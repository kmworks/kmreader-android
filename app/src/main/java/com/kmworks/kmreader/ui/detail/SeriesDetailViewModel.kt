package com.kmworks.kmreader.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val thumbnails: Map<Int, String> = emptyMap(),
)

/** 系列详情数据：按注册表反查 DTO id，拉单系列与其书籍列表。 */
@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SeriesDetailUiState())
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

    private val _collections = MutableStateFlow<List<ListEntry>?>(null)
    val collections: StateFlow<List<ListEntry>?> = _collections.asStateFlow()

    private val _downloaded = MutableStateFlow<Map<Int, Boolean>>(emptyMap())

    /** 各书籍（stableId）是否已整本下载到本地。 */
    val downloaded: StateFlow<Map<Int, Boolean>> = _downloaded.asStateFlow()

    private var loadedId: Int? = null

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (clients == null || dtoId == null) {
            _uiState.update { it.copy(hasSession = clients != null, loading = false) }
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
                val books = bookDtos.map { it.toUiBook() }
                val thumbnails = bookDtos.mapNotNull { dto ->
                    detailRepository.bookThumbnailUrl(dto.id)?.let { dto.id.hashCode() to it }
                }.toMap()
                _uiState.update {
                    it.copy(
                        loading = false,
                        series = seriesDto?.toUiSeries(),
                        books = books,
                        seriesThumbnail = detailRepository.seriesThumbnailUrl(dtoId),
                        thumbnails = thumbnails,
                    )
                }
                refreshDownloaded()
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
}
