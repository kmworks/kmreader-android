package com.kmworks.kmreader.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.SearchRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Search scope chips of the browse tab. */
enum class SearchScope(val label: String) {
    All("全部"),
    Series("系列"),
    Books("书籍"),
    ReadLists("阅读列表"),
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
    /** 当前 scope 下是否还有下一页（屏幕触底判定用）。 */
    val hasMore: Boolean = false,
) {
    val isEmpty: Boolean
        get() = series.isEmpty() && books.isEmpty() && readLists.isEmpty() && collections.isEmpty()
}

private data class SearchRequest(
    val query: String,
    val scope: SearchScope,
    val libraryId: String?,
    val hasSession: Boolean,
)

/** Search state: 300ms debounce, per-scope fan-out, paged results with bottom load-more. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val libraryRepository: LibraryRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val scope = MutableStateFlow(SearchScope.All)
    private val selectedLibraryId = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(BrowseUiState())
    val state = _state.asStateFlow()

    private var currentRequest: SearchRequest? = null
    private var loadingMore = false
    private var seriesPage = 0
    private var booksPage = 0
    private var readListsPage = 0
    private var collectionsPage = 0

    init {
        // library list for the scope menu（失败降级为空列表，不进错误态）
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
        // debounced search pipeline
        viewModelScope.launch {
            combine(
                query.debounce(300),
                scope,
                selectedLibraryId,
                sessionManager.clients,
            ) { q, s, libraryId, clients ->
                SearchRequest(q.trim(), s, libraryId, clients != null)
            }
                .distinctUntilChanged()
                .collectLatest { request -> performSearch(request) }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onScopeChange(value: SearchScope) {
        scope.value = value
    }

    fun onLibraryChange(libraryId: String?) {
        selectedLibraryId.value = libraryId
    }

    /** 触底加载当前 scope 的下一页（并发保护 + 无更多直接返回）。 */
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
                val nextCollections = if (request.scope == SearchScope.All && state.collectionsHasMore) {
                    collectionsPage + 1
                } else {
                    null
                }
                coroutineScope {
                    val series = async {
                        nextSeries?.let {
                            searchRepository.searchSeriesPage(request.query, request.libraryId, it, SERIES_PAGE_SIZE)
                        }
                    }
                    val books = async {
                        nextBooks?.let {
                            searchRepository.searchBooksPage(request.query, request.libraryId, it, BOOKS_PAGE_SIZE)
                        }
                    }
                    val readLists = async {
                        nextReadLists?.let {
                            searchRepository.searchReadListsPage(request.query, request.libraryId, it, LISTS_PAGE_SIZE)
                        }
                    }
                    val collections = async {
                        nextCollections?.let {
                            searchRepository.searchCollectionsPage(request.query, request.libraryId, it, LISTS_PAGE_SIZE)
                        }
                    }
                    val seriesResult = series.await()
                    val booksResult = books.await()
                    val readListsResult = readLists.await()
                    val collectionsResult = collections.await()
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
                _state.update { it.copy(loadingMore = false, error = e.message ?: "加载更多失败") }
            } finally {
                loadingMore = false
            }
        }
    }

    private suspend fun performSearch(request: SearchRequest) {
        currentRequest = request
        seriesPage = 0
        booksPage = 0
        readListsPage = 0
        collectionsPage = 0
        // 无会话或空关键词：清空结果，由屏幕展示空态（无假数据回退）
        if (!request.hasSession || request.query.isBlank()) {
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
                    hasMore = false,
                )
            }
            return
        }

        _state.update { it.copy(loading = true, error = null) }
        try {
            val seriesResult = coroutineScope {
                val series = async {
                    if (request.scope == SearchScope.All || request.scope == SearchScope.Series) {
                        searchRepository.searchSeriesPage(
                            request.query,
                            request.libraryId,
                            page = 0,
                            size = SERIES_PAGE_SIZE,
                        )
                    } else {
                        null
                    }
                }
                val books = async {
                    if (request.scope == SearchScope.All || request.scope == SearchScope.Books) {
                        searchRepository.searchBooksPage(
                            request.query,
                            request.libraryId,
                            page = 0,
                            size = BOOKS_PAGE_SIZE,
                        )
                    } else {
                        null
                    }
                }
                val readLists = async {
                    if (request.scope == SearchScope.All || request.scope == SearchScope.ReadLists) {
                        searchRepository.searchReadListsPage(
                            request.query,
                            request.libraryId,
                            page = 0,
                            size = LISTS_PAGE_SIZE,
                        )
                    } else {
                        null
                    }
                }
                val collections = async {
                    if (request.scope == SearchScope.All) {
                        searchRepository.searchCollectionsPage(
                            request.query,
                            request.libraryId,
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
            _state.update { st ->
                val updated = st.copy(
                    loading = false,
                    loadingMore = false,
                    series = seriesResult.series?.items.orEmpty(),
                    books = seriesResult.books?.items.orEmpty(),
                    readLists = seriesResult.readLists?.items.orEmpty(),
                    collections = seriesResult.collections?.items.orEmpty(),
                    seriesHasMore = seriesResult.series?.hasMore ?: false,
                    booksHasMore = seriesResult.books?.hasMore ?: false,
                    readListsHasMore = seriesResult.readLists?.hasMore ?: false,
                    collectionsHasMore = seriesResult.collections?.hasMore ?: false,
                )
                updated.copy(hasMore = computeHasMore(updated, request.scope))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "搜索失败") }
        }
    }

    private fun computeHasMore(state: BrowseUiState, searchScope: SearchScope): Boolean = when (searchScope) {
        SearchScope.All -> state.seriesHasMore || state.booksHasMore ||
            state.readListsHasMore || state.collectionsHasMore
        SearchScope.Series -> state.seriesHasMore
        SearchScope.Books -> state.booksHasMore
        SearchScope.ReadLists -> state.readListsHasMore
    }

    private data class SearchResults(
        val series: com.kmworks.kmreader.core.data.Paged<Series>?,
        val books: com.kmworks.kmreader.core.data.Paged<Book>?,
        val readLists: com.kmworks.kmreader.core.data.Paged<SampleData.ListEntry>?,
        val collections: com.kmworks.kmreader.core.data.Paged<SampleData.ListEntry>?,
    )

    private companion object {
        /** 搜索结果每页数量：系列/书籍 30（与原作搜索一致），列表类 20。 */
        const val SERIES_PAGE_SIZE = 30
        const val BOOKS_PAGE_SIZE = 30
        const val LISTS_PAGE_SIZE = 20
    }
}
