package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.network.SessionManager
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
    /** 收藏封面（服务端缩略图端点）。 */
    val heroThumbnail: String? = null,
)

/** 收藏详情数据：反查收藏 DTO，成员经 GET api/v1/collections/{id}/series 拉取。 */
@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    private var loadedId: Int? = null

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.collectionDtoId(stableId)
        if (clients == null || dtoId == null) {
            _uiState.update { it.copy(hasSession = clients != null, loading = false) }
            return
        }
        _uiState.update { it.copy(hasSession = true, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val collection = detailRepository.collectionDetail(dtoId)
                if (collection == null) {
                    _uiState.update { it.copy(loading = false) }
                    return@launch
                }
                val seriesDtos = detailRepository.collectionSeries(dtoId)
                _uiState.update {
                    it.copy(
                        loading = false,
                        name = collection.name,
                        ordered = collection.ordered,
                        series = seriesDtos.map { dto -> dto.toUiSeries() },
                        heroThumbnail = detailRepository.collectionThumbnailUrl(dtoId),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }
}
