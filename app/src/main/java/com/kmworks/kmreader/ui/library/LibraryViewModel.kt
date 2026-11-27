package com.kmworks.kmreader.ui.library

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.ui.lists.ListSelection
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.BookRepository
import com.kmworks.kmreader.core.data.CatalogCache
import com.kmworks.kmreader.core.data.ContentChange
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.PagedFeed
import com.kmworks.kmreader.core.data.ContentChangeKind
import com.kmworks.kmreader.core.data.ContentRowRefresher
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ImageCacheStore
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.PatchOutcome
import com.kmworks.kmreader.core.data.SearchRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.core.data.observeChanges
import com.kmworks.kmreader.core.network.ServerReachability
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerAdminRepository
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.core.data.ThumbnailCache
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.core.data.isOutsideLibraryScope
import com.kmworks.kmreader.data.BrowseContentType
import com.kmworks.kmreader.data.BrowseOptions
import com.kmworks.kmreader.data.isSensitiveToReadingProgress
import com.kmworks.kmreader.data.defaultBrowseOptions
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import com.kmworks.kmreader.ui.detail.BookMetadataDraft
import com.kmworks.kmreader.ui.detail.SeriesEditValues
import com.kmworks.kmreader.ui.detail.toBookDetailMeta
import com.kmworks.kmreader.ui.detail.toEditValues
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
    /** LibraryBrowseScope rawValue: pinned / all / library:{id}. A server without a recorded choice shows all. */
    val scope: String = AppSettingsStore.SCOPE_ALL,
    val pinnedIds: Set<String> = emptySet(),
    /** Series tab filter/sort state: the series panel's own options (mirrors SeriesBrowseOptions). */
    val seriesBrowseOptions: BrowseOptions = defaultBrowseOptions(BrowseContentType.Series),
    /** Books tab filter/sort state: the book panel's own options (mirrors BookBrowseOptions). */
    val booksBrowseOptions: BrowseOptions = defaultBrowseOptions(BrowseContentType.Books),
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
    /** Whether the current account is an admin (gates the analyze/refresh-metadata/add-to-list menu items). */
    val isAdmin: Boolean = false,
    /** kmf capability: admin + server feature; when false the three komf menu items are not rendered. */
    val komfAvailable: Boolean = false,
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
    private val serverProfileStore: ServerProfileStore,
    private val serverReachability: ServerReachability,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state = _state.asStateFlow()

    // The two tabs filter independently (the original keeps separate SeriesBrowseOptions and
    // BookBrowseOptions), so each tab owns one flow; either change re-queries both lists.
    private val seriesBrowseOptionsFlow =
        MutableStateFlow(defaultBrowseOptions(BrowseContentType.Series))
    private val booksBrowseOptionsFlow =
        MutableStateFlow(defaultBrowseOptions(BrowseContentType.Books))

    /** Snapshot of the current query; paging reuses the same conditions. A query change bumps
     *  generation so late results are discarded. */
    private data class Query(
        val scope: String,
        val pinned: Set<String>,
        val seriesOptions: BrowseOptions,
        val booksOptions: BrowseOptions,
    )

    private var query: Query? = null
    private var generation = 0
    /**
     * List feeds. They read [query] at call time, so a reload of the same scope/sort re-reads the pages the user
     * already scrolled through instead of shrinking the list back to page one (see [PagedFeed]).
     */
    private val seriesFeed = PagedFeed(
        pageSize = PAGE_SIZE,
        identity = { series: Series -> series.id },
        fetch = { page, size ->
            query?.let { current ->
                searchRepository.browseSeriesPage(
                    libraryIds = resolveLibraryIds(current.scope, current.pinned),
                    options = current.seriesOptions,
                    page = page,
                    size = size,
                ) ?: throw IllegalStateException(context.getString(R.string.library_series_load_failed))
            }
        },
    )
    private val booksFeed = PagedFeed(
        pageSize = PAGE_SIZE,
        identity = { book: Book -> book.id },
        fetch = { page, size ->
            query?.let { current ->
                searchRepository.browseBooksPage(
                    libraryIds = resolveLibraryIds(current.scope, current.pinned),
                    options = current.booksOptions,
                    page = page,
                    size = size,
                ) ?: throw IllegalStateException(context.getString(R.string.library_books_load_failed))
            }
        },
    )
    private var loadingMoreSeries = false
    private var loadingMoreBooks = false

    /** [SystemClock.elapsedRealtime] of the last load start, used to skip a redundant settle re-read. */
    private var lastLoadAtMs = 0L

    /** Instance key the in-memory filter state belongs to (the filters are dropped when the server changes). */
    private var filterInstanceKey: String? = null

    init {
        viewModelScope.launch {
            // Back online after an outage (probe, SSE reconnect or any request): reload the cached screen,
            // mirroring the iOS recovery refresh. drop(1) skips the initial `false` (StateFlow conflates repeats).
            serverReachability.offline
                .drop(1)
                .filter { !it }
                .collect { refresh() }
        }
        viewModelScope.launch {
            // Follow the active session: the admin role gates the card menus, and a server switch must
            // re-evaluate them instead of keeping the previous server's answer.
            combine(
                serverAdminRepository.adminStatus,
                serverProfileStore.profile,
            ) { isAdmin, profile ->
                isAdmin to (isAdmin && profile?.features?.contains(ServerFeature.Komf) == true)
            }.collect { (isAdmin, komfAvailable) ->
                _state.update { it.copy(isAdmin = isAdmin, komfAvailable = komfAvailable) }
            }
        }
        // Re-query whenever session × scope × pins × filters change (mirrors the original where
        // scope/filters drive the query directly)
        viewModelScope.launch {
            combine(
                sessionManager.clients,
                appSettings.libraryBrowseScope,
                appSettings.pinnedLibraryIds,
                seriesBrowseOptionsFlow,
                booksBrowseOptionsFlow,
            ) { clients, scope, pinned, seriesOptions, booksOptions ->
                ScopeQuery(
                    clients?.cacheNamespace,
                    clients != null,
                    scope,
                    pinned,
                    seriesOptions,
                    booksOptions,
                )
            }
                .collectLatest { snapshot ->
                    // The scope and the pins are stored per server, but the filter is session state: drop it on a
                    // server switch. Its conditions (authors, tags, library ids) belong to the previous server, so
                    // carrying it over would silently narrow the new server's list to rows it cannot match.
                    val instanceChanged = snapshot.instanceKey != filterInstanceKey
                    if (instanceChanged) {
                        filterInstanceKey = snapshot.instanceKey
                        appliedMetadataRoute = null
                        val seriesDefault = defaultBrowseOptions(BrowseContentType.Series)
                        val booksDefault = defaultBrowseOptions(BrowseContentType.Books)
                        if (seriesBrowseOptionsFlow.value != seriesDefault ||
                            booksBrowseOptionsFlow.value != booksDefault
                        ) {
                            // Clearing re-emits this query; let that pass do the work instead of loading filters
                            // of the server we just left. Both tabs are cleared: each carries its own panel state.
                            seriesBrowseOptionsFlow.value = seriesDefault
                            booksBrowseOptionsFlow.value = booksDefault
                            return@collectLatest
                        }
                    }
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
                                seriesBrowseOptions = snapshot.seriesOptions,
                                booksBrowseOptions = snapshot.booksOptions,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                scope = snapshot.scope,
                                pinnedIds = snapshot.pinned,
                                seriesBrowseOptions = snapshot.seriesOptions,
                                booksBrowseOptions = snapshot.booksOptions,
                            )
                        }
                        load(snapshot.scope, snapshot.pinned, snapshot.seriesOptions, snapshot.booksOptions)
                    }
                }
        }
        // Writes made here or on another screen patch the rows on screen by id; whatever this screen
        // cannot express (created/deleted rows, metadata fields) falls back to one debounced re-query. A patched
        // book still leaves its series card's aggregate stale, so those changes schedule one quiet re-read later.
        viewModelScope.launch {
            contentChangeBus.observeChanges(
                patch = {
                    try {
                        rowsMutex.withLock { patchContentChange(it) }
                    } finally {
                        drainPendingLoadMore()
                    }
                },
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
            if (_state.value.hasSession && current != null) load(current.scope, current.pinned, current.seriesOptions, current.booksOptions)
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
        val currentGeneration = generation
        viewModelScope.launch {
            try {
                rowsMutex.withLock {
            // The same window a normal reload reads: every page already on screen comes back in one request and
            // is swapped in place, so the settle pass corrects the aggregates without shrinking the list or
            // dropping the user back to the top. The rows are read under this lock, so a patch that landed a
            // moment ago is part of the snapshot instead of being overwritten by it.
            val snapshot = _state.value
            if (!snapshot.hasSession || snapshot.series == null) return@withLock
            val rows = runCatching { seriesFeed.reload(snapshot.series, seriesFeed.page) }.getOrNull()
                ?: return@withLock
            if (currentGeneration != generation) return@withLock
            lastLoadAtMs = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(series = rows, seriesHasMore = seriesFeed.hasMore, seriesTotal = seriesFeed.total)
            }
            // Books reorder on read state only when the list is sensitive to it; otherwise their rows are
            // already patched and a re-read would be a request for nothing (iOS gates the same way).
            if (snapshot.books != null &&
                current.booksOptions.isSensitiveToReadingProgress(BrowseContentType.Books)
            ) {
                val bookRows = runCatching { booksFeed.reload(snapshot.books, booksFeed.page) }.getOrNull()
                if (bookRows != null && currentGeneration == generation) {
                    _state.update {
                        it.copy(
                            books = bookRows,
                            booksHasMore = booksFeed.hasMore,
                            booksTotal = booksFeed.total,
                        )
                    }
                }
            }
            runCatching {
                catalogCache.saveSeries(listKey(current.scope, current.seriesOptions), rows.take(PAGE_SIZE))
            }
            }
            } finally {
                drainPendingLoadMore()
            }
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
        try {
            rowsMutex.withLock {
        val ids = detailRepository.stableIdsOf(change)
        if (ids.isEmpty() || !showsAny(ids)) return@withLock
        when (change) {
            is ContentChange.Series -> {
                val rows = contentRowRefresher.series(change.dtoIds)
                if (rows.isEmpty()) return@withLock
                _state.update { st ->
                    st.copy(series = st.series?.map { row -> rows[row.id] ?: row })
                }
                // The fresh row carries the server's aggregates, so nothing is left stale here.
            }

            is ContentChange.Books -> {
                val rows = contentRowRefresher.books(change.dtoIds)
                if (rows.isEmpty()) return@withLock
                _state.update { st ->
                    st.copy(books = st.books?.map { row -> rows[row.id] ?: row })
                }
            }

            else -> Unit
        }
            }
        } finally {
            drainPendingLoadMore()
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
    private fun patchContentChange(change: ContentChange): PatchOutcome {
        // A change the source pinned to another library cannot touch what this scope shows; a change without
        // a library id (local writes) always passes.
        val current = query ?: return PatchOutcome.Patched
        if (change.isOutsideLibraryScope(resolveLibraryIds(current.scope, current.pinned))) {
            return PatchOutcome.Patched
        }
        return when (change.kind) {
            // Ids without values: the reload path fetches these rows and patches them.
            ContentChangeKind.Touched -> PatchOutcome.Declined
            ContentChangeKind.ReadStateChanged -> {
                val read = change.read ?: return PatchOutcome.Patched
                val ids = detailRepository.stableIdsOf(change)
                if (ids.isEmpty()) return PatchOutcome.Patched
                val outcome = when (change) {
                    is ContentChange.Series -> patchSeriesRead(ids, read)
                    is ContentChange.Books -> patchBooksRead(ids, read)
                    else -> PatchOutcome.Patched
                }
                // Under a read-status filter the new state can move the row out of the list (or into it).
                if (outcome == PatchOutcome.Patched && readStatusFiltered(change)) settleOrPatched() else outcome
            }
            // The series cards aggregate their books, so a single book's page cannot be patched into them.
            ContentChangeKind.ProgressChanged -> {
                val progress = change.progress ?: return PatchOutcome.Patched
                val ids = detailRepository.stableIdsOf(change)
                if (change is ContentChange.Books && ids.isNotEmpty()) patchBooksProgress(ids, progress)
                // A progress change reorders rows only when the list sorts on it, but it always makes a series
                // card on screen stale: its progress and unread count are aggregates this screen cannot compute
                // (iOS can leave them to its local rows, so there the sort alone decides).
                val seriesOnScreen = _state.value.series != null
                if (progressSensitive(change) || seriesOnScreen) settleOrPatched() else PatchOutcome.Patched
            }
            else -> PatchOutcome.Declined
        }
    }

    /** Whether this list's sort or filters make it sensitive to a pure reading-progress change. */
    private fun progressSensitive(change: ContentChange): Boolean {
        val current = query ?: return true
        return when (change) {
            is ContentChange.Books -> current.booksOptions.isSensitiveToReadingProgress(BrowseContentType.Books)
            is ContentChange.Series -> current.seriesOptions.isSensitiveToReadingProgress(BrowseContentType.Series)
            else -> true
        }
    }

    /** Read-state changes move rows across a read-status filter; without one they stay patched in place. */
    private fun readStatusFiltered(change: ContentChange): Boolean {
        val current = query ?: return false
        return when (change) {
            is ContentChange.Books -> current.booksOptions.readStatuses.isNotEmpty()
            is ContentChange.Series -> current.seriesOptions.readStatuses.isNotEmpty()
            else -> false
        }
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

    /** Replace the pinned set (saved from the pin-editing sheet). */
    fun setPinnedLibraries(ids: Set<String>) {
        viewModelScope.launch { appSettings.setPinnedLibraryIds(ids) }
    }

    /** Update one tab's filter/sort options (the panel for [contentType] applies to the real query). */
    fun setBrowseOptions(contentType: BrowseContentType, options: BrowseOptions) {
        when (contentType) {
            BrowseContentType.Series -> seriesBrowseOptionsFlow.value = options
            BrowseContentType.Books -> booksBrowseOptionsFlow.value = options
        }
    }

    /** Condition injected by the last metadata-chip jump; null = the list is not filtered by a chip. */
    private var appliedMetadataRoute: AppliedMetadataRoute? = null

    /** Tab whose options carry [appliedMetadataRoute]: the injected condition must be revoked from it. */
    private var appliedMetadataContentType: BrowseContentType? = null

    /** Flow backing one tab's panel state (each tab keeps its own options). */
    private fun browseOptionsFlowFor(contentType: BrowseContentType): MutableStateFlow<BrowseOptions> =
        when (contentType) {
            BrowseContentType.Series -> seriesBrowseOptionsFlow
            BrowseContentType.Books -> booksBrowseOptionsFlow
        }

    /**
     * Replaces the metadata-chip condition of the list with the one encoded in [route] (mirrors the iOS
     * metadataFilter: switching chips replaces the previous condition instead of accumulating).
     *
     * The injected condition is tracked here rather than in the screen, so that it stays revocable after the
     * screen left composition (back to the origin page, a tab switch): a remember-based record would be lost
     * there, leaving the filter applied with nothing left to undo it.
     */
    fun applyMetadataRoute(contentType: BrowseContentType, route: String) {
        appliedMetadataRoute?.let { previous ->
            // Revoke from the tab that actually owns it, then inject into the requested tab.
            val previousFlow = browseOptionsFlowFor(appliedMetadataContentType ?: contentType)
            previousFlow.value = revokeFilterRoute(previousFlow.value, previous)
            appliedMetadataRoute = null
            appliedMetadataContentType = null
        }
        val flow = browseOptionsFlowFor(contentType)
        val (next, applied) = applyFilterRoute(flow.value, route) ?: return
        appliedMetadataRoute = applied
        appliedMetadataContentType = contentType
        if (next != flow.value) flow.value = next
    }

    /** Drops the metadata-chip condition again: the chip's clear button, or back to the page it came from. */
    fun clearMetadataRoute() {
        val applied = appliedMetadataRoute ?: return
        val flow = appliedMetadataContentType?.let { browseOptionsFlowFor(it) } ?: return
        appliedMetadataRoute = null
        appliedMetadataContentType = null
        flow.value = revokeFilterRoute(flow.value, applied)
    }

    /** Resolve the scope to library ids (mirrors iOS resolvedIds: all → no filter, empty pinned → no filter). */
    private fun resolveLibraryIds(scope: String, pinned: Set<String>): List<String>? =
        resolveLibraryScopeIds(scope, pinned)

    /**
     * Cache key of the current browse query (one row set per scope + filter combination).
     *
     * The options are serialized: [BrowseOptions] carries enums (TriState, ReadStatus, …) whose
     * `hashCode()` is identity-based and changes on every process start, so a data-class hashCode
     * key could never find the rows a previous launch wrote. `String.hashCode()` is specified and
     * stable across processes, which is all this key needs.
     */
    private fun listKey(scope: String, options: BrowseOptions): String =
        "library:$scope:${cacheKeyJson.encodeToString(options).hashCode()}"

    private suspend fun load(
        scope: String,
        pinned: Set<String>,
        seriesOptions: BrowseOptions,
        booksOptions: BrowseOptions,
    ) {
        val nextQuery = Query(scope, pinned, seriesOptions, booksOptions)
        // Same scope/pins/filters: keep the pages already on screen. Anything else is a new query and starts over.
        val sameQuery = query == nextQuery
        val keepSeriesPages = if (sameQuery) seriesFeed.page else 0
        val keepBooksPages = if (sameQuery) booksFeed.page else 0
        val currentGeneration = ++generation
        query = nextQuery
        loadingMoreSeries = false
        loadingMoreBooks = false
        lastLoadAtMs = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(hasSession = true, loading = true, loadingMore = false, error = null)
        }
        // Data-cache parity: paint the cached first page for the same query before the network answers.
        // Only seeds an empty screen; the response below replaces it.
        if (_state.value.series == null && _state.value.books == null) {
            val seriesKey = listKey(scope, seriesOptions)
            val booksKey = listKey(scope, booksOptions)
            val cachedSeries = runCatching { catalogCache.seedSeries(seriesKey) }.getOrDefault(emptyList())
            val cachedBooks = runCatching { catalogCache.seedBooks(booksKey) }.getOrDefault(emptyList())
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
            rowsMutex.withLock {
            val previousSeries = if (sameQuery) _state.value.series.orEmpty() else emptyList()
            val previousBooks = if (sameQuery) _state.value.books.orEmpty() else emptyList()
            val libraries = libraryRepository.libraries()
            val (seriesItems, bookItems) = coroutineScope {
                val series = async { seriesFeed.reload(previousSeries, keepSeriesPages, newQuery = !sameQuery) }
                val books = async { booksFeed.reload(previousBooks, keepBooksPages, newQuery = !sameQuery) }
                series.await() to books.await()
            }
            if (currentGeneration != generation) return
            val (collectionDtos, readListDtos) = coroutineScope {
                val c = async { listRepository.collections() }
                val r = async { listRepository.readLists() }
                c.await() to r.await()
            }
            // The mappers register the stable-id → dto-id mapping saveLists resolves; hand-built entries
            // would silently drop every row once the cache replaces kinds instead of upserting them.
            val collections = collectionDtos.map { it.toListEntry() }
            val readLists = readListDtos.map { it.toListEntry() }
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    series = seriesItems,
                    books = bookItems,
                    seriesHasMore = seriesFeed.hasMore,
                    booksHasMore = booksFeed.hasMore,
                    seriesTotal = seriesFeed.total,
                    booksTotal = booksFeed.total,
                    hasMore = seriesFeed.hasMore,
                    libraries = libraries,
                    libraryCount = libraries.size,
                    collections = collections,
                    readLists = readLists,
                )
            }
            // Persist the first page for the same query (ids + card rows), plus the Lists rows.
            runCatching {
                // The cache holds the first page only, whatever this reload kept on screen.
                catalogCache.saveSeries(listKey(scope, seriesOptions), seriesItems.take(PAGE_SIZE))
                catalogCache.saveBooks(listKey(scope, booksOptions), bookItems.take(PAGE_SIZE))
                catalogCache.saveLists(collections, readLists)
            }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentGeneration != generation) return
            _state.update {
                // Keep whatever is on screen (the cached first page): a failed refresh must not blank
                // the tab. The offline indicator carries the connectivity signal; the error line is
                // only for the nothing-to-show case.
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = if (it.series == null && it.books == null) {
                        e.message ?: context.getString(R.string.common_load_failed)
                    } else {
                        null
                    },
                )
            }
        } finally {
            drainPendingLoadMore()
        }
    }

    /**
     * Serialises every writer of the two row lists: a load, a load-more, a settle re-read and a row patch.
     * A load-more appends to the rows it read when it started, so without the lock a patch or a settle pass that
     * finished in between would be overwritten by its older snapshot (the generation checks do not see those
     * writers: they do not change the query).
     */
    private val rowsMutex = Mutex()

    /**
     * A load-more that found the rows busy (a load/settle reading the network under [rowsMutex]) sets its
     * flag instead of queueing on the lock — that queueing was the long wait at the bottom of the list.
     * Every rows writer drains the flags right after it releases the lock, so the skipped append runs as
     * soon as the rows are free again.
     */
    @Volatile private var pendingLoadMoreSeries = false
    @Volatile private var pendingLoadMoreBooks = false

    /** Retry the load-mores a busy rows lock turned away (see [pendingLoadMoreSeries]). */
    private fun drainPendingLoadMore() {
        if (pendingLoadMoreSeries) {
            pendingLoadMoreSeries = false
            loadMoreSeries()
        }
        if (pendingLoadMoreBooks) {
            pendingLoadMoreBooks = false
            loadMoreBooks()
        }
    }

    /** Load the next series page at the bottom (ignored while loading, concurrently, or when no more). */
    fun loadMoreSeries() {
        if (query == null) return
        if (!rowsMutex.tryLock()) {
            // A load or settle holds the rows across its network read: do not queue behind it (that wait is
            // what made the bottom of the list feel stuck) — it re-triggers this append when it publishes.
            pendingLoadMoreSeries = true
            return
        }
        viewModelScope.launch {
            try {
                val snapshot = _state.value
                if (loadingMoreSeries || snapshot.loading || !snapshot.seriesHasMore) return@launch
                loadingMoreSeries = true
                val currentGeneration = generation
                _state.update { it.copy(loadingMore = true) }
                try {
                    // Read and append under the same lock a load / settle / patch takes: the rows handed to the
                    // feed are the rows of this moment, and nothing can patch them while the request runs.
                    val items = seriesFeed.append(snapshot.series.orEmpty())
                    if (currentGeneration != generation) return@launch
                    _state.update {
                        it.copy(
                            series = items,
                            seriesHasMore = seriesFeed.hasMore,
                            seriesTotal = seriesFeed.total,
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
            } finally {
                rowsMutex.unlock()
            }
        }
    }

    /** Load the next book page at the bottom. */
    fun loadMoreBooks() {
        if (query == null) return
        if (!rowsMutex.tryLock()) {
            pendingLoadMoreBooks = true
            return
        }
        viewModelScope.launch {
            try {
                val snapshot = _state.value
                if (loadingMoreBooks || snapshot.loading || !snapshot.booksHasMore) return@launch
                loadingMoreBooks = true
                val currentGeneration = generation
                _state.update { it.copy(loadingMore = true) }
                try {
                    val items = booksFeed.append(snapshot.books.orEmpty())
                    if (currentGeneration != generation) return@launch
                    _state.update {
                        it.copy(
                            books = items,
                            booksHasMore = booksFeed.hasMore,
                            booksTotal = booksFeed.total,
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
            } finally {
                rowsMutex.unlock()
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
    fun downloadBook(book: Book) {
        val dtoId = detailRepository.bookDtoId(book.id) ?: return
        downloadRepository.enqueueBook(
            bookDtoId = dtoId,
            title = book.title,
            pageCount = book.pageCount,
            mediaType = book.mediaType,
            epubDivinaCompatible = book.epubDivinaCompatible,
            seriesDtoId = book.seriesDtoId,
        )
    }

    /** Download every book of the series (enqueued one by one). */
    fun downloadSeries(stableId: Int) {
        val dtoId = detailRepository.seriesDtoId(stableId) ?: return
        viewModelScope.launch {
            val books = detailRepository.seriesBooks(dtoId)
            books.forEach { book ->
                downloadRepository.enqueueBook(
                    book.id,
                    book.metadata.title.ifBlank { book.name },
                    book.media.pagesCount,
                    mediaType = book.media.mediaType,
                    epubDivinaCompatible = book.media.epubDivinaCompatible,
                    libraryId = book.libraryId,
                    seriesDtoId = dtoId,
                    seriesBookCount = books.size,
                )
            }
        }
    }

    /** Metadata baseline for editing a book outside its detail page: grid cards only carry the card fields. */
    suspend fun bookMetadata(stableId: Int): BookDetailMeta? =
        detailRepository.bookDtoId(stableId)?.let { dtoId ->
            detailRepository.bookDetail(dtoId)?.toBookDetailMeta()
        }

    /** Editing baseline for a series edited from the grid (full server values plus the locks). */
    suspend fun seriesEditValues(stableId: Int): SeriesEditValues? =
        detailRepository.seriesDtoId(stableId)?.let { dtoId ->
            detailRepository.seriesDetail(dtoId)?.metadata?.toEditValues()
        }

    /** Save the series metadata edit; body == null means no change, an immediate success. */
    fun saveSeriesMetadata(stableId: Int, body: JsonObject?, onDone: (Boolean) -> Unit) {
        if (body == null) {
            onDone(true)
            return
        }
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.updateSeriesMetadata(dtoId, body)
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

    /** Re-analyze the series. */
    fun analyzeSeries(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch { onDone(actionRepository.analyzeSeries(dtoId)) }
    }

    /** Refresh the series metadata; the write signal re-queries the current list (or the row in place). */
    fun refreshSeriesMetadata(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch { onDone(actionRepository.refreshSeriesMetadata(dtoId)) }
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
        /** Instance key of the session the snapshot was built for (null while signed out). */
        val instanceKey: String?,
        val hasSession: Boolean,
        val scope: String,
        val pinned: Set<String>,
        val seriesOptions: BrowseOptions,
        val booksOptions: BrowseOptions,
    )

    /** Create a collection from the picker's selection (Komga refuses empty ones); returns the new entry on success. */
    fun createCollectionEntry(
        name: String,
        ordered: Boolean,
        selection: ListSelection,
        onDone: (ListEntry?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(newCollectionEntry(listRepository, detailRepository, name, ordered, selection.seriesIds))
        }
    }

    /** Create a read list from the picker's selection (its books, or the books of its series); same contract. */
    fun createReadListEntry(
        name: String,
        summary: String,
        ordered: Boolean,
        selection: ListSelection,
        onDone: (ListEntry?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(newReadListEntry(listRepository, detailRepository, name, summary, ordered, selection))
        }
    }

    private companion object {
        /** Page size, mirroring pageSize = 50 of the original SeriesViewModel/BookViewModel. */
        const val PAGE_SIZE = 50

        /**
         * A settle re-read within this window of the last load is redundant: that load already read the state the
         * settle was waiting for.
         */
        const val SETTLE_SUPPRESS_MILLIS = 3_000L

        /**
         * Serializer for the cache list keys. Lives in the companion on purpose: an instance property
         * would still be null while the constructor's init blocks run their first undispatched load.
         */
        val cacheKeyJson = Json { ignoreUnknownKeys = true }
    }
}
