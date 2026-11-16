package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 收藏详情状态；series 为 null 时界面回退假数据。 */
data class CollectionDetailUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val name: String? = null,
    val ordered: Boolean? = null,
    val series: List<Series>? = null,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** 收藏封面（服务端缩略图端点）。 */
    val heroThumbnail: String? = null,
    val createdDate: String? = null,
    val lastModifiedDate: String? = null,
)

/** 收藏详情数据：反查收藏 DTO，成员经 GET api/v1/collections/{id}/series 拉取。 */
@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    private val _readLists = MutableStateFlow<List<ListEntry>?>(null)
    val readLists: StateFlow<List<ListEntry>?> = _readLists.asStateFlow()

    private var loadedId: Int? = null
    private var currentDtoId: String? = null
    private var nextPage = 0

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.collectionDtoId(stableId)
        if (clients == null || dtoId == null) {
            _uiState.update { it.copy(hasSession = clients != null, loading = false) }
            return
        }
        currentDtoId = dtoId
        nextPage = 0
        _uiState.update { it.copy(hasSession = true, loading = true, error = null, series = null, hasMore = false) }
        viewModelScope.launch {
            try {
                val collection = detailRepository.collectionDetail(dtoId)
                if (collection == null) {
                    _uiState.update { it.copy(loading = false) }
                    return@launch
                }
                val page = detailRepository.collectionSeries(dtoId, page = 0, size = PAGE_SIZE)
                nextPage = 1
                _uiState.update {
                    it.copy(
                        loading = false,
                        name = collection.name,
                        ordered = collection.ordered,
                        series = page.items.map { dto -> dto.toUiSeries() },
                        hasMore = page.hasMore,
                        heroThumbnail = detailRepository.collectionThumbnailUrl(dtoId),
                        createdDate = collection.createdDate,
                        lastModifiedDate = collection.lastModifiedDate,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val dtoId = currentDtoId ?: return
        if (state.loading || state.loadingMore || !state.hasMore) return
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = detailRepository.collectionSeries(dtoId, page = nextPage, size = PAGE_SIZE)
                nextPage += 1
                _uiState.update { state ->
                    val seen = state.series.orEmpty().map { it.id }.toMutableSet()
                    val appended = page.items.map { dto -> dto.toUiSeries() }.filter { seen.add(it.id) }
                    state.copy(
                        loadingMore = false,
                        series = state.series.orEmpty() + appended,
                        hasMore = page.hasMore && appended.isNotEmpty(),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loadingMore = false, error = e.message ?: "加载失败") }
            }
        }
    }

    /** 批量标记所选系列已读/未读。 */
    fun markSeriesRead(stableIds: Set<Int>, read: Boolean, onDone: (Boolean) -> Unit) {
        val seriesDtoIds = stableIds.mapNotNull { detailRepository.seriesDtoId(it) }
        if (seriesDtoIds.size != stableIds.size) return onDone(false)
        viewModelScope.launch {
            var ok = true
            seriesDtoIds.forEach { dtoId ->
                ok = (if (read) actionRepository.markSeriesRead(dtoId) else actionRepository.markSeriesUnread(dtoId)) && ok
            }
            if (ok) {
                _uiState.update { state ->
                    state.copy(
                        series = state.series.orEmpty().map { series ->
                            if (series.id in stableIds) {
                                series.copy(
                                    progress = if (read) 1f else 0f,
                                    unreadCount = if (read) 0 else series.bookCount,
                                )
                            } else series
                        },
                    )
                }
            }
            onDone(ok)
        }
    }

    /** 批量下载所选系列：先拉真实书籍列表，再逐本进入下载队列。 */
    fun downloadSeries(stableIds: Set<Int>, onDone: (Boolean) -> Unit) {
        if (sessionManager.clients.value == null) return onDone(false)
        val seriesDtoIds = stableIds.mapNotNull { detailRepository.seriesDtoId(it) }
        if (seriesDtoIds.size != stableIds.size) return onDone(false)
        viewModelScope.launch {
            var enqueued = false
            var failed = false
            seriesDtoIds.forEach { seriesDtoId ->
                try {
                    detailRepository.seriesBooks(seriesDtoId).forEach { book ->
                        downloadRepository.enqueueBook(book.id, book.name, book.media.pagesCount)
                        enqueued = true
                    }
                } catch (_: Exception) {
                    failed = true
                }
            }
            onDone(enqueued && !failed)
        }
    }

    /** 加载真实阅读列表供批量加入选择。 */
    fun ensureReadListsLoaded() {
        if (_readLists.value != null || sessionManager.clients.value == null) return
        viewModelScope.launch {
            try {
                _readLists.value = listRepository.readLists().map { it.toListEntry() }
            } catch (_: Exception) {
                _readLists.value = emptyList()
            }
        }
    }

    /** 将所选系列内的书籍加入阅读列表（批量 append 到原顺序末尾）。 */
    fun addSeriesToReadList(entry: ListEntry, stableIds: Set<Int>, onDone: (Boolean) -> Unit) {
        if (sessionManager.clients.value == null) return onDone(false)
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return onDone(false)
        val seriesDtoIds = stableIds.mapNotNull { detailRepository.seriesDtoId(it) }
        if (seriesDtoIds.size != stableIds.size) return onDone(false)
        viewModelScope.launch {
            try {
                val bookIds = seriesDtoIds
                    .flatMap { detailRepository.seriesBooks(it) }
                    .map { it.id }
                    .distinct()
                val ok = bookIds.isNotEmpty() && actionRepository.addBooksToReadList(readListDtoId, bookIds)
                onDone(ok)
            } catch (_: Exception) {
                onDone(false)
            }
        }
    }

    /** 从收藏移除所选系列（stableId 集合）；返回 false 表示无 DTO 映射。 */
    fun removeSeries(stableIds: Set<Int>, onDone: (Boolean) -> Unit) {
        val collectionDtoId = currentDtoId ?: return onDone(false)
        val seriesDtoIds = stableIds.mapNotNull { detailRepository.seriesDtoId(it) }
        if (seriesDtoIds.size != stableIds.size) return onDone(false)
        viewModelScope.launch {
            val ok = actionRepository.removeSeriesFromCollection(collectionDtoId, seriesDtoIds)
            if (ok) {
                _uiState.update { state ->
                    state.copy(series = state.series.orEmpty().filterNot { it.id in stableIds })
                }
            }
            onDone(ok)
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
