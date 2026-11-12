package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 阅读列表详情状态；books 为 null 时界面回退假数据。 */
data class ReadListDetailUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val name: String? = null,
    val summary: String? = null,
    val ordered: Boolean? = null,
    val books: List<Book>? = null,
    val thumbnails: Map<Int, String> = emptyMap(),
)

/** 阅读列表详情数据：反查 DTO，再按 bookIds 顺序并发拉各本书（含真实 readProgress）。 */
@HiltViewModel
class ReadListDetailViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReadListDetailUiState())
    val uiState: StateFlow<ReadListDetailUiState> = _uiState.asStateFlow()

    private var loadedId: Int? = null

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.readListDtoId(stableId)
        if (clients == null || dtoId == null) {
            _uiState.update { it.copy(hasSession = clients != null, loading = false) }
            return
        }
        _uiState.update { it.copy(hasSession = true, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val readList = detailRepository.readListDetail(dtoId)
                if (readList == null) {
                    _uiState.update { it.copy(loading = false) }
                    return@launch
                }
                val bookDtos = detailRepository.readListBooks(readList.bookIds)
                val thumbnails = bookDtos.mapNotNull { dto ->
                    detailRepository.bookThumbnailUrl(dto.id)?.let { dto.id.hashCode() to it }
                }.toMap()
                _uiState.update {
                    it.copy(
                        loading = false,
                        name = readList.name,
                        summary = readList.summary,
                        ordered = readList.ordered,
                        books = bookDtos.map { dto -> dto.toUiBook() },
                        thumbnails = thumbnails,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }
}
