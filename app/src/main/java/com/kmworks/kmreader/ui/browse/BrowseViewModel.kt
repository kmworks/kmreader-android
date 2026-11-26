package com.kmworks.kmreader.ui.browse

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.SearchRepository
import com.kmworks.kmreader.core.data.libraryChanges
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BrowseContentType
import com.kmworks.kmreader.data.BrowseOptions
import com.kmworks.kmreader.data.defaultBrowseOptions
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Search scope chips of the browse tab. */
enum class SearchScope(@param:StringRes val labelRes: Int) {
    All(R.string.common_all),
    Series(R.string.common_series),
    Books(R.string.browse_scope_books),
    Collections(R.string.browse_scope_collections),
    ReadLists(R.string.browse_scope_readlists),
}

/** Browse (search) tab state; series/books/readLists/collections are accumulated result pages. */
data class BrowseUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val libraries: List<LibraryDto> = emptyList(),
    val series: List<Series> = emptyList(),
    val books: List<Book> = emptyList(),
    val readLists: List<SampleData.ListEntry> = emptyList(),
    val collections: List<SampleData.ListEntry> = emptyList(),
    val seriesHasMore: Boolean = false,
    val booksHasMore: Boolean = false,
    val readListsHasMore: Boolean = false,
    val collectionsHasMore: Boolean = false,
    /** Server-side totals of the current query, shown next to the grouped section titles (null = unknown). */
    val seriesTotal: Long? = null,
    val booksTotal: Long? = null,
    val readListsTotal: Long? = null,
    val collectionsTotal: Long? = null,
    /** Whether the current scope has another page (for bottom-of-list detection). */
    val hasMore: Boolean = false,
    /** Library scope (pinned/all/library:{id}; mirrors iOS LibraryBrowseScope, persisted per server, defaults to all). */
    val libraryScope: String = AppSettingsStore.SCOPE_ALL,
    /** Pinned library ids (resolved into a libraryIds filter for the pinned scope). */
    val pinnedIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = series.isEmpty() && books.isEmpty() && readLists.isEmpty() && collections.isEmpty()
}

private data class SearchRequest(
    val query: String,
    val scope: SearchScope,
    val libraryIds: List<String>?,
    val hasSession: Boolean,
    /**
     * Search filters (session-scoped, not persisted; mirrors the search tab's own browseOptions on
     * iOS). Series and books keep separate sets like the library tabs do: the series set carries the
     * publisher/genre/series-status dimensions a book query cannot express, and each group sorts by
     * its own vocabulary.
     */
    val seriesOptions: BrowseOptions = defaultBrowseOptions(BrowseContentType.Series),
    val booksOptions: BrowseOptions = defaultBrowseOptions(BrowseContentType.Books),
) {
    /**
     * Filters only apply in the dedicated scopes: the mixed All scope, and the collections/read lists
     * scopes whose endpoints take no filter conditions, run unfiltered. Keeping the nullable shape means
     * a disabled filter chip can never leak a condition into a query.
     */
    val seriesFilters: BrowseOptions? get() = seriesOptions.takeIf { scope == SearchScope.Series }
    val bookFilters: BrowseOptions? get() = booksOptions.takeIf { scope == SearchScope.Books }
}

/** Search state: 300ms debounce, per-scope fan-out, paged results with bottom load-more. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val searchRepository: SearchRepository,
    private val libraryRepository: LibraryRepository,
    private val appSettings: AppSettingsStore,
    sessionManager: SessionManager,
    private val contentChangeBus: ContentChangeBus,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val scope = MutableStateFlow(SearchScope.All)
    private val seriesBrowseOptions = MutableStateFlow(defaultBrowseOptions(BrowseContentType.Series))
    private val booksBrowseOptions = MutableStateFlow(defaultBrowseOptions(BrowseContentType.Books))

    private val _state = MutableStateFlow(BrowseUiState())
    val state = _state.asStateFlow()

    private var currentRequest: SearchRequest? = null
    private var loadingMore = false
    private var seriesPage = 0
    private var booksPage = 0
    private var readListsPage = 0
    private var collectionsPage = 0

    /**
     * The effective query each kind's visible list was fetched with (keyed by the dedicated scope of
     * that kind). A scope switch that reproduces the same query keeps the accumulated pages instead
     * of refetching from page 0 — e.g. All -> Series with untouched filters, and back.
     */
    private val kindSignatures = mutableMapOf<SearchScope, String>()

    init {
        // library list for the scope menu (on failure fall back to an empty list, no error state)
        viewModelScope.launch {
            sessionManager.clients.collectLatest { clients ->
                if (clients == null) {
                    _state.update { it.copy(hasSession = false, libraries = emptyList()) }
                } else {
                    val libraries = try {
                        libraryRepository.libraries()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    _state.update { it.copy(hasSession = true, libraries = libraries) }
                }
            }
        }
        // A library was added, renamed or removed elsewhere: the scope menu shows the names, so re-read.
        viewModelScope.launch {
            contentChangeBus.libraryChanges().collect {
                val libraries = runCatching { libraryRepository.libraries() }.getOrDefault(emptyList())
                _state.update { it.copy(libraries = libraries) }
            }
        }
        // debounced search pipeline (library scope persisted as searchBrowseScope per server, default all)
        viewModelScope.launch {
            combine(
                combine(
                    query.debounce(300),
                    scope,
                    appSettings.searchBrowseScope,
                    appSettings.pinnedLibraryIds,
                    sessionManager.clients,
                ) { q, s, libraryScope, pinned, clients ->
                    SearchRequest(q.trim(), s, resolveLibraryScopeIds(libraryScope, pinned), clients != null)
                },
                seriesBrowseOptions,
                booksBrowseOptions,
            ) { request, seriesOptions, booksOptions ->
                request.copy(seriesOptions = seriesOptions, booksOptions = booksOptions)
            }
                .distinctUntilChanged()
                .collectLatest { request -> performSearch(request) }
        }
        // The search filter is session state, not a persisted setting: it must not follow the user to another
        // server, where its authors/tags/library ids (and the read-status semantics under a different account)
        // would silently narrow the result set. Drop both sets whenever the session's instance changes.
        viewModelScope.launch {
            sessionManager.clients
                .map { it?.cacheNamespace }
                .distinctUntilChanged()
                .collect {
                    seriesBrowseOptions.value = defaultBrowseOptions(BrowseContentType.Series)
                    booksBrowseOptions.value = defaultBrowseOptions(BrowseContentType.Books)
                }
        }
        // sync library scope / pinned ids into UI state (for the scope menu and the pin-editing sheet)
        viewModelScope.launch {
            combine(
                appSettings.searchBrowseScope,
                appSettings.pinnedLibraryIds,
            ) { s, p -> s to p }
                .collect { (s, p) ->
                    _state.update { it.copy(libraryScope = s, pinnedIds = p) }
                }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onScopeChange(value: SearchScope) {
        scope.value = value
    }

    /** Search filters per content type (extra conditions layered on series/books full-text search; session-scoped). */
    val seriesBrowseOptionsFlow = seriesBrowseOptions.asStateFlow()
    val booksBrowseOptionsFlow = booksBrowseOptions.asStateFlow()

    fun setBrowseOptions(contentType: BrowseContentType, options: BrowseOptions) {
        when (contentType) {
            BrowseContentType.Series -> seriesBrowseOptions.value = options
            BrowseContentType.Books -> booksBrowseOptions.value = options
        }
    }

    /** Library scope switch (pinned/all/library:{id}, persisted; mirrors iOS @AppStorage("searchBrowseScope")). */
    fun onLibraryScopeChange(scope: String) {
        viewModelScope.launch { appSettings.setSearchBrowseScope(scope) }
    }

    /** Replace the pinned id set (saved from the pin-editing sheet). */
    fun setPinnedLibraries(ids: Set<String>) {
        viewModelScope.launch { appSettings.setPinnedLibraryIds(ids) }
    }

    /** Load the next page of the current scope on bottom reach (concurrency guard + early return when no more). */
    fun loadMore() {
        val request = currentRequest ?: return
        if (loadingMore || !_state.value.hasMore) return
        loadingMore = true
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val state = _state.value
                val nextSeries = if (request.scope != SearchScope.Books && state.seriesHasMore) {
                    seriesPage + 1
                } else {
                    null
                }
                val nextBooks = if (request.scope != SearchScope.Series && state.booksHasMore) {
                    booksPage + 1
                } else {
                    null
                }
                val nextReadLists = if (request.scope == SearchScope.All || request.scope == SearchScope.ReadLists) {
                    if (state.readListsHasMore) readListsPage + 1 else null
                } else {
                    null
                }
                val nextCollections = if (request.scope == SearchScope.All || request.scope == SearchScope.Collections) {
                    if (state.collectionsHasMore) collectionsPage + 1 else null
                } else {
                    null
                }
                coroutineScope {
                    val series = async {
                        nextSeries?.let {
                            searchRepository.searchSeriesPage(
                                request.query,
                                request.libraryIds,
                                request.seriesFilters,
                                it,
                                SERIES_PAGE_SIZE,
                            )
                        }
                    }
                    val books = async {
                        nextBooks?.let {
                            searchRepository.searchBooksPage(
                                request.query,
                                request.libraryIds,
                                request.bookFilters,
                                it,
                                BOOKS_PAGE_SIZE,
                            )
                        }
                    }
                    val readLists = async {
                        nextReadLists?.let {
                            searchRepository.searchReadListsPage(request.query, request.libraryIds, it, LISTS_PAGE_SIZE)
                        }
                    }
                    val collections = async {
                        nextCollections?.let {
                            searchRepository.searchCollectionsPage(request.query, request.libraryIds, it, LISTS_PAGE_SIZE)
                        }
                    }
                    val seriesResult = series.await()
                    val booksResult = books.await()
                    val readListsResult = readLists.await()
                    val collectionsResult = collections.await()
                    // Drop this page if the search criteria changed while the request was in flight,
                    // so stale results are never appended to a new query
                    if (currentRequest != request) return@coroutineScope
                    if (nextSeries != null) seriesPage = nextSeries
                    if (nextBooks != null) booksPage = nextBooks
                    if (nextReadLists != null) readListsPage = nextReadLists
                    if (nextCollections != null) collectionsPage = nextCollections
                    _state.update { st ->
                        val seriesItems = st.series +
                            seriesResult?.items.orEmpty().filterNot { item ->
                                st.series.any { it.id == item.id }
                            }
                        val bookItems = st.books +
                            booksResult?.items.orEmpty().filterNot { item ->
                                st.books.any { it.id == item.id }
                            }
                        val readListItems = st.readLists +
                            readListsResult?.items.orEmpty().filterNot { item ->
                                st.readLists.any { it.id == item.id }
                            }
                        val collectionItems = st.collections +
                            collectionsResult?.items.orEmpty().filterNot { item ->
                                st.collections.any { it.id == item.id }
                            }
                        st.copy(
                            loadingMore = false,
                            series = seriesItems,
                            books = bookItems,
                            readLists = readListItems,
                            collections = collectionItems,
                            seriesHasMore = seriesResult?.hasMore ?: st.seriesHasMore,
                            booksHasMore = booksResult?.hasMore ?: st.booksHasMore,
                            readListsHasMore = readListsResult?.hasMore ?: st.readListsHasMore,
                            collectionsHasMore = collectionsResult?.hasMore ?: st.collectionsHasMore,
                            seriesTotal = seriesResult?.totalElements ?: st.seriesTotal,
                            booksTotal = booksResult?.totalElements ?: st.booksTotal,
                            readListsTotal = readListsResult?.totalElements ?: st.readListsTotal,
                            collectionsTotal = collectionsResult?.totalElements ?: st.collectionsTotal,
                            hasMore = computeHasMore(
                                st.copy(
                                    seriesHasMore = seriesResult?.hasMore ?: st.seriesHasMore,
                                    booksHasMore = booksResult?.hasMore ?: st.booksHasMore,
                                    readListsHasMore = readListsResult?.hasMore ?: st.readListsHasMore,
                                    collectionsHasMore = collectionsResult?.hasMore ?: st.collectionsHasMore,
                                ),
                                request.scope,
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(loadingMore = false, error = e.message ?: context.getString(R.string.common_load_more_failed))
                }
            } finally {
                loadingMore = false
            }
        }
    }

    // Default options run the same query as no options (library condition + the type's default
    // sort), so they do not distinguish a kind's signature: untouched filters reuse, real filters refetch.
    private fun seriesKey(request: SearchRequest): String = listOf(
        request.query,
        request.libraryIds?.joinToString(","),
        request.seriesFilters?.takeIf { it != defaultBrowseOptions(BrowseContentType.Series) },
    ).joinToString("|")

    private fun booksKey(request: SearchRequest): String = listOf(
        request.query,
        request.libraryIds?.joinToString(","),
        request.bookFilters?.takeIf { it != defaultBrowseOptions(BrowseContentType.Books) },
    ).joinToString("|")

    private fun listsKey(request: SearchRequest): String =
        listOf(request.query, request.libraryIds?.joinToString(",")).joinToString("|")

    private fun resetKind(kind: SearchScope) {
        kindSignatures.remove(kind)
        when (kind) {
            SearchScope.Series -> seriesPage = 0
            SearchScope.Books -> booksPage = 0
            SearchScope.ReadLists -> readListsPage = 0
            SearchScope.Collections -> collectionsPage = 0
            SearchScope.All -> Unit
        }
    }

    /** Load the next page of one result kind (the grouped strips page independently of each other). */
    fun loadMoreKind(kind: SearchScope) {
        if (kind == SearchScope.All) return
        val request = currentRequest ?: return
        if (loadingMore) return
        val state = _state.value
        val hasMore = when (kind) {
            SearchScope.Series -> state.seriesHasMore
            SearchScope.Books -> state.booksHasMore
            SearchScope.ReadLists -> state.readListsHasMore
            SearchScope.Collections -> state.collectionsHasMore
            SearchScope.All -> false
        }
        if (!hasMore) return
        loadingMore = true
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                when (kind) {
                    SearchScope.Series -> {
                        val next = seriesPage + 1
                        val result = searchRepository.searchSeriesPage(
                            request.query, request.libraryIds, request.seriesFilters, next, SERIES_PAGE_SIZE,
                        )
                        if (currentRequest != request) return@launch
                        seriesPage = next
                        _state.update { st ->
                            val items = st.series + result.items.filterNot { item -> st.series.any { it.id == item.id } }
                            st.copy(
                                loadingMore = false,
                                series = items,
                                seriesHasMore = result.hasMore,
                                seriesTotal = result.totalElements ?: st.seriesTotal,
                                hasMore = computeHasMore(st.copy(seriesHasMore = result.hasMore), request.scope),
                            )
                        }
                    }
                    SearchScope.Books -> {
                        val next = booksPage + 1
                        val result = searchRepository.searchBooksPage(
                            request.query, request.libraryIds, request.bookFilters, next, BOOKS_PAGE_SIZE,
                        )
                        if (currentRequest != request) return@launch
                        booksPage = next
                        _state.update { st ->
                            val items = st.books + result.items.filterNot { item -> st.books.any { it.id == item.id } }
                            st.copy(
                                loadingMore = false,
                                books = items,
                                booksHasMore = result.hasMore,
                                booksTotal = result.totalElements ?: st.booksTotal,
                                hasMore = computeHasMore(st.copy(booksHasMore = result.hasMore), request.scope),
                            )
                        }
                    }
                    SearchScope.ReadLists -> {
                        val next = readListsPage + 1
                        val result = searchRepository.searchReadListsPage(
                            request.query, request.libraryIds, next, LISTS_PAGE_SIZE,
                        )
                        if (currentRequest != request) return@launch
                        readListsPage = next
                        _state.update { st ->
                            val items = st.readLists + result.items.filterNot { item -> st.readLists.any { it.id == item.id } }
                            st.copy(
                                loadingMore = false,
                                readLists = items,
                                readListsHasMore = result.hasMore,
                                readListsTotal = result.totalElements ?: st.readListsTotal,
                                hasMore = computeHasMore(st.copy(readListsHasMore = result.hasMore), request.scope),
                            )
                        }
                    }
                    SearchScope.Collections -> {
                        val next = collectionsPage + 1
                        val result = searchRepository.searchCollectionsPage(
                            request.query, request.libraryIds, next, LISTS_PAGE_SIZE,
                        )
                        if (currentRequest != request) return@launch
                        collectionsPage = next
                        _state.update { st ->
                            val items = st.collections + result.items.filterNot { item -> st.collections.any { it.id == item.id } }
                            st.copy(
                                loadingMore = false,
                                collections = items,
                                collectionsHasMore = result.hasMore,
                                collectionsTotal = result.totalElements ?: st.collectionsTotal,
                                hasMore = computeHasMore(st.copy(collectionsHasMore = result.hasMore), request.scope),
                            )
                        }
                    }
                    SearchScope.All -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(loadingMore = false, error = e.message ?: context.getString(R.string.common_load_more_failed))
                }
            } finally {
                loadingMore = false
            }
        }
    }

    private suspend fun performSearch(request: SearchRequest) {
        currentRequest = request
        // No session or blank query: clear results and let the screen show its empty state (no fake-data fallback)
        if (!request.hasSession || request.query.isBlank()) {
            kindSignatures.clear()
            seriesPage = 0
            booksPage = 0
            readListsPage = 0
            collectionsPage = 0
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = null,
                    series = emptyList(),
                    books = emptyList(),
                    readLists = emptyList(),
                    collections = emptyList(),
                    seriesHasMore = false,
                    booksHasMore = false,
                    readListsHasMore = false,
                    collectionsHasMore = false,
                    seriesTotal = null,
                    booksTotal = null,
                    readListsTotal = null,
                    collectionsTotal = null,
                    hasMore = false,
                )
            }
            return
        }

        val wantsSeries = request.scope == SearchScope.All || request.scope == SearchScope.Series
        val wantsBooks = request.scope == SearchScope.All || request.scope == SearchScope.Books
        val wantsReadLists = request.scope == SearchScope.All || request.scope == SearchScope.ReadLists
        val wantsCollections = request.scope == SearchScope.All || request.scope == SearchScope.Collections

        // A kind keeps its accumulated pages when this request reproduces the exact query its
        // visible list was fetched with; anything else goes back to page 0.
        val reuseSeries = wantsSeries && kindSignatures[SearchScope.Series] == seriesKey(request)
        val reuseBooks = wantsBooks && kindSignatures[SearchScope.Books] == booksKey(request)
        val reuseReadLists = wantsReadLists && kindSignatures[SearchScope.ReadLists] == listsKey(request)
        val reuseCollections = wantsCollections && kindSignatures[SearchScope.Collections] == listsKey(request)
        if (!reuseSeries) resetKind(SearchScope.Series)
        if (!reuseBooks) resetKind(SearchScope.Books)
        if (!reuseReadLists) resetKind(SearchScope.ReadLists)
        if (!reuseCollections) resetKind(SearchScope.Collections)
        val fetchSeries = wantsSeries && !reuseSeries
        val fetchBooks = wantsBooks && !reuseBooks
        val fetchReadLists = wantsReadLists && !reuseReadLists
        val fetchCollections = wantsCollections && !reuseCollections

        if (!fetchSeries && !fetchBooks && !fetchReadLists && !fetchCollections) {
            // Every in-scope kind is reused: only out-of-scope leftovers need clearing.
            _state.update { st ->
                val updated = st.copy(
                    loading = false,
                    loadingMore = false,
                    error = null,
                    series = if (wantsSeries) st.series else emptyList(),
                    books = if (wantsBooks) st.books else emptyList(),
                    readLists = if (wantsReadLists) st.readLists else emptyList(),
                    collections = if (wantsCollections) st.collections else emptyList(),
                    seriesHasMore = if (wantsSeries) st.seriesHasMore else false,
                    booksHasMore = if (wantsBooks) st.booksHasMore else false,
                    readListsHasMore = if (wantsReadLists) st.readListsHasMore else false,
                    collectionsHasMore = if (wantsCollections) st.collectionsHasMore else false,
                )
                updated.copy(hasMore = computeHasMore(updated, request.scope))
            }
            return
        }

        _state.update { it.copy(loading = true, error = null) }
        try {
            val seriesResult = coroutineScope {
                val series = async {
                    if (fetchSeries) {
                        searchRepository.searchSeriesPage(
                            request.query,
                            request.libraryIds,
                            request.seriesFilters,
                            page = 0,
                            size = SERIES_PAGE_SIZE,
                        )
                    } else {
                        null
                    }
                }
                val books = async {
                    if (fetchBooks) {
                        searchRepository.searchBooksPage(
                            request.query,
                            request.libraryIds,
                            request.bookFilters,
                            page = 0,
                            size = BOOKS_PAGE_SIZE,
                        )
                    } else {
                        null
                    }
                }
                val readLists = async {
                    if (fetchReadLists) {
                        searchRepository.searchReadListsPage(
                            request.query,
                            request.libraryIds,
                            page = 0,
                            size = LISTS_PAGE_SIZE,
                        )
                    } else {
                        null
                    }
                }
                val collections = async {
                    if (fetchCollections) {
                        searchRepository.searchCollectionsPage(
                            request.query,
                            request.libraryIds,
                            page = 0,
                            size = LISTS_PAGE_SIZE,
                        )
                    } else {
                        null
                    }
                }
                SearchResults(
                    series = series.await(),
                    books = books.await(),
                    readLists = readLists.await(),
                    collections = collections.await(),
                )
            }
            if (fetchSeries) kindSignatures[SearchScope.Series] = seriesKey(request)
            if (fetchBooks) kindSignatures[SearchScope.Books] = booksKey(request)
            if (fetchReadLists) kindSignatures[SearchScope.ReadLists] = listsKey(request)
            if (fetchCollections) kindSignatures[SearchScope.Collections] = listsKey(request)
            _state.update { st ->
                val updated = st.copy(
                    loading = false,
                    loadingMore = false,
                    series = if (fetchSeries) seriesResult.series?.items.orEmpty() else if (wantsSeries) st.series else emptyList(),
                    books = if (fetchBooks) seriesResult.books?.items.orEmpty() else if (wantsBooks) st.books else emptyList(),
                    readLists = if (fetchReadLists) seriesResult.readLists?.items.orEmpty() else if (wantsReadLists) st.readLists else emptyList(),
                    collections = if (fetchCollections) seriesResult.collections?.items.orEmpty() else if (wantsCollections) st.collections else emptyList(),
                    seriesHasMore = if (fetchSeries) seriesResult.series?.hasMore ?: false else if (wantsSeries) st.seriesHasMore else false,
                    booksHasMore = if (fetchBooks) seriesResult.books?.hasMore ?: false else if (wantsBooks) st.booksHasMore else false,
                    readListsHasMore = if (fetchReadLists) seriesResult.readLists?.hasMore ?: false else if (wantsReadLists) st.readListsHasMore else false,
                    collectionsHasMore = if (fetchCollections) seriesResult.collections?.hasMore ?: false else if (wantsCollections) st.collectionsHasMore else false,
                    seriesTotal = if (fetchSeries) seriesResult.series?.totalElements else if (wantsSeries) st.seriesTotal else null,
                    booksTotal = if (fetchBooks) seriesResult.books?.totalElements else if (wantsBooks) st.booksTotal else null,
                    readListsTotal = if (fetchReadLists) seriesResult.readLists?.totalElements else if (wantsReadLists) st.readListsTotal else null,
                    collectionsTotal = if (fetchCollections) seriesResult.collections?.totalElements else if (wantsCollections) st.collectionsTotal else null,
                )
                updated.copy(hasMore = computeHasMore(updated, request.scope))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: context.getString(R.string.browse_search_failed)) }
        }
    }

    private fun computeHasMore(state: BrowseUiState, searchScope: SearchScope): Boolean = when (searchScope) {
        SearchScope.All -> state.seriesHasMore || state.booksHasMore ||
            state.readListsHasMore || state.collectionsHasMore
        SearchScope.Series -> state.seriesHasMore
        SearchScope.Books -> state.booksHasMore
        SearchScope.ReadLists -> state.readListsHasMore
        SearchScope.Collections -> state.collectionsHasMore
    }

    private data class SearchResults(
        val series: com.kmworks.kmreader.core.data.Paged<Series>?,
        val books: com.kmworks.kmreader.core.data.Paged<Book>?,
        val readLists: com.kmworks.kmreader.core.data.Paged<SampleData.ListEntry>?,
        val collections: com.kmworks.kmreader.core.data.Paged<SampleData.ListEntry>?,
    )

    private companion object {
        /** Uniform 50 items per page, matching the infinite-scroll policy of every list. */
        const val SERIES_PAGE_SIZE = 50
        const val BOOKS_PAGE_SIZE = 50
        const val LISTS_PAGE_SIZE = 50
    }
}
