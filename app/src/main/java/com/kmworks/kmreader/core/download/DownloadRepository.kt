package com.kmworks.kmreader.core.download

import android.content.Context
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kmworks.kmreader.core.data.CatalogCache
import com.kmworks.kmreader.core.data.CoverUrlRegistry
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Download management (MVP: whole-book download to the app-private directory downloads/{bookDtoId}/).
 * Queue state comes from WorkManager (tag = bookDtoId, shared tag = book_download);
 * the downloaded list and integrity checks use a file scan (meta.txt stores title/page count).
 */
@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("unused") private val sessionManager: SessionManager,
    appSettings: AppSettingsStore,
    private val appScope: CoroutineScope,
    private val catalogCache: CatalogCache,
) {

    private val workManager get() = WorkManager.getInstance(context)
    private val downloadsDir get() = File(context.filesDir, "downloads")

    // Page-level concurrency: persisted to AppSettingsStore from the network settings page, passed to the Worker at enqueue time
    @Volatile
    private var pageConcurrency = AppSettingsStore.DEFAULT_DOWNLOAD_CONCURRENCY

    /**
     * Series identity recovered from the catalog cache for downloads enqueued before #series existed,
     * memoized so a queue tick does not hit the database again.
     */
    private val legacySeries = ConcurrentHashMap<String, Pair<String, Int>>()

    /**
     * Latest queue state as badge marks (bookDtoId → mark). The file scan needs it to publish a single
     * [DownloadedIndex] that carries both finished and in-flight/failed books.
     */
    @Volatile
    private var latestTaskMarks: Map<String, DownloadMark> = emptyMap()

    /** Serializes index publication so a slow scan cannot overwrite a newer snapshot. */
    private val indexMutex = Mutex()

    /** Rescan completed downloads and publish the badge index (off the main thread). */
    fun refreshDownloadedIndex() {
        appScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                _downloadedIndex.value = scanDownloadedIndex(latestTaskMarks)
            }
        }
    }

    /** Rebuild and publish the index inline (already on an IO dispatcher, e.g. after a delete). */
    private suspend fun publishDownloadedIndex() {
        indexMutex.withLock {
            _downloadedIndex.value = scanDownloadedIndex(latestTaskMarks)
        }
    }

    private suspend fun scanDownloadedIndex(taskMarks: Map<String, DownloadMark>): DownloadedIndex {
        val completed = downloadsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> DownloadMeta.read(dir)?.takeIf { it.isComplete(dir) }?.let { dir to it } }
            .orEmpty()
        val books = completed.mapTo(mutableSetOf()) { (dir, _) -> StableIds.book(dir.name) }

        // Downloads made before the series was recorded recover it from the cached catalog, and the result is
        // written back into their metadata so the offline list and its detail menu pick it up too.
        val missing = completed.map { (dir, _) -> dir.name }
            .filter { enqueueMeta.getString("$it#series", null) == null && !legacySeries.containsKey(it) }
        if (missing.isNotEmpty()) {
            runCatching { catalogCache.downloadSeriesIdentity(missing) }
                .getOrDefault(emptyMap())
                .forEach { (bookId, identity) -> legacySeries[bookId] = identity }
            missing.forEach { bookId ->
                val identity = legacySeries[bookId] ?: return@forEach
                val editor = enqueueMeta.edit().putString("$bookId#series", identity.first)
                if (identity.second > 0) editor.putInt("$bookId#seriesCount", identity.second)
                editor.apply()
            }
        }

        val seriesDownloaded = mutableMapOf<String, Int>()
        val seriesTotals = mutableMapOf<String, Int>()
        val seriesDownloading = mutableMapOf<String, Int>()
        val seriesPending = mutableMapOf<String, Int>()
        val seriesFailed = mutableMapOf<String, Int>()
        completed.forEach { (dir, _) ->
            val bookId = dir.name
            val seriesDtoId = enqueueMeta.getString("$bookId#series", null) ?: return@forEach
            seriesDownloaded[seriesDtoId] = (seriesDownloaded[seriesDtoId] ?: 0) + 1
            val total = enqueueMeta.getInt("$bookId#seriesCount", 0)
            if (total > 0) seriesTotals[seriesDtoId] = maxOf(seriesTotals[seriesDtoId] ?: 0, total)
        }

        // Unfinished tasks: the badge follows the queue, but a book whose files are already complete keeps its
        // Full mark (a REPLACE restart of a finished download must not downgrade the card).
        val tasks = mutableMapOf<Int, DownloadMark>()
        taskMarks.forEach { (bookId, mark) ->
            val stableId = StableIds.book(bookId)
            if (stableId in books) return@forEach
            tasks[stableId] = mark
            val seriesDtoId = enqueueMeta.getString("$bookId#series", null) ?: return@forEach
            when (mark) {
                DownloadMark.Downloading ->
                    seriesDownloading[seriesDtoId] = (seriesDownloading[seriesDtoId] ?: 0) + 1
                DownloadMark.Pending ->
                    seriesPending[seriesDtoId] = (seriesPending[seriesDtoId] ?: 0) + 1
                DownloadMark.Failed ->
                    seriesFailed[seriesDtoId] = (seriesFailed[seriesDtoId] ?: 0) + 1
                else -> Unit
            }
            val total = enqueueMeta.getInt("$bookId#seriesCount", 0)
            if (total > 0) seriesTotals[seriesDtoId] = maxOf(seriesTotals[seriesDtoId] ?: 0, total)
        }

        val seriesIds = seriesDownloaded.keys + seriesDownloading.keys + seriesPending.keys + seriesFailed.keys
        return DownloadedIndex(
            books = books,
            tasks = tasks,
            series = seriesIds.associateWith { seriesDtoId ->
                SeriesDownloadAggregate(
                    downloaded = seriesDownloaded[seriesDtoId] ?: 0,
                    total = seriesTotals[seriesDtoId] ?: 0,
                    downloading = seriesDownloading[seriesDtoId] ?: 0,
                    pending = seriesPending[seriesDtoId] ?: 0,
                    failed = seriesFailed[seriesDtoId] ?: 0,
                )
            },
        )
    }

    // Enqueue metadata (WorkInfo carries no inputData; title/page count live here for queue rows)
    private val enqueueMeta
        get() = context.getSharedPreferences("download_enqueue_meta", Context.MODE_PRIVATE)

    // Download policy (mirrors the Wi-Fi/auto-download toggles of OfflinePolicySheet)
    private val settingsPrefs
        get() = context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)

    private val _wifiOnly = MutableStateFlow(settingsPrefs.getBoolean(KEY_WIFI_ONLY, false))

    /** Wi-Fi-only download: when on, tasks are constrained to an UNMETERED network. */
    val wifiOnly: Flow<Boolean> = _wifiOnly

    fun setWifiOnly(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        _wifiOnly.value = enabled
    }

    private val _autoDownloadUnread = MutableStateFlow(settingsPrefs.getBoolean(KEY_AUTO_UNREAD, false))

    /**
     * Auto-download unread content: the toggle persists here; the trigger is AutoDownloadUnreadTrigger (SSE book/series events),
     * together with the series detail page's autoDownloadUnreadIfEnabled (top-up downloads of that series' unread books when the detail opens).
     */
    val autoDownloadUnread: Flow<Boolean> = _autoDownloadUnread

    fun setAutoDownloadUnread(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_AUTO_UNREAD, enabled).apply()
        _autoDownloadUnread.value = enabled
    }

    // Local actions (pause/resume/enqueue) drive a re-composition of the downloads flow via tick
    private val tick = MutableStateFlow(0)

    private val _downloadedIndex = MutableStateFlow(DownloadedIndex())

    /**
     * Completed downloads as card-badge state (stable book ids + per-series aggregates). Refreshed after
     * queue transitions and every explicit rescan, so cards never scan the disk themselves.
     */
    val downloadedIndex: StateFlow<DownloadedIndex> = _downloadedIndex

    /**
     * Enqueue a whole-book download; a same-named task is REPLACE-restarted (retry semantics).
     * The media type decides what is downloaded (page images vs the single book file), see [DownloadSpec].
     * libraryId filters queue tasks by library on the offline page; omitted keeps the old value.
     */
    fun enqueueBook(
        bookDtoId: String,
        title: String,
        pageCount: Int,
        mediaType: String = "",
        epubDivinaCompatible: Boolean = false,
        libraryId: String? = null,
        /** Owning series: lets downloaded books carry a series badge and the offline row open the series detail. */
        seriesDtoId: String? = null,
        /** Books in the owning series when known (0/absent = unknown, the badge stays "partial"). */
        seriesBookCount: Int? = null,
    ) {
        enqueue(
            bookDtoId,
            title,
            pageCount,
            DownloadSpec.forMedia(mediaType, epubDivinaCompatible),
            libraryId,
            seriesDtoId,
            seriesBookCount,
        )
    }

    /**
     * Re-enqueue a paused/failed task using the kind and format recorded at enqueue time, so a retry
     * never needs the book DTO (and legacy page downloads keep their layout).
     */
    fun retryBook(bookDtoId: String) {
        val title = enqueueMeta.getString(bookDtoId, null) ?: return
        val pageCount = enqueueMeta.getInt("$bookDtoId#pages", 0)
        if (pageCount <= 0) return
        val kind = DownloadKind.fromMeta(enqueueMeta.getString("$bookDtoId#kind", null))
        val format = enqueueMeta.getString("$bookDtoId#format", null)
            .orEmpty()
            .ifBlank { if (kind == DownloadKind.Pages) DownloadSpec.FORMAT_IMAGES else "bin" }
        enqueue(bookDtoId, title, pageCount, DownloadSpec(kind, format), libraryId = null)
    }

    private fun enqueue(
        bookDtoId: String,
        title: String,
        pageCount: Int,
        spec: DownloadSpec,
        libraryId: String?,
        seriesDtoId: String? = null,
        seriesBookCount: Int? = null,
    ) {
        enqueueMeta.edit()
            .putString(bookDtoId, title)
            .putInt("$bookDtoId#pages", pageCount)
            .putString("$bookDtoId#kind", spec.kind.metaValue)
            .putString("$bookDtoId#format", spec.format)
            .apply {
                if (libraryId != null) putString("$bookDtoId#library", libraryId)
                if (seriesDtoId != null) putString("$bookDtoId#series", seriesDtoId)
                if (seriesBookCount != null && seriesBookCount > 0) {
                    putInt("$bookDtoId#seriesCount", seriesBookCount)
                }
            }
            .apply()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (_wifiOnly.value) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED,
            )
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(
                DownloadWorker.KEY_BOOK_ID to bookDtoId,
                DownloadWorker.KEY_TITLE to title,
                DownloadWorker.KEY_PAGE_COUNT to pageCount,
                DownloadWorker.KEY_CONCURRENCY to pageConcurrency,
                DownloadWorker.KEY_KIND to spec.kind.metaValue,
                DownloadWorker.KEY_FORMAT to spec.format,
            ))
            .setConstraints(constraints)
            .addTag(DownloadWorker.TAG)
            .addTag(bookDtoId)
            .build()
        workManager.enqueueUniqueWork(workName(bookDtoId), androidx.work.ExistingWorkPolicy.REPLACE, request)
        AppLog.i("Download", "enqueue: $title (${spec.kind.metaValue}, $pageCount pages)")
        tick.value += 1
    }

    /** Real pause: mark the paused state and cancel the task; downloaded pages are kept (skipped automatically on resume). */
    suspend fun pauseBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        enqueueMeta.edit().putBoolean("$bookDtoId#paused", true).apply()
        workManager.cancelUniqueWork(workName(bookDtoId))
        AppLog.i("Download", "pause: ${enqueueMeta.getString(bookDtoId, null) ?: bookDtoId}")
        tick.value += 1
    }

    /** Resume download: clear the paused flag and re-enqueue (existing content is skipped — natural resume). */
    suspend fun resumeBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        val title = enqueueMeta.getString(bookDtoId, null) ?: return@withContext
        enqueueMeta.edit().putBoolean("$bookDtoId#paused", false).apply()
        AppLog.i("Download", "resume: $title")
        retryBook(bookDtoId)
    }

    /** Downloading queue (in progress + paused + failed). */
    val downloads: Flow<List<DownloadTaskUi>> =
        workManager.getWorkInfosByTagFlow(DownloadWorker.TAG)
            .combine(tick) { infos, _ -> infos }
            .map { infos ->
                val rows = infos.mapNotNull { info ->
                    val bookId = bookIdFromTags(info.tags) ?: return@mapNotNull null
                    val title = enqueueMeta.getString(bookId, null) ?: bookId
                    val total = enqueueMeta.getInt("$bookId#pages", 0)
                    val paused = enqueueMeta.getBoolean("$bookId#paused", false)
                    val kind = DownloadKind.fromMeta(enqueueMeta.getString("$bookId#kind", null))
                    val pageDone = info.progress.getInt(DownloadWorker.KEY_PAGE_DONE, 0)
                    val pageTotal = info.progress.getInt(DownloadWorker.KEY_PAGE_TOTAL, total)
                    when {
                        paused && info.state != WorkInfo.State.RUNNING -> DownloadTaskUi(
                            bookDtoId = bookId,
                            title = title,
                            kind = kind,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Paused,
                        )
                        info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED -> DownloadTaskUi(
                            bookDtoId = bookId,
                            title = title,
                            kind = kind,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Downloading,
                        )
                        info.state == WorkInfo.State.FAILED -> DownloadTaskUi(
                            bookDtoId = bookId,
                            title = title,
                            kind = kind,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Failed,
                        )
                        else -> null
                    }
                }
                // Paused entries whose WorkInfo was already pruned (after a restart): backfill Paused rows from the file scan
                val present = rows.map { it.bookDtoId }.toSet()
                val pausedOrphans = enqueueMeta.all
                    .filter { it.key.endsWith("#paused") && it.value == true }
                    .map { it.key.removeSuffix("#paused") }
                    .filter { it !in present }
                    .mapNotNull { bookId ->
                        val dir = File(downloadsDir, bookId)
                        if (!dir.isDirectory) return@mapNotNull null
                        val meta = DownloadMeta.read(dir) ?: return@mapNotNull null
                        if (meta.kind == DownloadKind.Pages && meta.pageCount <= 0) return@mapNotNull null
                        // A fully downloaded one must not appear in the queue (the completed list takes over)
                        if (meta.isComplete(dir)) return@mapNotNull null
                        DownloadTaskUi(
                            bookDtoId = bookId,
                            title = enqueueMeta.getString(bookId, null) ?: bookId,
                            kind = meta.kind,
                            pageDone = if (meta.kind == DownloadKind.Pages) meta.pageFiles(dir).size else 0,
                            pageTotal = if (meta.kind == DownloadKind.Pages) meta.pageCount else 1,
                            status = DownloadTaskUi.Status.Paused,
                        )
                    }
                rows + pausedOrphans
            }
            .flowOn(Dispatchers.IO)

    /** Downloaded books (file scan; the meta kind decides completeness: all pages, or the single file). */
    suspend fun downloadedBooks(): List<DownloadedBookUi> = withContext(Dispatchers.IO) {
        // Keep the badge index in sync with every explicit rescan (the offline page calls this after deletes).
        publishDownloadedIndex()
        downloadsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val meta = DownloadMeta.read(dir) ?: return@mapNotNull null
            if (!meta.isComplete(dir)) return@mapNotNull null
            // The offline list is built from the file scan alone: no DTO has been mapped on this launch, so the
            // cover registry has no book → dto id mapping yet. Register it here or every offline cover is a miss.
            CoverUrlRegistry.registerBook(StableIds.book(dir.name), dir.name)
            DownloadedBookUi(
                bookDtoId = dir.name,
                title = meta.title ?: dir.name,
                pageCount = meta.pageCount,
                sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                libraryId = enqueueMeta.getString("${dir.name}#library", null),
                kind = meta.kind,
                format = meta.fileFormat,
                seriesDtoId = enqueueMeta.getString("${dir.name}#series", null),
            )
        }?.sortedBy { it.title } ?: emptyList()
    }

    /** Integrity check: the directory exists and holds everything the meta kind requires. */
    suspend fun isBookDownloaded(bookDtoId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(downloadsDir, bookDtoId)
        DownloadMeta.read(dir)?.isComplete(dir) == true
    }

    /** Cancel the task and delete the downloaded content. */
    suspend fun cancelBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(bookDtoId))
        AppLog.i("Download", "cancel: ${enqueueMeta.getString(bookDtoId, null) ?: bookDtoId}")
        clearEnqueueMeta(bookDtoId)
        File(downloadsDir, bookDtoId).deleteRecursively()
        publishDownloadedIndex()
        tick.value += 1
    }

    /** Delete a completed book's files. */
    suspend fun deleteBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(bookDtoId))
        AppLog.i("Download", "delete: ${enqueueMeta.getString(bookDtoId, null) ?: bookDtoId}")
        clearEnqueueMeta(bookDtoId)
        File(downloadsDir, bookDtoId).deleteRecursively()
        publishDownloadedIndex()
        tick.value += 1
    }

    private fun clearEnqueueMeta(bookDtoId: String) {
        enqueueMeta.edit()
            .remove(bookDtoId)
            .remove("$bookDtoId#pages")
            .remove("$bookDtoId#paused")
            .remove("$bookDtoId#library")
            .remove("$bookDtoId#kind")
            .remove("$bookDtoId#format")
            .remove("$bookDtoId#series")
            .remove("$bookDtoId#seriesCount")
            .apply()
    }

    /** Total bytes used by the download directory. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        downloadsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Total device storage bytes (the partition holding filesDir). */
    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.blockSizeLong * stat.blockCountLong
    }

    /** Owning library id recorded at enqueue time (used to filter queue tasks by library on the offline page). */
    fun libraryIdOf(bookDtoId: String): String? =
        enqueueMeta.getString("$bookDtoId#library", null)

    private fun workName(bookDtoId: String) = "download_$bookDtoId"

    /**
     * Deferred to the end of the class body on purpose: the init block starts coroutines that read the
     * properties above ([downloads], [_downloadedIndex], [legacySeries]). Kotlin initializes properties and
     * runs init blocks in declaration order, so an init block placed earlier would observe those fields as null
     * from the worker thread (crash: MutableStateFlow.setValue on a null reference).
     */
    init {
        appScope.launch {
            appSettings.downloadConcurrency.collect { pageConcurrency = it }
        }
        refreshDownloadedIndex()
        // A task reaching SUCCEEDED/CANCELLED changes what is on disk; progress updates do not, so only
        // (book, status) pairs are compared. The marks also feed the card badges (pending/spinning/failed).
        appScope.launch {
            downloads
                .map { rows -> rows.associate { it.bookDtoId to it.status.toDownloadMark() } }
                .distinctUntilChanged()
                .collect { marks ->
                    latestTaskMarks = marks
                    refreshDownloadedIndex()
                }
        }
        // Refreshed catalog rows carry the series id, which backfills downloads enqueued before it existed.
        // Debounced because a single screen load writes several pages in a row.
        appScope.launch {
            catalogCache.savedBooks.collectLatest {
                delay(500)
                refreshDownloadedIndex()
            }
        }
    }

    companion object {
        private const val KEY_WIFI_ONLY = "wifiOnly"
        private const val KEY_AUTO_UNREAD = "autoDownloadUnread"

        /**
         * WorkManager tags every request with the worker class FQCN in addition to the tags we add, so the tag
         * set is {FQCN, [DownloadWorker.TAG], bookDtoId}. Only the book dto id identifies the book: both
         * synthetic tags are excluded, and anything unexpected is rejected rather than surfaced as a queue row
         * titled by a class name.
         */
        internal fun bookIdFromTags(tags: Collection<String>): String? =
            tags.filterNot(::isSyntheticTag).singleOrNull()?.takeIf { it.isNotBlank() }

        private fun isSyntheticTag(tag: String): Boolean =
            tag == DownloadWorker.TAG ||
                tag == DownloadWorker::class.java.name ||
                tag.endsWith(".ConstraintTrackingWorker")
    }
}
