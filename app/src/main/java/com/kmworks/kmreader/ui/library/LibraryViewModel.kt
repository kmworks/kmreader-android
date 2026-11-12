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
    val error: String? = null,
    val series: List<Series>? = null,
    val books: List<Book>? = null,
    val thumbnails: Map<Int, String> = emptyMap(),
    val libraries: List<LibraryDto> = emptyList(),
    val libraryCount: Int = 0,
    /** LibraryBrowseScope rawValue：pinned / all / library:{id}。 */
    val scope: String = AppSettingsStore.SCOPE_PINNED,
    val pinnedIds: Set<String> = emptySet(),
    val browseOptions: BookBrowseOptions = BookBrowseOptions(),
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
)

/** Real library content (scope + filters drive POST list queries). */
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

    init {
        // 会话 × 范围 × 钉选 × 筛选 任一变化都重查（对齐原作 scope/filters 直接驱动查询）
        viewModelScope.launch {
            combine(
                sessionManager.clients,
                appSettings.libraryBrowseScope,
                appSettings.pinnedLibraryIds,
                browseOptionsFlow,
            ) { clients, scope, pinned, options -> ScopeQuery(clients != null, scope, pinned, options) }
                .collectLatest { query ->
                    if (!query.hasSession) {
                        _state.update {
                            it.copy(
                                hasSession = false,
                                loading = false,
                                series = null,
                                books = null,
                                thumbnails = emptyMap(),
                                libraries = emptyList(),
                                scope = query.scope,
                                pinnedIds = query.pinned,
                                browseOptions = query.options,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                scope = query.scope,
                                pinnedIds = query.pinned,
                                browseOptions = query.options,
                            )
                        }
                        load(query.scope, query.pinned, query.options)
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
            val current = _state.value
            if (current.hasSession) load(current.scope, current.pinnedIds, current.browseOptions)
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
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        try {
            val libraryIds = resolveLibraryIds(scope, pinned)
            val libraries = libraryRepository.libraries()
            val (seriesDtos, bookDtos) = coroutineScope {
                val series = async {
                    searchRepository.browseSeries(libraryIds, options)
                        ?: throw IllegalStateException("系列列表加载失败")
                }
                val books = async {
                    searchRepository.browseBooks(libraryIds, options)
                        ?: throw IllegalStateException("书籍列表加载失败")
                }
                series.await() to books.await()
            }
            val (collectionDtos, readListDtos) = coroutineScope {
                val c = async { listRepository.collections() }
                val r = async { listRepository.readLists() }
                c.await() to r.await()
            }
            // 缩略图按 UI id 键（stableId = dtoId.hashCode()），dtoId 由注册表反查
            val thumbMap = buildMap<Int, String> {
                seriesDtos.forEach { dto ->
                    detailRepository.seriesDtoId(dto.id)
                        ?.let { searchRepository.seriesThumbnailUrl(it) }
                        ?.let { put(dto.id, it) }
                }
                bookDtos.forEach { dto ->
                    detailRepository.bookDtoId(dto.id)
                        ?.let { searchRepository.bookThumbnailUrl(it) }
                        ?.let { put(dto.id, it) }
                }
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
                    series = seriesDtos,
                    books = bookDtos,
                    thumbnails = thumbMap,
                    libraries = libraries,
                    libraryCount = libraries.size,
                    collections = collections,
                    readLists = readLists,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "加载失败", series = null, books = null) }
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
        /** 触发书库刷新的事件类别。 */
        val REFRESH_KINDS = setOf(
            SseEventKind.Book,
            SseEventKind.Series,
            SseEventKind.ReadProgress,
            SseEventKind.Library,
        )
    }
}
