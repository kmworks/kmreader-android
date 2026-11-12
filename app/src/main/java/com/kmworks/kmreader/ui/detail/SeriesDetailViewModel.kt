package com.kmworks.kmreader.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SeriesDetailUiState())
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

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
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }
}
