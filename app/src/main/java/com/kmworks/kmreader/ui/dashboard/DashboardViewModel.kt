package com.kmworks.kmreader.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.BookRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.ReadListContinuationUi
import com.kmworks.kmreader.data.ReadListsInProgressSectionId
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Dashboard sections state; sections == null keeps the sample demo UI. */
data class DashboardUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val sections: List<DashboardSection<out Any>>? = null,
    /** 进行中的阅读列表续读卡（真实计算；无进行中列表时为空）。 */
    val continuations: List<ReadListContinuationUi> = emptyList(),
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
    /** 分区标题右侧是否显示卡片形态菜单（设置页持久化）。 */
    val kindMenuEnabled: Boolean = true,
    /** 库范围菜单数据：服务器真实库列表。 */
    val libraries: List<LibraryDto> = emptyList(),
    /** 库范围选择（null = 全部书库）；各分区查询经 core/data 的 libraryIds 参数过滤。 */
    val libraryScope: String? = null,
)

/** 仪表盘布局配置（持久化于 AppSettingsStore，对齐 iOS DashboardConfiguration）。 */
private data class DashboardLayout(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val continuation: Boolean = true,
    val kindMenu: Boolean = true,
    val overrides: Map<String, DashboardCardKind> = emptyMap(),
)

/** Real dashboard data for the five sections (books + series feeds). */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val seriesRepository: SeriesRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val detailRepository: DetailRepository,
    private val libraryRepository: LibraryRepository,
    private val sseService: SseService,
    private val appSettings: AppSettingsStore,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state = _state.asStateFlow()

    /** 服务端产出的分区（未按设置裁剪），布局变化时重新派生 sections。 */
    private var rawSections: List<DashboardSection<out Any>> = emptyList()
    private var layout = DashboardLayout()

    /** 重新拉取分区内容（动作成功后的内容同步）。 */
    fun refresh() {
        viewModelScope.launch { load() }
    }

    init {
        viewModelScope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _state.update { it.copy(hasSession = false, loading = false, sections = null) }
                } else {
                    load()
                }
            }
        }
        // 设置页改动顺序/显隐/卡片形态后即时重排，无需重新请求
        viewModelScope.launch {
            combine(
                appSettings.dashboardSectionOrder,
                appSettings.dashboardHiddenSections,
                appSettings.dashboardContinuationEnabled,
                appSettings.dashboardCardKindMenu,
                appSettings.dashboardKindOverrides,
            ) { order, hidden, continuation, kindMenu, overrides ->
                DashboardLayout(
                    order = order,
                    hidden = hidden,
                    continuation = continuation,
                    kindMenu = kindMenu,
                    overrides = overrides.mapNotNull { (id, value) ->
                        DashboardCardKind.entries.firstOrNull { it.name == value }?.let { id to it }
                    }.toMap(),
                )
            }.collect { next ->
                layout = next
                val sections = derivedSections()
                _state.update {
                    it.copy(
                        sections = sections ?: it.sections,
                        kindMenuEnabled = next.kindMenu,
                    )
                }
            }
        }
        // SSE 内容变化事件聚合 3s 后刷新（设置页「自动刷新」关闭时不跟随事件）
        viewModelScope.launch {
            combine(
                sseService.events.filter { it.kind in REFRESH_KINDS },
                appSettings.sseAutoRefresh,
            ) { _, autoRefresh -> autoRefresh }
                .filter { it }
                .debounce(3_000)
                .collect { refresh() }
        }
    }

    /** 按持久化顺序/显隐/卡片形态派生分区列表；尚未加载内容时返回 null。 */
    private fun derivedSections(): List<DashboardSection<out Any>>? {
        if (rawSections.isEmpty()) return null
        return rawSections
            .sortedBy { section ->
                val index = layout.order.indexOf(section.id)
                if (index < 0) Int.MAX_VALUE else index
            }
            .filter { section ->
                section.id !in layout.hidden &&
                    (section.id != ReadListsInProgressSectionId || layout.continuation)
            }
            .map { section ->
                val kind = layout.overrides[section.id] ?: section.kind
                if (kind == section.kind) section else section.copy(kind = kind)
            }
    }

    /** 分区卡片形态切换（仪表盘分区标题右侧菜单）。 */
    fun setSectionKind(sectionId: String, kind: DashboardCardKind?) {
        viewModelScope.launch {
            appSettings.setDashboardKindOverride(sectionId, kind?.name)
        }
    }

    /** 库范围选择（LibraryScopeMenu）；切换后按 libraryIds 重新拉取各分区（null/全部走原行为）。 */
    fun setLibraryScope(libraryId: String?) {
        if (_state.value.libraryScope == libraryId) return
        _state.update { it.copy(libraryScope = libraryId) }
        if (_state.value.hasSession) refresh()
    }

    private suspend fun load() {
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        val libraryIds = _state.value.libraryScope?.let { listOf(it) }
        try {
            val fetched = coroutineScope {
                val keepReading = async { bookRepository.keepReading(libraryIds = libraryIds) }
                val onDeck = async { bookRepository.onDeck(libraryIds = libraryIds) }
                val latestBooks = async { bookRepository.latest(libraryIds = libraryIds) }
                val latestSeries = async { seriesRepository.latest(libraryIds = libraryIds) }
                val updatedSeries = async { seriesRepository.recentlyUpdated(libraryIds = libraryIds) }
                DashboardFeeds(
                    keepReading.await(),
                    onDeck.await(),
                    latestBooks.await(),
                    latestSeries.await(),
                    updatedSeries.await(),
                )
            }
            val keepReading = fetched.keepReading.map { it.toUiBook() }
            val onDeck = fetched.onDeck.map { it.toUiBook() }
            val latestBooks = fetched.latestBooks.map { it.toUiBook() }
            val latestSeries = fetched.latestSeries.map { it.toUiSeries() }
            val updatedSeries = fetched.updatedSeries.map { it.toUiSeries() }
            val sections = listOfNotNull(
                if (keepReading.isNotEmpty()) DashboardSection("keepReading", "继续阅读", DashboardCardKind.Large, keepReading) else null,
                if (onDeck.isNotEmpty()) DashboardSection("onDeck", "待读", DashboardCardKind.Large, onDeck) else null,
                if (latestBooks.isNotEmpty()) DashboardSection("recentlyAddedBooks", "最近更新书籍", DashboardCardKind.Large, latestBooks) else null,
                if (latestSeries.isNotEmpty()) DashboardSection("recentlyAddedSeries", "最近更新系列", DashboardCardKind.Large, latestSeries) else null,
                if (updatedSeries.isNotEmpty()) DashboardSection("recentlyUpdatedSeries", "最近更新系列（内容）", DashboardCardKind.Large, updatedSeries) else null,
            )
            val collectionDtos = listRepository.collections()
            val readListDtos = listRepository.readLists()
            val libraries = runCatching { libraryRepository.libraries() }.getOrDefault(emptyList())
            val collections = collectionDtos.map { dto ->
                ListEntry(dto.id.hashCode(), dto.name, dto.seriesIds.size, hueFor(dto.id), false)
            }
            val readLists = readListDtos.map { dto ->
                ListEntry(dto.id.hashCode(), dto.name, dto.bookIds.size, hueFor(dto.id), false)
            }
            // 续读卡：逐列表拉书并找出第一本未读完（对齐 ReadListContinuation 语义）
            val continuations = coroutineScope {
                readListDtos.map { dto ->
                    async {
                        if (dto.bookIds.isEmpty()) return@async null
                        val books = detailRepository.readListBooks(dto.id).items.map { b -> b.toUiBook() }
                        if (books.isEmpty()) return@async null
                        val booksRead = books.count { it.progress == 1f }
                        if (booksRead >= books.size) return@async null
                        val nextIdx = books.indexOfFirst { it.progress == null || it.progress < 1f }
                            .let { if (it < 0) 0 else it }
                        val next = books[nextIdx]
                        ReadListContinuationUi(
                            readListId = dto.id.hashCode(),
                            readListName = dto.name,
                            nextBookIndex = nextIdx + 1,
                            nextBookTitle = next.title,
                            hue = hueFor(dto.id),
                            bookProgress = next.progress,
                            booksRead = booksRead,
                            bookCount = books.size,
                        )
                    }
                }.mapNotNull { it.await() }
            }
            val allSections = if (continuations.isNotEmpty()) {
                sections + DashboardSection(
                    ReadListsInProgressSectionId, "进行中的阅读列表",
                    DashboardCardKind.Horizontal, emptyList<Any>(),
                )
            } else {
                sections
            }
            rawSections = allSections
            _state.update {
                it.copy(
                    loading = false,
                    sections = derivedSections() ?: allSections,
                    continuations = continuations,
                    collections = collections,
                    readLists = readLists,
                    libraries = libraries,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "加载失败", sections = null) }
        }
    }

    /** 标记书籍已读/未读；成功后刷新分区内容（分区由服务端进度驱动，无法纯本地重算）。 */
    fun markBookRead(book: Book, read: Boolean) {
        val dtoId = detailRepository.bookDtoId(book.id) ?: return
        viewModelScope.launch {
            val ok = if (read) actionRepository.markBookRead(dtoId)
            else actionRepository.markBookUnread(dtoId)
            if (ok) refresh()
        }
    }

    /** 标记系列已读/未读；成功后刷新分区内容。 */
    fun markSeriesRead(series: Series, read: Boolean) {
        val dtoId = detailRepository.seriesDtoId(series.id) ?: return
        viewModelScope.launch {
            val ok = if (read) actionRepository.markSeriesRead(dtoId)
            else actionRepository.markSeriesUnread(dtoId)
            if (ok) refresh()
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

    private companion object {
        /** 触发仪表盘刷新的事件类别。 */
        val REFRESH_KINDS = setOf(
            SseEventKind.Book,
            SseEventKind.Series,
            SseEventKind.ReadProgress,
            SseEventKind.Collection,
            SseEventKind.ReadList,
            SseEventKind.Library,
        )
    }
}

private data class DashboardFeeds(
    val keepReading: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val onDeck: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val latestBooks: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val latestSeries: List<com.kmworks.kmreader.core.api.model.SeriesDto>,
    val updatedSeries: List<com.kmworks.kmreader.core.api.model.SeriesDto>,
)
