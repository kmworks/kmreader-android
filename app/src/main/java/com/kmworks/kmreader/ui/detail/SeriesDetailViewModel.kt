package com.kmworks.kmreader.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
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
import javax.inject.Inject
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

/** 系列详情状态；series/books 为 null 时界面回退传入的假数据。 */
data class SeriesDetailUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val series: Series? = null,
    val books: List<Book>? = null,
    val seriesThumbnail: String? = null,
    /** 归属收藏（真实数据；Mappers.toListEntry 映射，点击经 onOpenCollection 上抛）。 */
    val collections: List<ListEntry> = emptyList(),
    /** komf 能力：hasFeature(Komf) && 管理员 && 有 DTO 映射；为 false 时菜单三项不渲染。 */
    val komfAvailable: Boolean = false,
    /** 当前系列的 libraryId（DTO 直出，komf 调用需要）。 */
    val libraryId: String? = null,
    val komfSearching: Boolean = false,
    val komfSearchFailed: Boolean = false,
    val komfSearchResults: List<KomfSearchResultDto> = emptyList(),
    /** 有 komf 任务正在轮询（sheet 提交状态/菜单禁用用）。 */
    val komfJobRunning: Boolean = false,
    /** kmrs Trackers 能力（无会话或 stock 服务器为 false）。 */
    val trackerSupported: Boolean = false,
    /** Tracker 已绑定条目（仅 trackerSupported 且有值时非空）。 */
    val trackerLinks: List<TrackerLink> = emptyList(),
    /** 元数据编辑的初始值（DTO 直出；无会话/演示数据时为 null）。 */
    val editable: SeriesEditValues? = null,
)

/** 系列详情数据：按注册表反查 DTO id，拉单系列与其书籍列表。 */
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SeriesDetailUiState())
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

    /** komf 完成/失败/超时等一次性通知（详情页 snackbar 消费）。 */
    private val _komfEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val komfEvents: SharedFlow<String> = _komfEvents.asSharedFlow()

    private val _collections = MutableStateFlow<List<ListEntry>?>(null)
    val collections: StateFlow<List<ListEntry>?> = _collections.asStateFlow()

    private val _readLists = MutableStateFlow<List<ListEntry>?>(null)
    val readLists: StateFlow<List<ListEntry>?> = _readLists.asStateFlow()

    private val _downloaded = MutableStateFlow<Map<Int, Boolean>>(emptyMap())

    /** 各书籍（stableId）是否已整本下载到本地。 */
    val downloaded: StateFlow<Map<Int, Boolean>> = _downloaded.asStateFlow()

    private var loadedId: Int? = null
    private var sseJob: Job? = null

    /** 当前详情的 DTO id（komf 调用与任务完成后刷新用）。 */
    private var dtoId: String? = null
    private var komfSearchJob: Job? = null

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.seriesDtoId(stableId)
        this.dtoId = dtoId
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
        viewModelScope.launch {
            try {
                val (seriesDto, bookDtos) = coroutineScope {
                    val s = async { detailRepository.seriesDetail(dtoId) }
                    val b = async { detailRepository.seriesBooks(dtoId) }
                    s.await() to b.await()
                }
                // komf 能力门控：kmrs 特性 + 管理员（对齐 KomfIntegrationStore）
                val komfAvailable = serverProfileStore.hasFeature(ServerFeature.Komf) &&
                    (configStore.config.first()?.isAdmin == true)
                // 归属收藏：失败返回空，不阻塞详情主体
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
                            )
                        },
                        libraryId = seriesDto?.libraryId,
                    )
                }
                refreshDownloaded()
                autoDownloadUnreadIfEnabled()
                refreshTrackerLinks()
                observeReadProgressEvents()
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    /** 按需加载收藏列表（「加入收藏」选择器用）。 */
    fun ensureCollectionsLoaded() {
        if (_collections.value != null) return
        viewModelScope.launch {
            _collections.value = listRepository.collections().map { dto ->
                ListEntry(
                    id = dto.id.hashCode(),
                    title = dto.name,
                    count = dto.seriesIds.size,
                    hue = hueFor(dto.id),
                    pinned = false,
                )
            }
        }
    }

    /** 标记整个系列为已读；返回 false 表示无 DTO 映射（演示数据，界面本地处理）。 */
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

    /** 把当前系列加入收藏；返回 false 表示无 DTO 映射。 */
    fun addToCollection(entry: ListEntry): Boolean {
        val seriesDtoId = loadedId?.let { detailRepository.seriesDtoId(it) } ?: return false
        val collectionDtoId = detailRepository.collectionDtoId(entry.id) ?: return false
        viewModelScope.launch {
            actionRepository.addSeriesToCollection(collectionDtoId, seriesDtoId)
        }
        return true
    }

    /** 按需加载阅读列表（「加入阅读列表」选择器用）。 */
    fun ensureReadListsLoaded() {
        if (_readLists.value != null) return
        viewModelScope.launch {
            _readLists.value = listRepository.readLists().map { dto ->
                ListEntry(
                    id = dto.id.hashCode(),
                    title = dto.name,
                    count = dto.bookIds.size,
                    hue = hueFor(dto.id),
                    pinned = false,
                )
            }
        }
    }

    /** 批量标记选中书籍已读/未读；返回 false 表示缺少真实 DTO 映射。 */
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

    /** 批量下载选中书籍；返回 false 表示没有可下载的真实书籍。 */
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

    /** 批量加入阅读列表；返回 false 表示选择器条目或书籍缺少真实 DTO 映射。 */
    fun addBooksToReadList(entry: ListEntry, stableIds: Set<Int>): Boolean {
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return false
        val bookDtoIds = stableIds.mapNotNull { detailRepository.bookDtoId(it) }
        if (bookDtoIds.isEmpty()) return false
        viewModelScope.launch {
            actionRepository.addBooksToReadList(readListDtoId, bookDtoIds)
        }
        return true
    }

    /** 删除选中书籍文件；成功后从详情本地列表移除并刷新下载状态。 */
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
            pairs.forEach { (_, dtoId) -> ok = actionRepository.deleteBook(dtoId) && ok }
            if (ok) {
                val removedIds = pairs.map { it.first.id }.toSet()
                _uiState.update { state ->
                    state.copy(books = state.books?.filterNot { it.id in removedIds })
                }
                refreshDownloaded()
            }
            onDone(ok)
        }
    }

    /** 下载本系列全部书籍；返回 false 表示无会话/无 DTO 映射（演示数据）。 */
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

    /** 刷新各书籍的本地已下载状态（文件扫描）。 */
    /** 自动下载未读：策略开启后，打开系列详情时把未读且未下载的书排队。 */
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

    /** Tracker 绑定刷新：仅 kmrs Trackers 能力时拉取；失败保持旧值（对齐 TrackerSeriesView reload）。 */
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

    /** komf 搜索（300ms 防抖；对齐 KomfIdentifySheet 的 task(id:) 防抖语义）。 */
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

    /** 提交 komf identify；成功后 sheet 关闭、任务进入轮询（对齐 submit()）。 */
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

    /** 匹配 komf（对齐 KomfMenu.match）。 */
    fun komfMatch() {
        val dtoId = dtoId ?: return
        val libraryId = _uiState.value.libraryId ?: return
        if (_uiState.value.komfJobRunning) return
        viewModelScope.launch {
            val jobId = komfRepository.match(libraryId, dtoId)
            if (jobId != null) {
                _komfEvents.emit("komf 匹配任务已提交")
                trackKomfJob(jobId)
            } else {
                _komfEvents.emit("komf 匹配提交失败")
            }
        }
    }

    /** 重置 komf（非任务：成功直接刷新详情，对齐 resetSeries）。 */
    fun komfReset() {
        val dtoId = dtoId ?: return
        val libraryId = _uiState.value.libraryId ?: return
        viewModelScope.launch {
            if (komfRepository.reset(libraryId, dtoId)) {
                _komfEvents.emit("已重置 komf 元数据")
                reloadDetail()
            } else {
                _komfEvents.emit("komf 重置失败")
            }
        }
    }

    /** 轮询任务到终态；完成/超时尽力刷新详情（对齐 KomfJobTracker）。 */
    private fun trackKomfJob(jobId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(komfJobRunning = true) }
            komfRepository.trackJob(jobId).collect { state ->
                when (state) {
                    KomfJobState.Running -> Unit
                    KomfJobState.Completed -> {
                        _komfEvents.emit("komf 任务已完成")
                        reloadDetail()
                    }
                    is KomfJobState.Failed -> {
                        val suffix = state.message?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
                        _komfEvents.emit("komf 任务失败$suffix")
                    }
                    KomfJobState.TimedOut -> {
                        _komfEvents.emit("komf 任务仍在执行，已停止跟踪")
                        reloadDetail()
                    }
                }
            }
            _uiState.update { it.copy(komfJobRunning = false) }
        }
    }

    /** 任务完成后重拉详情（重置 loadedId 后走原加载路径）。 */
    private fun reloadDetail() {
        val stableId = loadedId ?: return
        loadedId = null
        load(stableId)
    }

    /**
     * 保存系列元数据；dto 为 null 表示没有改动（直接关闭，不发请求）。
     * 保存成功后重拉详情，让 hero/summary/chips 立刻反映服务端数据。
     */
    fun saveMetadata(dto: SeriesMetadataUpdateDto?, onDone: (Boolean) -> Unit) {
        val seriesDtoId = dtoId
        if (dto == null) {
            onDone(true)
            return
        }
        if (seriesDtoId == null) {
            _komfEvents.tryEmit("保存失败：无服务器数据")
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.updateSeriesMetadata(seriesDtoId, dto)
            if (ok) {
                reloadDetail()
            } else {
                _komfEvents.tryEmit("保存失败，请稍后重试")
            }
            onDone(ok)
        }
    }

    /**
     * 阅读进度事件：读完书籍的 tracker 推进发生在服务端（对齐原作，客户端不推送）；
     * 订阅 SSE ReadProgress/Book 事件，防抖后重拉书籍列表与 tracker 绑定以反映服务端推进。
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

    /** 仅重拉书籍列表（SSE 触发；详情主体不变）。封面由 stableId 注册表解析。 */
    private fun reloadBooks() {
        val dtoId = dtoId ?: return
        viewModelScope.launch {
            val bookDtos = detailRepository.seriesBooks(dtoId)
            val books = bookDtos.map { it.toUiBook() }
            _uiState.update { it.copy(books = books) }
            refreshDownloaded()
        }
    }
}
