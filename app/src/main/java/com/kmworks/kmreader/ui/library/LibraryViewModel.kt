package com.kmworks.kmreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.BookRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.SearchRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.SampleData.ListEntry
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
    val hasMore: Boolean = false,
    val libraries: List<LibraryDto> = emptyList(),
    val libraryCount: Int = 0,
    /** LibraryBrowseScope rawValue：pinned / all / library:{id}。 */
    val scope: String = AppSettingsStore.SCOPE_PINNED,
    val pinnedIds: Set<String> = emptySet(),
    val browseOptions: BookBrowseOptions = BookBrowseOptions(),
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
)

/** Real library content (scope + filters drive paged POST list queries). */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val libraryRepository: LibraryRepository,
    private val searchRepository: SearchRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val detailRepository: DetailRepository,
    private val sseService: SseService,
    private val appSettings: AppSettingsStore,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state = _state.asStateFlow()

    private val browseOptionsFlow = MutableStateFlow(BookBrowseOptions())

    /** 当前查询快照，翻页沿用同一条件；查询变化时自增 generation 丢弃迟到结果。 */
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

    init {
        // 会话 × 范围 × 钉选 × 筛选 任一变化都重查（对齐原作 scope/filters 直接驱动查询）
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
        // SSE 内容变化事件聚合 3s 后刷新
        viewModelScope.launch {
            sseService.events
                .filter { it.kind in REFRESH_KINDS }
                .debounce(3_000)
                .collect { refresh() }
        }
    }

    /** 重新拉取列表内容（动作成功或 SSE 事件后的内容同步）。 */
    fun refresh() {
        viewModelScope.launch {
            val current = query
            if (_state.value.hasSession && current != null) load(current.scope, current.pinned, current.options)
        }
    }

    /** 切换库范围（pinned/all/library:{id}，持久化后由 flow 触发重查）。 */
    fun setScope(scope: String) {
        viewModelScope.launch { appSettings.setLibraryBrowseScope(scope) }
    }

    /** 覆盖钉选集合（钉选编辑 sheet 保存）。 */
    fun setPinnedLibraries(ids: Set<String>) {
        viewModelScope.launch { appSettings.setPinnedLibraryIds(ids) }
    }

    /** 切换单个书库的钉选状态。 */
    fun togglePinnedLibrary(libraryId: String) {
        val next = _state.value.pinnedIds.toMutableSet()
        if (!next.remove(libraryId)) next += libraryId
        setPinnedLibraries(next)
    }

    /** 更新筛选/排序选项（BrowseOptionsSheet 编辑结果真实作用于查询）。 */
    fun setBrowseOptions(options: BookBrowseOptions) {
        browseOptionsFlow.value = options
    }

    /** scope 解析为库 id 列表（对齐 iOS resolvedIds：all→不过滤，pinned 空→不过滤）。 */
    private fun resolveLibraryIds(scope: String, pinned: Set<String>): List<String>? = when {
        scope == AppSettingsStore.SCOPE_ALL -> null
        scope.startsWith("library:") -> listOf(scope.removePrefix("library:"))
        else -> pinned.toList().takeIf { it.isNotEmpty() }
    }

    private suspend fun load(scope: String, pinned: Set<String>, options: BookBrowseOptions) {
        val nextQuery = Query(scope, pinned, options)
        val currentGeneration = ++generation
        query = nextQuery
        seriesPage = 0
        booksPage = 0
        loadingMoreSeries = false
        loadingMoreBooks = false
        _state.update {
            it.copy(hasSession = true, loading = true, loadingMore = false, error = null)
        }
        try {
            val libraryIds = resolveLibraryIds(scope, pinned)
            val libraries = libraryRepository.libraries()
            val (seriesPageResult, bookPageResult) = coroutineScope {
                val series = async {
                    searchRepository.browseSeriesPage(libraryIds, options, page = 0, size = PAGE_SIZE)
                        ?: throw IllegalStateException("系列列表加载失败")
                }
                val books = async {
                    searchRepository.browseBooksPage(libraryIds, options, page = 0, size = PAGE_SIZE)
                        ?: throw IllegalStateException("书籍列表加载失败")
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
                ListEntry(dto.id.hashCode(), dto.name, dto.seriesIds.size, hueFor(dto.id), false)
            }
            val readLists = readListDtos.map { dto ->
                ListEntry(dto.id.hashCode(), dto.name, dto.bookIds.size, hueFor(dto.id), false)
            }
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    series = seriesPageResult.items,
                    books = bookPageResult.items,
                    seriesHasMore = seriesPageResult.hasMore,
                    booksHasMore = bookPageResult.hasMore,
                    hasMore = seriesPageResult.hasMore,
                    libraries = libraries,
                    libraryCount = libraries.size,
                    collections = collections,
                    readLists = readLists,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentGeneration != generation) return
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = e.message ?: "加载失败",
                    series = null,
                    books = null,
                )
            }
        }
    }

    /** 触底加载系列下一页（并发/无更多/加载中直接忽略）。 */
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
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentGeneration != generation) return@launch
                _state.update { it.copy(loadingMore = false, error = e.message ?: "加载更多失败") }
            } finally {
                loadingMoreSeries = false
            }
        }
    }

    /** 触底加载书籍下一页。 */
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
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentGeneration != generation) return@launch
                _state.update { it.copy(loadingMore = false, error = e.message ?: "加载更多失败") }
            } finally {
                loadingMoreBooks = false
            }
        }
    }

    /** 标记系列已读/未读并即时更新本地状态；无 DTO 映射时静默忽略（演示数据）。 */
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

    /** 标记书籍已读/未读并即时更新本地状态；无 DTO 映射时静默忽略。 */
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

    /** 把系列加入收藏。 */
    fun addSeriesToCollection(entry: ListEntry, series: Series) {
        val seriesDtoId = detailRepository.seriesDtoId(series.id) ?: return
        val collectionDtoId = detailRepository.collectionDtoId(entry.id) ?: return
        viewModelScope.launch {
            actionRepository.addSeriesToCollection(collectionDtoId, seriesDtoId)
        }
    }

    /** 把系列的全部书籍加入阅读列表（Komga 阅读列表只收书籍）。 */
    fun addSeriesToReadList(entry: ListEntry, series: Series) {
        val seriesDtoId = detailRepository.seriesDtoId(series.id) ?: return
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return
        viewModelScope.launch {
            detailRepository.seriesBooks(seriesDtoId).forEach { book ->
                actionRepository.addBookToReadList(readListDtoId, book.id)
            }
        }
    }

    /** 把书籍加入阅读列表。 */
    fun addBookToReadList(entry: ListEntry, book: Book) {
        val bookDtoId = detailRepository.bookDtoId(book.id) ?: return
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return
        viewModelScope.launch {
            actionRepository.addBookToReadList(readListDtoId, bookDtoId)
        }
    }

    /** 查询快照（会话+范围+钉选+筛选）。 */
    private data class ScopeQuery(
        val hasSession: Boolean,
        val scope: String,
        val pinned: Set<String>,
        val options: BookBrowseOptions,
    )

    private companion object {
        /** 每页数量，对齐原作 SeriesViewModel/BookViewModel 的 pageSize = 50。 */
        const val PAGE_SIZE = 50

        /** 触发书库刷新的事件类别。 */
        val REFRESH_KINDS = setOf(
            SseEventKind.Book,
            SseEventKind.Series,
            SseEventKind.ReadProgress,
            SseEventKind.Library,
        )
    }
}
