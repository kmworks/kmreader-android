package com.kmworks.kmreader.core.download

import android.content.Context
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    appScope: CoroutineScope,
) {

    private val workManager get() = WorkManager.getInstance(context)
    private val downloadsDir get() = File(context.filesDir, "downloads")

    // Page-level concurrency: persisted to AppSettingsStore from the network settings page, passed to the Worker at enqueue time
    @Volatile
    private var pageConcurrency = AppSettingsStore.DEFAULT_DOWNLOAD_CONCURRENCY

    init {
        appScope.launch {
            appSettings.downloadConcurrency.collect { pageConcurrency = it }
        }
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

    /** Enqueue a whole-book download; a same-named task is REPLACE-restarted (retry semantics). libraryId filters queue tasks by library on the offline page; omitted keeps the old value. */
    fun enqueueBook(bookDtoId: String, title: String, pageCount: Int, libraryId: String? = null) {
        enqueueMeta.edit()
            .putString(bookDtoId, title)
            .putInt("$bookDtoId#pages", pageCount)
            .apply {
                if (libraryId != null) putString("$bookDtoId#library", libraryId)
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
            ))
            .setConstraints(constraints)
            .addTag(DownloadWorker.TAG)
            .addTag(bookDtoId)
            .build()
        workManager.enqueueUniqueWork(workName(bookDtoId), androidx.work.ExistingWorkPolicy.REPLACE, request)
        AppLog.i("Download", "enqueue: $title ($pageCount pages)")
        tick.value += 1
    }

    /** Real pause: mark the paused state and cancel the task; downloaded pages are kept (skipped automatically on resume). */
    suspend fun pauseBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        enqueueMeta.edit().putBoolean("$bookDtoId#paused", true).apply()
        workManager.cancelUniqueWork(workName(bookDtoId))
        AppLog.i("Download", "pause: ${enqueueMeta.getString(bookDtoId, null) ?: bookDtoId}")
        tick.value += 1
    }

    /** Resume download: clear the paused flag and re-enqueue (existing pages are skipped — natural resume). */
    suspend fun resumeBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        val title = enqueueMeta.getString(bookDtoId, null) ?: return@withContext
        val pageCount = enqueueMeta.getInt("$bookDtoId#pages", 0)
        if (pageCount <= 0) return@withContext
        enqueueMeta.edit().putBoolean("$bookDtoId#paused", false).apply()
        AppLog.i("Download", "resume: $title")
        enqueueBook(bookDtoId, title, pageCount)
    }

    /** Downloading queue (in progress + paused + failed). */
    val downloads: Flow<List<DownloadTaskUi>> =
        workManager.getWorkInfosByTagFlow(DownloadWorker.TAG)
            .combine(tick) { infos, _ -> infos }
            .map { infos ->
                val rows = infos.mapNotNull { info ->
                    val bookId = info.tags.firstOrNull { it != DownloadWorker.TAG }
                        ?: return@mapNotNull null
                    val title = enqueueMeta.getString(bookId, null) ?: bookId
                    val total = enqueueMeta.getInt("$bookId#pages", 0)
                    val paused = enqueueMeta.getBoolean("$bookId#paused", false)
                    val pageDone = info.progress.getInt(DownloadWorker.KEY_PAGE_DONE, 0)
                    val pageTotal = info.progress.getInt(DownloadWorker.KEY_PAGE_TOTAL, total)
                    when {
                        paused && info.state != WorkInfo.State.RUNNING -> DownloadTaskUi(
                            bookDtoId = bookId,
                            title = title,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Paused,
                        )
                        info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED -> DownloadTaskUi(
                            bookDtoId = bookId,
                            title = title,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Downloading,
                        )
                        info.state == WorkInfo.State.FAILED -> DownloadTaskUi(
                            bookDtoId = bookId,
                            title = title,
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
                        val pageTotal = enqueueMeta.getInt("$bookId#pages", 0)
                        if (!dir.isDirectory || pageTotal <= 0) return@mapNotNull null
                        val pageDone = dir.listFiles()
                            ?.count { it.name != DownloadWorker.META_FILE } ?: 0
                        // A fully downloaded one must not appear in the queue (the completed list takes over)
                        if (pageDone >= pageTotal) return@mapNotNull null
                        DownloadTaskUi(
                            bookDtoId = bookId,
                            title = enqueueMeta.getString(bookId, null) ?: bookId,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Paused,
                        )
                    }
                rows + pausedOrphans
            }
            .flowOn(Dispatchers.IO)

    /** Downloaded books (file scan; requires meta.txt and all page files). */
    suspend fun downloadedBooks(): List<DownloadedBookUi> = withContext(Dispatchers.IO) {
        downloadsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val meta = File(dir, DownloadWorker.META_FILE).takeIf { it.isFile } ?: return@mapNotNull null
            val props = meta.readLines().mapNotNull {
                val i = it.indexOf('=')
                if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
            }.toMap()
            val pageCount = props["pageCount"]?.toIntOrNull() ?: return@mapNotNull null
            val pages = dir.listFiles()?.count { it.name != DownloadWorker.META_FILE } ?: 0
            if (pages < pageCount) return@mapNotNull null
            DownloadedBookUi(
                bookDtoId = dir.name,
                title = props["title"] ?: dir.name,
                pageCount = pageCount,
                sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                libraryId = enqueueMeta.getString("${dir.name}#library", null),
            )
        }?.sortedBy { it.title } ?: emptyList()
    }

    /** Integrity check: the directory exists and every page is present. */
    suspend fun isBookDownloaded(bookDtoId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(downloadsDir, bookDtoId)
        val meta = File(dir, DownloadWorker.META_FILE)
        if (!meta.isFile) return@withContext false
        val pageCount = meta.readLines()
            .firstOrNull { it.startsWith("pageCount=") }
            ?.substringAfter('=')?.toIntOrNull() ?: return@withContext false
        val pages = dir.listFiles()?.count { it.name != DownloadWorker.META_FILE } ?: 0
        pages >= pageCount
    }

    /** Cancel the task and delete the downloaded page files. */
    suspend fun cancelBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(bookDtoId))
        AppLog.i("Download", "cancel: ${enqueueMeta.getString(bookDtoId, null) ?: bookDtoId}")
        enqueueMeta.edit()
            .remove(bookDtoId)
            .remove("$bookDtoId#pages")
            .remove("$bookDtoId#paused")
            .remove("$bookDtoId#library")
            .apply()
        File(downloadsDir, bookDtoId).deleteRecursively()
        tick.value += 1
    }

    /** Delete a completed book's files. */
    suspend fun deleteBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(bookDtoId))
        AppLog.i("Download", "delete: ${enqueueMeta.getString(bookDtoId, null) ?: bookDtoId}")
        enqueueMeta.edit()
            .remove(bookDtoId)
            .remove("$bookDtoId#pages")
            .remove("$bookDtoId#paused")
            .remove("$bookDtoId#library")
            .apply()
        File(downloadsDir, bookDtoId).deleteRecursively()
        tick.value += 1
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

    companion object {
        private const val KEY_WIFI_ONLY = "wifiOnly"
        private const val KEY_AUTO_UNREAD = "autoDownloadUnread"
    }
}
