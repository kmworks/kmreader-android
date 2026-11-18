package com.kmworks.kmreader.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.CatalogCache
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ImageCacheStore
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.komf.KomfJobState
import com.kmworks.kmreader.core.komf.KomfRepository
import com.kmworks.kmreader.core.komf.KomfSearchResultDto
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import com.kmworks.kmreader.core.tracker.TrackerRepository
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.tracker.TrackerLink
import com.kmworks.kmreader.ui.tracker.toUiTrackerLink
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Series detail state; when series/books are null the UI falls back to the sample data passed in. */
data class SeriesDetailUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val series: Series? = null,
    val books: List<Book>? = null,
    val seriesThumbnail: String? = null,
    /** Membership collections (real data; mapped by Mappers.toListEntry, a tap is bubbled up via onOpenCollection). */
    val collections: List<ListEntry> = emptyList(),
    /** komf capability: hasFeature(Komf) && admin && DTO mapping exists; when false the three menu items are not rendered. */
    val komfAvailable: Boolean = false,
    /** Whether the current account is an admin (gates the analyze/refresh-metadata/delete menu items, mirroring iOS current.isAdmin). */
    val isAdmin: Boolean = false,
    /** libraryId of the current series (straight from the DTO, needed for komf calls). */
    val libraryId: String? = null,
    val komfSearching: Boolean = false,
    val komfSearchFailed: Boolean = false,
    val komfSearchResults: List<KomfSearchResultDto> = emptyList(),
    /** A komf job is being polled (used for the sheet submit state and menu disabling). */
    val komfJobRunning: Boolean = false,
    /** kmrs Trackers capability (false without a session or on a stock server). */
    val trackerSupported: Boolean = false,
    /** Tracker entries already bound (non-empty only when trackerSupported and values exist). */
    val trackerLinks: List<TrackerLink> = emptyList(),
    /** Initial values for metadata editing (straight from the DTO; null without a session or for demo data). */
    val editable: SeriesEditValues? = null,
)

/** Series detail data: resolves the DTO id through the registry, then loads the series and its book list. */
@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val downloadRepository: DownloadRepository,
    private val komfRepository: KomfRepository,
    private val trackerRepository: TrackerRepository,
    private val serverProfileStore: ServerProfileStore,
    private val configStore: ServerConfigStore,
    private val sseService: SseService,
    @ApplicationContext private val context: Context,
    private val imageCacheStore: ImageCacheStore,
    private val catalogCache: CatalogCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SeriesDetailUiState())
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

    /** One-shot komf notifications (completion/failure/timeout) consumed as detail-page snackbars. */
    private val _komfEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val komfEvents: SharedFlow<String> = _komfEvents.asSharedFlow()

    private val _collections = MutableStateFlow<List<ListEntry>?>(null)
    val collections: StateFlow<List<ListEntry>?> = _collections.asStateFlow()

    private val _readLists = MutableStateFlow<List<ListEntry>?>(null)
    val readLists: StateFlow<List<ListEntry>?> = _readLists.asStateFlow()

    private val _downloaded = MutableStateFlow<Map<Int, Boolean>>(emptyMap())

    /** Whether each book (by stableId) is fully downloaded locally. */
    val downloaded: StateFlow<Map<Int, Boolean>> = _downloaded.asStateFlow()

    private var loadedId: Int? = null
    /** The entry the current state content belongs to (content resets when the entry changes, but a forced
     *  refresh of the same entry keeps it). */
    private var contentId: Int? = null
    private var sseJob: Job? = null
    private var loadJob: Job? = null

    /** DTO id of the current detail (used by komf calls and for refreshing after a job completes). */
    private var dtoId: String? = null
    private var komfSearchJob: Job? = null

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.seriesDtoId(stableId)
        this.dtoId = dtoId
        // When the entry changes, clear the previous entry's content and its satellite state so stale data
        // does not flash during loading; a forced refresh of the same entry (re-entered after reloadDetail
        // cleared loadedId) keeps the content and refreshes silently.
        loadJob?.cancel()
        if (contentId != stableId) {
            _uiState.value = SeriesDetailUiState(hasSession = clients != null)
            _readLists.value = null
            _collections.value = null
            _downloaded.value = emptyMap()
        }
        if (clients == null || dtoId == null) {
            _uiState.update {
                it.copy(
                    hasSession = clients != null,
                    loading = false,
                    komfAvailable = false,
                    trackerSupported = trackerRepository.supported,
                )
            }
            return
        }
        _uiState.update { it.copy(hasSession = true, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val (seriesDto, bookDtos) = coroutineScope {
                    val s = async { detailRepository.seriesDetail(dtoId) }
                    val b = async { detailRepository.seriesBooks(dtoId) }
                    s.await() to b.await()
                }
                // komf capability gate: kmrs feature + admin (mirrors KomfIntegrationStore)
                val isAdmin = configStore.config.first()?.isAdmin == true
                val komfAvailable = serverProfileStore.hasFeature(ServerFeature.Komf) && isAdmin
                // Membership collections: failures yield an empty list and never block the detail body
                val memberships = detailRepository.seriesCollections(dtoId)
                    .map { it.toListEntry() }
                val books = bookDtos.map { it.toUiBook() }
                _uiState.update {
                    it.copy(
                        loading = false,
                        series = seriesDto?.toUiSeries(),
                        books = books,
                        seriesThumbnail = detailRepository.seriesThumbnailUrl(dtoId),
                        collections = memberships,
                        komfAvailable = komfAvailable,
                        isAdmin = isAdmin,
                        trackerSupported = trackerRepository.supported,
                        editable = seriesDto?.metadata?.let { metadata ->
                            SeriesEditValues(
                                title = metadata.title,
                                titleSort = metadata.titleSort,
                                summary = metadata.summary,
                                status = metadata.status,
                                readingDirection = metadata.readingDirection,
                                language = metadata.language,
                                publisher = metadata.publisher,
                                ageRating = metadata.ageRating?.toString().orEmpty(),
                                totalBookCount = metadata.totalBookCount?.toString().orEmpty(),
                                genres = metadata.genres.toList(),
                                tags = metadata.tags.toList(),
                                links = metadata.links.map { it.label to it.url },
                                alternateTitles = metadata.alternateTitles.map { it.label to it.title },
                                sharingLabels = metadata.sharingLabels.toList(),
                                locks = SeriesEditLocks(
                                    titleLock = metadata.titleLock,
                                    titleSortLock = metadata.titleSortLock,
                                    summaryLock = metadata.summaryLock,
                                    statusLock = metadata.statusLock,
                                    readingDirectionLock = metadata.readingDirectionLock,
                                    languageLock = metadata.languageLock,
                                    publisherLock = metadata.publisherLock,
                                    ageRatingLock = metadata.ageRatingLock,
                                    totalBookCountLock = metadata.totalBookCountLock,
                                    genresLock = metadata.genresLock,
                                    tagsLock = metadata.tagsLock,
                                    linksLock = metadata.linksLock,
                                    alternateTitlesLock = metadata.alternateTitlesLock,
                                    sharingLabelsLock = metadata.sharingLabelsLock,
                                ),
                            )
                        },
                        libraryId = seriesDto?.libraryId,
                    )
                }
                contentId = stableId
                refreshDownloaded()
                autoDownloadUnreadIfEnabled()
                refreshTrackerLinks()
                observeReadProgressEvents()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: context.getString(R.string.common_load_failed))
                }
            }
        }
    }

    /** Loads the collections on demand (for the "add to collection" picker). */
    fun ensureCollectionsLoaded() {
        if (_collections.value != null) return
        viewModelScope.launch {
            _collections.value = listRepository.collections().map { dto ->
                ListEntry(
                    id = StableIds.collection(dto.id),
                    title = dto.name,
                    count = dto.seriesIds.size,
                    hue = hueFor(dto.id),
                    pinned = false,
                )
            }
        }
    }

    /** Marks the whole series as read; false means there is no DTO mapping (demo data, handled locally by the UI). */
    fun markSeriesRead(): Boolean {
        val dtoId = loadedId?.let { detailRepository.seriesDtoId(it) } ?: return false
        viewModelScope.launch {
            if (actionRepository.markSeriesRead(dtoId)) {
                _uiState.update {
                    it.copy(
                        series = it.series?.copy(progress = 1f, unreadCount = 0),
                        books = it.books?.map { b -> b.copy(progress = 1f) },
                    )
                }
            }
        }
        return true
    }

    /** Adds the current series to a collection; false means there is no DTO mapping. */
    fun addToCollection(entry: ListEntry): Boolean {
        val seriesDtoId = loadedId?.let { detailRepository.seriesDtoId(it) } ?: return false
        val collectionDtoId = detailRepository.collectionDtoId(entry.id) ?: return false
        viewModelScope.launch {
            actionRepository.addSeriesToCollection(collectionDtoId, seriesDtoId)
        }
        return true
    }

    /** Loads the read lists on demand (for the "add to reading list" picker). */
    fun ensureReadListsLoaded() {
        if (_readLists.value != null) return
        viewModelScope.launch {
            _readLists.value = listRepository.readLists().map { dto ->
                ListEntry(
                    id = StableIds.readList(dto.id),
                    title = dto.name,
                    count = dto.bookIds.size,
                    hue = hueFor(dto.id),
                    pinned = false,
                )
            }
        }
    }

    /** Batch-marks the selected books as read/unread; false means real DTO mappings are missing. */
    fun markBooksRead(stableIds: Set<Int>, read: Boolean): Boolean {
        val books = _uiState.value.books.orEmpty().filter { it.id in stableIds }
        val pairs = books.mapNotNull { book ->
            detailRepository.bookDtoId(book.id)?.let { book to it }
        }
        if (pairs.isEmpty()) return false
        viewModelScope.launch {
            var ok = true
            pairs.forEach { (_, dtoId) ->
                val success = if (read) {
                    actionRepository.markBookRead(dtoId)
                } else {
                    actionRepository.markBookUnread(dtoId)
                }
                ok = ok && success
            }
            if (ok) {
                val readIds = pairs.map { it.first.id }.toSet()
                _uiState.update { state ->
                    state.copy(
                        books = state.books?.map { book ->
                            if (book.id in readIds) book.copy(progress = if (read) 1f else 0f) else book
                        },
                    )
                }
            }
        }
        return true
    }

    /** Batch-downloads the selected books; false means there is no real downloadable book. */
    fun downloadBooks(stableIds: Set<Int>): Boolean {
        if (sessionManager.clients.value == null) return false
        val books = _uiState.value.books.orEmpty().filter { it.id in stableIds }
        var enqueued = false
        books.forEach { book ->
            val dtoId = detailRepository.bookDtoId(book.id) ?: return@forEach
            downloadRepository.enqueueBook(dtoId, book.title, book.pageCount)
            enqueued = true
        }
        if (enqueued) refreshDownloaded()
        return enqueued
    }

    /** Batch-adds books to a reading list; false means the picker entry or the books lack real DTO mappings. */
    fun addBooksToReadList(entry: ListEntry, stableIds: Set<Int>): Boolean {
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return false
        val bookDtoIds = stableIds.mapNotNull { detailRepository.bookDtoId(it) }
        if (bookDtoIds.isEmpty()) return false
        viewModelScope.launch {
            actionRepository.addBooksToReadList(readListDtoId, bookDtoIds)
        }
        return true
    }

    /** Deletes the selected books' files; on success they are removed from the local detail list and the download state is refreshed. */
    fun deleteBooks(stableIds: Set<Int>, onDone: (Boolean) -> Unit) {
        val pairs = _uiState.value.books.orEmpty()
            .filter { it.id in stableIds }
            .mapNotNull { book -> detailRepository.bookDtoId(book.id)?.let { book to it } }
        if (pairs.isEmpty()) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            var ok = true
            // Purge each deleted book's cached pages while its page list still exists.
            pairs.forEach { (_, dtoId) -> imageCacheStore.clearBookPages(dtoId) }
            pairs.forEach { (_, dtoId) -> ok = actionRepository.deleteBook(dtoId) && ok }
            if (ok) {
                pairs.forEach { (_, dtoId) -> catalogCache.deleteBook(dtoId) }
                val removedIds = pairs.map { it.first.id }.toSet()
                _uiState.update { state ->
                    state.copy(books = state.books?.filterNot { it.id in removedIds })
                }
                refreshDownloaded()
            }
            onDone(ok)
        }
    }

    /** Downloads every book of the series; false means no session / no DTO mapping (demo data). */
    fun downloadSeries(): Boolean {
        if (sessionManager.clients.value == null) return false
        val books = _uiState.value.books ?: return false
        var enqueued = false
        books.forEach { book ->
            val dtoId = detailRepository.bookDtoId(book.id) ?: return@forEach
            downloadRepository.enqueueBook(dtoId, book.title, book.pageCount)
            enqueued = true
        }
        return enqueued
    }

    /** Refreshes each book's local downloaded state (file scan). */
    /** Auto-download unread: once the policy is on, opening the series detail queues unread books that are not downloaded yet. */
    private suspend fun autoDownloadUnreadIfEnabled() {
        if (!downloadRepository.autoDownloadUnread.first()) return
        _uiState.value.books.orEmpty().filter { (it.progress ?: 0f) < 1f }.forEach { book ->
            val bookDtoId = detailRepository.bookDtoId(book.id) ?: return@forEach
            if (_downloaded.value[book.id] != true) {
                downloadRepository.enqueueBook(bookDtoId, book.title, book.pageCount)
            }
        }
    }

    fun refreshDownloaded() {
        val books = _uiState.value.books ?: return
        viewModelScope.launch {
            val map = books.mapNotNull { book ->
                val dtoId = detailRepository.bookDtoId(book.id) ?: return@mapNotNull null
                book.id to downloadRepository.isBookDownloaded(dtoId)
            }.toMap()
            _downloaded.value = map
        }
    }

    /** Tracker binding refresh: only fetched when the kmrs Trackers capability exists; failures keep the old values
     *  (mirroring the TrackerSeriesView reload). */
    fun refreshTrackerLinks() {
        if (!trackerRepository.supported) return
        val dtoId = dtoId ?: return
        viewModelScope.launch {
            val links = trackerRepository.links(dtoId) ?: return@launch
            val enriched = coroutineScope {
                links.map { link ->
                    async {
                        val remoteState =
                            trackerRepository.remoteState(link.provider, link.trackId)
                        link.toUiTrackerLink(state = remoteState)
                    }
                }.awaitAll()
            }
            _uiState.update { it.copy(trackerLinks = enriched) }
        }
    }

    /** komf search (300ms debounce; mirrors the task(id:) debounce semantics of KomfIdentifySheet). */
    fun komfSearch(query: String) {
        komfSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(komfSearching = false, komfSearchFailed = false, komfSearchResults = emptyList())
            }
            return
        }
        val dtoId = dtoId ?: return
        val libraryId = _uiState.value.libraryId ?: return
        komfSearchJob = viewModelScope.launch {
            delay(300)
            _uiState.update { it.copy(komfSearching = true, komfSearchFailed = false) }
            val results = komfRepository.search(trimmed, libraryId, dtoId)
            _uiState.update {
                if (results == null) {
                    it.copy(komfSearching = false, komfSearchFailed = true, komfSearchResults = emptyList())
                } else {
                    it.copy(komfSearching = false, komfSearchResults = results)
                }
            }
        }
    }

    /** Submits the komf identify request; on success the sheet closes and the job starts polling (mirrors submit()). */
    fun komfIdentify(
        provider: String,
        providerSeriesId: String,
        onSubmitted: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val dtoId = dtoId ?: return onFailed()
        viewModelScope.launch {
            val jobId = komfRepository.identify(_uiState.value.libraryId, dtoId, provider, providerSeriesId)
            if (jobId != null) {
                onSubmitted()
                trackKomfJob(jobId)
            } else {
                onFailed()
            }
        }
    }

    /** Matches with komf (mirrors KomfMenu.match). */
    fun komfMatch() {
        val dtoId = dtoId ?: return
        val libraryId = _uiState.value.libraryId ?: return
        if (_uiState.value.komfJobRunning) return
        viewModelScope.launch {
            val jobId = komfRepository.match(libraryId, dtoId)
            if (jobId != null) {
                _komfEvents.emit(context.getString(R.string.komf_event_match_submitted))
                trackKomfJob(jobId)
            } else {
                _komfEvents.emit(context.getString(R.string.komf_event_match_failed))
            }
        }
    }

    /** Resets komf (not a job: on success the detail is refreshed directly, mirroring resetSeries). */
    fun komfReset() {
        val dtoId = dtoId ?: return
        val libraryId = _uiState.value.libraryId ?: return
        viewModelScope.launch {
            if (komfRepository.reset(libraryId, dtoId)) {
                _komfEvents.emit(context.getString(R.string.komf_event_reset_done))
                reloadDetail()
            } else {
                _komfEvents.emit(context.getString(R.string.komf_event_reset_failed))
            }
        }
    }

    /** Analyzes the series (admin only; the analysis is async on the server, so the call result is the outcome). */
    fun analyzeSeries(onDone: (Boolean) -> Unit) {
        val dtoId = dtoId ?: return onDone(false)
        viewModelScope.launch { onDone(actionRepository.analyzeSeries(dtoId)) }
    }

    /** Refreshes the series metadata; on success the detail is refreshed. */
    fun refreshSeriesMetadata(onDone: (Boolean) -> Unit) {
        val dtoId = dtoId ?: return onDone(false)
        viewModelScope.launch {
            val ok = actionRepository.refreshSeriesMetadata(dtoId)
            if (ok) reloadDetail()
            onDone(ok)
        }
    }

    /** Deletes the series files; on success the page navigates back. */
    fun deleteSeries(onDone: (Boolean) -> Unit) {
        val dtoId = dtoId ?: return onDone(false)
        viewModelScope.launch {
            val ok = actionRepository.deleteSeries(dtoId)
            if (ok) catalogCache.deleteSeries(dtoId)
            onDone(ok)
        }
    }

    /** Komga web URL of the series (used for sharing). */
    fun seriesWebUrl(): String? {
        val clients = sessionManager.clients.value ?: return null
        val dtoId = dtoId ?: return null
        return "${clients.baseUrl}/series/$dtoId"
    }

    /** Polls the job to its final state; completion/timeout refreshes the detail on a best-effort basis (mirrors KomfJobTracker). */
    private fun trackKomfJob(jobId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(komfJobRunning = true) }
            komfRepository.trackJob(jobId).collect { state ->
                when (state) {
                    KomfJobState.Running -> Unit
                    KomfJobState.Completed -> {
                        _komfEvents.emit(context.getString(R.string.komf_event_job_completed))
                        reloadDetail()
                    }
                    is KomfJobState.Failed -> {
                        val message = state.message?.takeIf { it.isNotBlank() }?.let {
                            context.getString(R.string.komf_event_job_failed_with_reason, it)
                        } ?: context.getString(R.string.komf_event_job_failed)
                        _komfEvents.emit(message)
                    }
                    KomfJobState.TimedOut -> {
                        _komfEvents.emit(context.getString(R.string.komf_event_job_tracking_stopped))
                        reloadDetail()
                    }
                }
            }
            _uiState.update { it.copy(komfJobRunning = false) }
        }
    }

    /** Reloads the detail after a job completes (resets loadedId and re-enters the normal load path). */
    private fun reloadDetail() {
        val stableId = loadedId ?: return
        loadedId = null
        load(stableId)
    }

    /**
     * Saves the series metadata; a null dto means nothing changed (close immediately without a request).
     * On success the detail is reloaded so the hero/summary/chips immediately reflect the server data.
     */
    fun saveMetadata(dto: SeriesMetadataUpdateDto?, onDone: (Boolean) -> Unit) {
        val seriesDtoId = dtoId
        if (dto == null) {
            onDone(true)
            return
        }
        if (seriesDtoId == null) {
            _komfEvents.tryEmit(context.getString(R.string.detail_save_failed_no_server_data))
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.updateSeriesMetadata(seriesDtoId, dto)
            if (ok) {
                reloadDetail()
            } else {
                _komfEvents.tryEmit(context.getString(R.string.detail_save_failed))
            }
            onDone(ok)
        }
    }

    /**
     * Read-progress events: the tracker advancement for finished books happens on the server (mirroring the
     * original app, the client pushes nothing); ReadProgress/Book SSE events are subscribed and, after a
     * debounce, the book list and tracker bindings are reloaded to reflect the server-side progress.
     */
    private fun observeReadProgressEvents() {
        if (sseJob != null) return
        sseJob = viewModelScope.launch {
            sseService.events
                .filter { it.kind in setOf(SseEventKind.ReadProgress, SseEventKind.Book) }
                .debounce(3_000)
                .collect {
                    reloadBooks()
                    refreshTrackerLinks()
                }
        }
    }

    /** Reloads only the book list (triggered by SSE; the detail body is unchanged). Covers are resolved through the stableId registry. */
    private fun reloadBooks() {
        val dtoId = dtoId ?: return
        viewModelScope.launch {
            val bookDtos = detailRepository.seriesBooks(dtoId)
            val books = bookDtos.map { it.toUiBook() }
            _uiState.update { it.copy(books = books) }
            refreshDownloaded()
        }
    }

    /** Book metadata save (the BookEditSheet flow, mirroring saveMetadata): no changes succeed directly; on success the book list is reloaded. */
    fun saveBookMetadata(stableId: Int, baseTitle: String, draft: BookMetadataDraft, onDone: (Boolean) -> Unit) {
        val bookDtoId = detailRepository.bookDtoId(stableId) ?: return onDone(false)
        val body = draft.toUpdateDto(baseTitle, null) ?: return onDone(true)
        viewModelScope.launch {
            val ok = actionRepository.updateBookMetadata(bookDtoId, body)
            if (ok) reloadBooks()
            onDone(ok)
        }
    }

    /** Re-analyzes a book (thin wrapper: dtoId lookup + ActionRepository; on success the book list is reloaded). */
    fun analyzeBook(stableId: Int, onDone: (Boolean) -> Unit) {
        val bookDtoId = detailRepository.bookDtoId(stableId) ?: return onDone(false)
        viewModelScope.launch {
            val ok = actionRepository.analyzeBook(bookDtoId)
            if (ok) reloadBooks()
            onDone(ok)
        }
    }

    /** Refreshes the book metadata (thin wrapper; on success the book list is reloaded). */
    fun refreshBookMetadata(stableId: Int, onDone: (Boolean) -> Unit) {
        val bookDtoId = detailRepository.bookDtoId(stableId) ?: return onDone(false)
        viewModelScope.launch {
            val ok = actionRepository.refreshBookMetadata(bookDtoId)
            if (ok) reloadBooks()
            onDone(ok)
        }
    }

    /** Deletes the book files (Komga book deletion = DELETE books/{id}/file; on success the book list is reloaded). */
    fun deleteBook(stableId: Int, onDone: (Boolean) -> Unit) {
        val bookDtoId = detailRepository.bookDtoId(stableId) ?: return onDone(false)
        viewModelScope.launch {
            // Purge this book's cached pages while its page list still exists (see ImageCacheStore.clearBookPages).
            imageCacheStore.clearBookPages(bookDtoId)
            val ok = actionRepository.deleteBook(bookDtoId)
            if (ok) {
                catalogCache.deleteBook(bookDtoId)
                reloadBooks()
            }
            onDone(ok)
        }
    }

    /** Web share link of a book ({baseUrl}/book/{dtoId}). */
    fun bookWebUrl(stableId: Int): String? {
        val bookDtoId = detailRepository.bookDtoId(stableId) ?: return null
        val clients = sessionManager.clients.value ?: return null
        return "${clients.baseUrl}/book/$bookDtoId"
    }

    /** Creates a reading list (the "new…" flow of the "add to list" picker); returns the new entry on success. */
    fun createReadListEntry(name: String, summary: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) {
        viewModelScope.launch { onDone(newReadListEntry(listRepository, name, summary, ordered)) }
    }

    /** Creates a collection (the "new…" flow of the "add to collection" picker); returns the new entry on success. */
    fun createCollectionEntry(name: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) {
        viewModelScope.launch { onDone(newCollectionEntry(listRepository, name, ordered)) }
    }
}
