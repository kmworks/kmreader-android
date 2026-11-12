package com.kmworks.kmreader.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.SearchRepository
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
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

/** Browse (search) tab state; series/books/readLists/collections are results. */
data class BrowseUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val libraries: List<LibraryDto> = emptyList(),
    val series: List<Series> = emptyList(),
    val books: List<Book> = emptyList(),
    val readLists: List<SampleData.ListEntry> = emptyList(),
    val collections: List<SampleData.ListEntry> = emptyList(),
    val thumbnails: Map<Int, String> = emptyMap(),
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

private data class SearchResults(
    val series: List<SeriesDto>,
    val books: List<BookDto>,
    val readLists: List<ReadListDto>,
    val collections: List<CollectionDto>,
)

/** Search state: 300ms debounce, per-scope fan-out, sample fallback without a session. */
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

    private suspend fun performSearch(request: SearchRequest) {
        // 无会话或空关键词：清空结果，由屏幕展示空态（无假数据回退）
        if (!request.hasSession || request.query.isBlank()) {
            _state.update {
                it.copy(
                    loading = false,
                    error = null,
                    series = emptyList(),
                    books = emptyList(),
                    readLists = emptyList(),
                    collections = emptyList(),
                    thumbnails = emptyMap(),
                )
            }
            return
        }

        _state.update { it.copy(loading = true, error = null) }
        try {
            val results = coroutineScope {
                val series = async {
                    if (request.scope == SearchScope.All || request.scope == SearchScope.Series) {
                        searchRepository.searchSeries(request.query, request.libraryId)
                    } else {
                        emptyList()
                    }
                }
                val books = async {
                    if (request.scope == SearchScope.All || request.scope == SearchScope.Books) {
                        searchRepository.searchBooks(request.query, request.libraryId)
                    } else {
                        emptyList()
                    }
                }
                val readLists = async {
                    if (request.scope == SearchScope.All || request.scope == SearchScope.ReadLists) {
                        searchRepository.searchReadLists(request.query, request.libraryId)
                    } else {
                        emptyList()
                    }
                }
                val collections = async {
                    if (request.scope == SearchScope.All) {
                        searchRepository.searchCollections(request.query, request.libraryId)
                    } else {
                        emptyList()
                    }
                }
                SearchResults(series.await(), books.await(), readLists.await(), collections.await())
            }
            val thumbnails = buildMap {
                results.series.forEach { dto ->
                    searchRepository.seriesThumbnailUrl(dto.id)?.let { url -> put(dto.id.hashCode(), url) }
                }
                results.books.forEach { dto ->
                    searchRepository.bookThumbnailUrl(dto.id)?.let { url -> put(dto.id.hashCode(), url) }
                }
            }
            _state.update {
                it.copy(
                    loading = false,
                    series = results.series.map { dto -> dto.toUiSeries() },
                    books = results.books.map { dto -> dto.toUiBook() },
                    readLists = results.readLists.map { dto -> dto.toListEntry() },
                    collections = results.collections.map { dto -> dto.toListEntry() },
                    thumbnails = thumbnails,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "搜索失败") }
        }
    }
}
