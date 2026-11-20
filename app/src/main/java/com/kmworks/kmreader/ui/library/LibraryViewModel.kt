package com.kmworks.kmreader.ui.library

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.BookRepository
import com.kmworks.kmreader.core.data.CatalogCache
import com.kmworks.kmreader.core.data.ContentChange
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.ContentChangeKind
import com.kmworks.kmreader.core.data.ContentRowRefresher
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ImageCacheStore
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.PatchOutcome
import com.kmworks.kmreader.core.data.SearchRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.observeChanges
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.LibraryMetricValues
import com.kmworks.kmreader.core.server.ServerAdminRepository
import com.kmworks.kmreader.core.data.ThumbnailCache
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import com.kmworks.kmreader.ui.detail.BookMetadataDraft
import com.kmworks.kmreader.ui.detail.toUpdateDto
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Library grid state; series/books == null means no session or load failure. */
data class LibraryUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val series: List<Series>? = null,
    val books: List<Book>? = null,
    val seriesHasMore: Boolean = false,
    val booksHasMore: Boolean = false,
    /** Server-side totals (mirroring the iOS library title counts); null = no paged session data yet. */
    val seriesTotal: Long? = null,
    val booksTotal: Long? = null,
    val hasMore: Boolean = false,
    val libraries: List<LibraryDto> = emptyList(),
    val libraryCount: Int = 0,
    /** Volume/count metrics keyed by library id (empty = no metric source answered). */
    val metricsByLibrary: Map<String, LibraryMetricValues> = emptyMap(),
    /** Server-wide metric totals, used by the all/pinned scopes. */
    val metricsTotal: LibraryMetricValues? = null,
    /** LibraryBrowseScope rawValue: pinned / all / library:{id}. */
    val scope: String = AppSettingsStore.SCOPE_PINNED,
    val pinnedIds: Set<String> = emptySet(),
    val browseOptions: BookBrowseOptions = BookBrowseOptions(),
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
)

/** Real library content (scope + filters drive paged POST list queries). */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val libraryRepository: LibraryRepository,
    private val searchRepository: SearchRepository,
    private val actionRepository: ActionRepository,
    private val contentChangeBus: ContentChangeBus,
    private val listRepository: ListRepository,
    private val detailRepository: DetailRepository,
    private val contentRowRefresher: ContentRowRefresher,
    private val appSettings: AppSettingsStore,
    private val sessionManager: SessionManager,
    private val downloadRepository: DownloadRepository,
    private val thumbnailCache: ThumbnailCache,
    private val imageCacheStore: ImageCacheStore,
    private val catalogCache: CatalogCache,
    private val serverAdminRepository: ServerAdminRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state = _state.asStateFlow()

    private val browseOptionsFlow = MutableStateFlow(BookBrowseOptions())

    /** Snapshot of the current query; paging reuses the same conditions. A query change bumps
     *  generation so late results are discarded. */
    private data class Query(
        val scope: String,
        val pinned: Set<String>,
        val options: BookBrowseOptions,
    )

    private var query: Query? = null
    private var generation = 0
    private var seriesPage = 0
    private var booksPage = 0
    private var loadingMoreSeries = false
    private var loadingMoreBooks = false

    /** [SystemClock.elapsedRealtime] of the last load start, used to skip a redundant settle re-read. */
    private var lastLoadAtMs = 0L

    init {
        // Re-query whenever session × scope × pins × filters change (mirrors the original where
        // scope/filters drive the query directly)
        viewModelScope.launch {
            combine(
                sessionManager.clients,
                appSettings.libraryBrowseScope,
                appSettings.pinnedLibraryIds,
                browseOptionsFlow,
            ) { clients, scope, pinned, options -> ScopeQuery(clients != null, scope, pinned, options) }
                .collectLatest { snapshot ->
                    if (!snapshot.hasSession) {
                        generation++
                        query = null
                        _state.update {
                            it.copy(
                                hasSession = false,
                                loading = false,
                                loadingMore = false,
                                series = null,
                                books = null,
                                libraries = emptyList(),
                                scope = snapshot.scope,
                                pinnedIds = snapshot.pinned,
                                browseOptions = snapshot.options,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                scope = snapshot.scope,
                                pinnedIds = snapshot.pinned,
                                browseOptions = snapshot.options,
                            )
                        }
                        load(snapshot.scope, snapshot.pinned, snapshot.options)
                    }
                }
        }
        // Writes made here or on another screen patch the rows on screen by id; whatever this screen
        // cannot express (created/deleted rows, metadata fields) falls back to one debounced re-query. A patched
        // book still leaves its series card's aggregate stale, so those changes schedule one quiet re-read later.
        viewModelScope.launch {
            contentChangeBus.observeChanges(
                patch = { patchContentChange(it) },
                reload = { applyReload(it) },
                settle = { settleRefresh() },
            )
        }
    }

    /**
     * Re-fetch the list content (content sync after a successful action, an SSE event or a
     * pull-to-refresh). The returned job completes when the reload landed, so a caller can keep its
     * refresh indicator up meanwhile while the work itself stays in this view model's scope.
     */
    fun refresh(): Job {
        return viewModelScope.launch {
            val current = query
            if (_state.value.hasSession && current != null) load(current.scope, current.pinned, current.options)
        }
    }

    /**
     * Re-reads the first page and merges it into the rows already on screen, for the fields a patch leaves stale:
     * a series card's progress and unread count are aggregates the server recomputes out of its books, so a book
     * change cannot produce them locally.
     *
     * Merging by id instead of reloading keeps what a reload would destroy: the loaded pages of the session, the
     * scroll position and any row the server no longer returns on page one.
     *
     * Skipped when a load just ran. SSE already re-reads this screen on its own 3s debounce, and a pull-to-refresh
     * may have just landed; reading again would be the same request twice. Skipping cannot lose the correction:
     * a load that recent has already read the post-write numbers.
     */
    private fun settleRefresh() {
        if (SystemClock.elapsedRealtime() - lastLoadAtMs < SETTLE_SUPPRESS_MILLIS) return
        val current = query ?: return
        if (!_state.value.hasSession || _state.value.series == null) return
        viewModelScope.launch {
            val fresh = runCatching {
                searchRepository.browseSeriesPage(
                    libraryIds = resolveLibraryIds(current.scope, current.pinned),
                    options = current.options,
                    page = 0,
                    size = PAGE_SIZE,
                )
            }.getOrNull() ?: return@launch
            val byId = fresh.items.associateBy { it.id }
            lastLoadAtMs = SystemClock.elapsedRealtime()
            _state.update { st ->
                st.copy(series = st.series?.map { row -> byId[row.id] ?: row })
            }
            runCatching { catalogCache.saveSeries(listKey(current.scope, current.options), fresh.items) }
        }
    }

    /**
     * The path for a change this screen could not apply itself. A [ContentChangeKind.Touched] change (the server
     * announced an id but sent no values) is turned into fresh rows, one request per row on screen.
     */
    private suspend fun applyReload(change: ContentChange) {
        if (change.kind == ContentChangeKind.Touched) {
            applyTouched(change)
            return
        }
        refresh()
    }

    /** Fetch the touched rows and swap them in; ids this screen does not show cost nothing. */
    private suspend fun applyTouched(change: ContentChange) {
        val ids = detailRepository.stableIdsOf(change)
        if (ids.isEmpty() || !showsAny(ids)) return
        when (change) {
            is ContentChange.Series -> {
                val rows = contentRowRefresher.series(change.dtoIds)
                if (rows.isEmpty()) return
                _state.update { st ->
                    st.copy(series = st.series?.map { row -> rows[row.id] ?: row })
                }
                // The fresh row carries the server's aggregates, so nothing is left stale here.
            }

            is ContentChange.Books -> {
                val rows = contentRowRefresher.books(change.dtoIds)
                if (rows.isEmpty()) return
                _state.update { st ->
                    st.copy(books = st.books?.map { row -> rows[row.id] ?: row })
                }
            }

            else -> Unit
        }
    }

    private fun showsAny(ids: Set<Int>): Boolean =
        _state.value.series?.any { it.id in ids } == true || _state.value.books?.any { it.id in ids } == true

    /**
     * Apply a write to the rows on screen without asking the server. Read state and the progress a reader flushed
     * on its way out are the only things these lists show that a write flips in place; anything else (a created or
     * deleted row, a renamed or remetadated entity) declines and lets the caller re-query.
     *
     * A book's own row is patchable, but a series card's progress and unread count are aggregates over all its
     * books: those changes report [PatchOutcome.PendingSettle] so the screen re-reads them once the write settled.
     */
    private fun patchContentChange(change: ContentChange): PatchOutcome = when (change.kind) {
        // Ids without values: the reload path fetches these rows and patches them.
        ContentChangeKind.Touched -> PatchOutcome.Declined
        ContentChangeKind.ReadStateChanged -> {
            val read = change.read ?: return PatchOutcome.Patched
            val ids = detailRepository.stableIdsOf(change)
            if (ids.isEmpty()) return PatchOutcome.Patched
            when (change) {
                is ContentChange.Series -> patchSeriesRead(ids, read)
                is ContentChange.Books -> patchBooksRead(ids, read)
                else -> PatchOutcome.Patched
            }
        }
        // The series cards aggregate their books, so a single book's page cannot be patched into them.
        ContentChangeKind.ProgressChanged -> {
            val progress = change.progress ?: return PatchOutcome.Patched
            val ids = detailRepository.stableIdsOf(change)
            if (change is ContentChange.Books && ids.isNotEmpty()) patchBooksProgress(ids, progress)
            settleOrPatched()
        }
        else -> PatchOutcome.Declined
    }

    /** A patched book row is only half the screen while the series cards above it are stale. */
    private fun settleOrPatched(): PatchOutcome =
        if (_state.value.series != null) PatchOutcome.PendingSettle else PatchOutcome.Patched

    private fun patchSeriesRead(ids: Set<Int>, read: Boolean): PatchOutcome {
        if (_state.value.series?.any { it.id in ids } != true) return PatchOutcome.Patched
        _state.update { st ->
            st.copy(
                series = st.series?.map {
                    if (it.id in ids) {
                        it.copy(
                            progress = if (read) 1f else null,
                            unreadCount = if (read) 0 else it.bookCount,
                        )
                    } else {
                        it
                    }
                },
            )
        }
        return PatchOutcome.Patched
    }

    private fun patchBooksRead(ids: Set<Int>, read: Boolean): PatchOutcome {
        if (_state.value.books?.any { it.id in ids } != true) return settleOrPatched()
        _state.update { st ->
            st.copy(books = st.books?.map {
                if (it.id in ids) it.copy(progress = if (read) 1f else null) else it
            })
        }
        return settleOrPatched()
    }

    private fun patchBooksProgress(ids: Set<Int>, progress: Float): PatchOutcome {
        if (_state.value.books?.any { it.id in ids } != true) return settleOrPatched()
        _state.update { st ->
            st.copy(books = st.books?.map {
                if (it.id in ids) it.copy(progress = progress) else it
            })
        }
        return settleOrPatched()
    }

    /** Switch the library scope (pinned/all/library:{id}); the flow re-queries after persistence. */
    fun setScope(scope: String) {
        viewModelScope.launch { appSettings.setLibraryBrowseScope(scope) }
    }

    /**
     * Per-library size/count metrics for the scope caption (mirrors iOS LibraryMetricsLoader:
     * the kmrs stats endpoint first, then the stock-Komga actuator tags). The repository caches
     * the snapshot, so repeated reloads do not hammer the meter endpoints.
     */
    private suspend fun loadLibraryMetrics() {
        val metrics = runCatching { serverAdminRepository.libraryMetrics() }.getOrNull() ?: return
        _state.update {
            it.copy(metricsByLibrary = metrics.byLibrary, metricsTotal = metrics.total)
        }
    }

    /** Replace the pinned set (saved from the pin-editing sheet). */
    fun setPinnedLibraries(ids: Set<String>) {
        viewModelScope.launch { appSettings.setPinnedLibraryIds(ids) }
    }

    /** Update filter/sort options (BrowseOptionsSheet edits apply to the real query). */
    fun setBrowseOptions(options: BookBrowseOptions) {
        browseOptionsFlow.value = options
    }

    /** Condition injected by the last metadata-chip jump; null = the list is not filtered by a chip. */
    private var appliedMetadataRoute: AppliedMetadataRoute? = null

    /**
     * Replaces the metadata-chip condition of the list with the one encoded in [route] (mirrors the iOS
     * metadataFilter: switching chips replaces the previous condition instead of accumulating).
     *
     * The injected condition is tracked here rather than in the screen, so that it stays revocable after the
     * screen left composition (back to the origin page, a tab switch): a remember-based record would be lost
     * there, leaving the filter applied with nothing left to undo it.
     */
    fun applyMetadataRoute(route: String) {
        var base = browseOptionsFlow.value
        appliedMetadataRoute?.let { previous ->
            base = revokeFilterRoute(base, previous)
            appliedMetadataRoute = null
        }
        val (next, applied) = applyFilterRoute(base, route) ?: return
        appliedMetadataRoute = applied
        if (next != base) browseOptionsFlow.value = next
    }

    /** Drops the metadata-chip condition again: the chip's clear button, or back to the page it came from. */
    fun clearMetadataRoute() {
        val applied = appliedMetadataRoute ?: return
        appliedMetadataRoute = null
        browseOptionsFlow.value = revokeFilterRoute(browseOptionsFlow.value, applied)
    }

    /** Resolve the scope to library ids (mirrors iOS resolvedIds: all → no filter, empty pinned → no filter). */
    private fun resolveLibraryIds(scope: String, pinned: Set<String>): List<String>? =
        resolveLibraryScopeIds(scope, pinned)

    /** Cache key of the current browse query (one row set per scope + filter combination). */
    private fun listKey(scope: String, options: BookBrowseOptions): String =
        "library:$scope:${options.hashCode()}"

    private suspend fun load(scope: String, pinned: Set<String>, options: BookBrowseOptions) {
        val nextQuery = Query(scope, pinned, options)
        val currentGeneration = ++generation
        query = nextQuery
        seriesPage = 0
        booksPage = 0
        loadingMoreSeries = false
        loadingMoreBooks = false
        lastLoadAtMs = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(hasSession = true, loading = true, loadingMore = false, error = null)
        }
        // Data-cache parity: paint the cached first page for the same query before the network answers.
        // Only seeds an empty screen; the response below replaces it.
        if (_state.value.series == null && _state.value.books == null) {
            val key = listKey(scope, options)
            val cachedSeries = runCatching { catalogCache.seedSeries(key) }.getOrDefault(emptyList())
            val cachedBooks = runCatching { catalogCache.seedBooks(key) }.getOrDefault(emptyList())
            if (cachedSeries.isNotEmpty() || cachedBooks.isNotEmpty()) {
                _state.update {
                    it.copy(
                        series = cachedSeries.takeIf { cachedSeries.isNotEmpty() },
                        books = cachedBooks.takeIf { cachedBooks.isNotEmpty() },
                    )
                }
            }
        }
        try {
            val libraryIds = resolveLibraryIds(scope, pinned)
            val libraries = libraryRepository.libraries()
            val (seriesPageResult, bookPageResult) = coroutineScope {
                val series = async {
                    searchRepository.browseSeriesPage(libraryIds, options, page = 0, size = PAGE_SIZE)
                        ?: throw IllegalStateException(context.getString(R.string.library_series_load_failed))
                }
                val books = async {
                    searchRepository.browseBooksPage(libraryIds, options, page = 0, size = PAGE_SIZE)
                        ?: throw IllegalStateException(context.getString(R.string.library_books_load_failed))
                }
                series.await() to books.await()
            }
            if (currentGeneration != generation) return
            val (collectionDtos, readListDtos) = coroutineScope {
                val c = async { listRepository.collections() }
                val r = async { listRepository.readLists() }
                c.await() to r.await()
            }
            val collections = collectionDtos.map { dto ->
                ListEntry(StableIds.collection(dto.id), dto.name, dto.seriesIds.size, hueFor(dto.id), false)
            }
            val readLists = readListDtos.map { dto ->
                ListEntry(StableIds.readList(dto.id), dto.name, dto.bookIds.size, hueFor(dto.id), false)
            }
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    series = seriesPageResult.items,
                    books = bookPageResult.items,
                    seriesHasMore = seriesPageResult.hasMore,
                    booksHasMore = bookPageResult.hasMore,
                    seriesTotal = seriesPageResult.totalElements,
                    booksTotal = bookPageResult.totalElements,
                    hasMore = seriesPageResult.hasMore,
                    libraries = libraries,
                    libraryCount = libraries.size,
                    collections = collections,
                    readLists = readLists,
                )
            }
            // Persist the first page for the same query (ids + card rows), plus the Lists rows.
            val key = listKey(scope, options)
            runCatching {
                catalogCache.saveSeries(key, seriesPageResult.items)
                catalogCache.saveBooks(key, bookPageResult.items)
                catalogCache.saveLists(collections, readLists)
            }
            // Volume/count caption for the scope (stats → actuator fallback; empty hides the line).
            viewModelScope.launch { loadLibraryMetrics() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentGeneration != generation) return
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = e.message ?: context.getString(R.string.common_load_failed),
                    series = null,
                    books = null,
                )
            }
        }
    }

    /** Load the next series page at the bottom (ignored while loading, concurrently, or when no more). */
    fun loadMoreSeries() {
        val current = query ?: return
        val snapshot = _state.value
        if (loadingMoreSeries || snapshot.loading || !snapshot.seriesHasMore) return
        loadingMoreSeries = true
        val currentGeneration = generation
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val nextPage = seriesPage + 1
                val result = searchRepository.browseSeriesPage(
                    libraryIds = resolveLibraryIds(current.scope, current.pinned),
                    options = current.options,
                    page = nextPage,
                    size = PAGE_SIZE,
                )
                if (currentGeneration != generation) return@launch
                if (result == null) {
                    _state.update { it.copy(loadingMore = false, seriesHasMore = false) }
                    return@launch
                }
                seriesPage = nextPage
                _state.update { st ->
                    val known = st.series?.mapTo(HashSet()) { item -> item.id } ?: HashSet()
                    val appended = result.items.filter { known.add(it.id) }
                    st.copy(
                        series = st.series.orEmpty() + appended,
                        seriesHasMore = result.hasMore,
                        seriesTotal = result.totalElements ?: st.seriesTotal,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentGeneration != generation) return@launch
                _state.update { it.copy(loadingMore = false, error = e.message ?: context.getString(R.string.common_load_more_failed)) }
            } finally {
                loadingMoreSeries = false
            }
        }
    }

    /** Load the next book page at the bottom. */
    fun loadMoreBooks() {
        val current = query ?: return
        val snapshot = _state.value
        if (loadingMoreBooks || snapshot.loading || !snapshot.booksHasMore) return
        loadingMoreBooks = true
        val currentGeneration = generation
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val nextPage = booksPage + 1
                val result = searchRepository.browseBooksPage(
                    libraryIds = resolveLibraryIds(current.scope, current.pinned),
                    options = current.options,
                    page = nextPage,
                    size = PAGE_SIZE,
                )
                if (currentGeneration != generation) return@launch
                if (result == null) {
                    _state.update { it.copy(loadingMore = false, booksHasMore = false) }
                    return@launch
                }
                booksPage = nextPage
                _state.update { st ->
                    val known = st.books?.mapTo(HashSet()) { item -> item.id } ?: HashSet()
                    val appended = result.items.filter { known.add(it.id) }
                    st.copy(
                        books = st.books.orEmpty() + appended,
                        booksHasMore = result.hasMore,
                        booksTotal = result.totalElements ?: st.booksTotal,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentGeneration != generation) return@launch
                _state.update { it.copy(loadingMore = false, error = e.message ?: context.getString(R.string.common_load_more_failed)) }
            } finally {
                loadingMoreBooks = false
            }
        }
    }

    /** Mark a series read/unread and update local state immediately; silently ignored without a DTO
     *  mapping (demo data). */
    fun markSeriesRead(series: Series, read: Boolean) {
        val dtoId = detailRepository.seriesDtoId(series.id) ?: return
        viewModelScope.launch {
            val ok = if (read) actionRepository.markSeriesRead(dtoId)
            else actionRepository.markSeriesUnread(dtoId)
            if (ok) {
                _state.update { st ->
                    st.copy(series = st.series?.map {
                        if (it.id == series.id) {
                            it.copy(
                                progress = if (read) 1f else null,
                                unreadCount = if (read) 0 else it.bookCount,
                            )
                        } else {
                            it
                        }
                    })
                }
            }
        }
    }

    /** Mark a book read/unread and update local state immediately; silently ignored without a DTO mapping. */
    fun markBookRead(book: Book, read: Boolean) {
        val dtoId = detailRepository.bookDtoId(book.id) ?: return
        viewModelScope.launch {
            val ok = if (read) actionRepository.markBookRead(dtoId)
            else actionRepository.markBookUnread(dtoId)
            if (ok) {
                _state.update { st ->
                    st.copy(books = st.books?.map {
                        if (it.id == book.id) it.copy(progress = if (read) 1f else null) else it
                    })
                }
            }
        }
    }

    /** Download the whole book (silently ignored without a DTO mapping). */
    fun downloadBook(stableId: Int, title: String, pageCount: Int) {
        val dtoId = detailRepository.bookDtoId(stableId) ?: return
        downloadRepository.enqueueBook(dtoId, title, pageCount)
    }

    /** Download every book of the series (enqueued one by one). */
    fun downloadSeries(stableId: Int) {
        val dtoId = detailRepository.seriesDtoId(stableId) ?: return
        viewModelScope.launch {
            detailRepository.seriesBooks(dtoId).forEach { book ->
                downloadRepository.enqueueBook(
                    book.id,
                    book.metadata.title.ifBlank { book.name },
                    book.media.pagesCount,
                    libraryId = book.libraryId,
                )
            }
        }
    }

    /** Save the series metadata edit; dto == null means no change, an immediate success. */
    fun saveSeriesMetadata(stableId: Int, dto: SeriesMetadataUpdateDto?, onDone: (Boolean) -> Unit) {
        if (dto == null) {
            onDone(true)
            return
        }
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.updateSeriesMetadata(dtoId, dto)
            onDone(ok)
        }
    }

    /** Save the book metadata edit (only changed fields are submitted); an unchanged draft succeeds
     *  immediately. */
    fun saveBookMetadata(stableId: Int, baseTitle: String, draft: BookMetadataDraft, onDone: (Boolean) -> Unit) {
        val body = draft.toUpdateDto(baseTitle, null)
        if (body == null) {
            onDone(true)
            return
        }
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.updateBookMetadata(dtoId, body)
            onDone(ok)
        }
    }

    /** Refresh the cover (clears the cache; the next display re-fetches). */
    fun refreshCover(stableId: Int) {
        thumbnailCache.refresh(stableId)
    }

    /** Re-analyze the book. */
    fun analyzeBook(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch { onDone(actionRepository.analyzeBook(dtoId)) }
    }

    /** Refresh the book metadata; the write signal re-queries the current list (or the row in place). */
    fun refreshBookMetadata(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch { onDone(actionRepository.refreshBookMetadata(dtoId)) }
    }

    /** Delete the series files; the write signal re-queries the current list (or drops the row in place). */
    fun deleteSeries(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.deleteSeries(dtoId)
            if (ok) catalogCache.deleteSeries(dtoId)
            onDone(ok)
        }
    }

    /** Delete the book files; the write signal re-queries the current list (or drops the row in place). */
    fun deleteBook(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            // Purge this book's cached pages while its page list still exists (see ImageCacheStore.clearBookPages).
            imageCacheStore.clearBookPages(dtoId)
            val ok = actionRepository.deleteBook(dtoId)
            if (ok) catalogCache.deleteBook(dtoId)
            onDone(ok)
        }
    }

    /** Komga web URL of the series (for sharing). */
    fun seriesWebUrl(stableId: Int): String? {
        val clients = sessionManager.clients.value ?: return null
        val dtoId = detailRepository.seriesDtoId(stableId) ?: return null
        return "${clients.baseUrl}/series/$dtoId"
    }

    /** Komga web URL of the book (for sharing). */
    fun bookWebUrl(stableId: Int): String? {
        val clients = sessionManager.clients.value ?: return null
        val dtoId = detailRepository.bookDtoId(stableId) ?: return null
        return "${clients.baseUrl}/book/$dtoId"
    }

    /** Add the series to a collection. */
    fun addSeriesToCollection(entry: ListEntry, series: Series) {
        val seriesDtoId = detailRepository.seriesDtoId(series.id) ?: return
        val collectionDtoId = detailRepository.collectionDtoId(entry.id) ?: return
        viewModelScope.launch {
            actionRepository.addSeriesToCollection(collectionDtoId, seriesDtoId)
        }
    }

    /** Add every book of the series to a read list (Komga read lists accept books only). */
    fun addSeriesToReadList(entry: ListEntry, series: Series) {
        val seriesDtoId = detailRepository.seriesDtoId(series.id) ?: return
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return
        viewModelScope.launch {
            detailRepository.seriesBooks(seriesDtoId).forEach { book ->
                actionRepository.addBookToReadList(readListDtoId, book.id)
            }
        }
    }

    /** Add the book to a read list. */
    fun addBookToReadList(entry: ListEntry, book: Book) {
        val bookDtoId = detailRepository.bookDtoId(book.id) ?: return
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return
        viewModelScope.launch {
            actionRepository.addBookToReadList(readListDtoId, bookDtoId)
        }
    }

    /** Query snapshot (session + scope + pins + filters). */
    private data class ScopeQuery(
        val hasSession: Boolean,
        val scope: String,
        val pinned: Set<String>,
        val options: BookBrowseOptions,
    )

    /** Create a read list (the "New…" flow of the "Add to List" picker); returns the new entry on
     *  success. */
    fun createReadListEntry(name: String, summary: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) {
        viewModelScope.launch { onDone(newReadListEntry(listRepository, name, summary, ordered)) }
    }

    /** Create a collection (the "New…" flow of the "Add to Collection" picker); returns the new entry
     *  on success. */
    fun createCollectionEntry(name: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) {
        viewModelScope.launch { onDone(newCollectionEntry(listRepository, name, ordered)) }
    }

    private companion object {
        /** Page size, mirroring pageSize = 50 of the original SeriesViewModel/BookViewModel. */
        const val PAGE_SIZE = 50

        /**
         * A settle re-read within this window of the last load is redundant: that load already read the state the
         * settle was waiting for.
         */
        const val SETTLE_SUPPRESS_MILLIS = 3_000L
    }
}
