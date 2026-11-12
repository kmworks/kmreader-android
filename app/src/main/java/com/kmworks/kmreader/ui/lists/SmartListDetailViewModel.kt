package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.SmartListRepository
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.data.SmartListTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Smart list detail state; books/series == null keeps the sample demo UI. */
data class SmartListDetailUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** 注册表已解析出 DTO id（真实列表可编辑/删除）。 */
    val hasRealSmartList: Boolean = false,
    val name: String? = null,
    val summary: String? = null,
    val target: SmartListTarget? = null,
    val modifiedLabel: String? = null,
    val books: List<Book>? = null,
    val series: List<Series>? = null,
    /** Hero thumbnail (smart-list cover endpoint). */
    val heroThumbnail: String? = null,
    /** Thumbnails keyed by stable Int book/series id. */
    val thumbnails: Map<Int, String> = emptyMap(),
)

/** Real smart list detail + hits via the kmrs private endpoints. */
@HiltViewModel
class SmartListDetailViewModel @Inject constructor(
    private val smartListRepository: SmartListRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SmartListDetailUiState())
    val state = _state.asStateFlow()

    private var loadedId: Int? = null

    /** Load once per stable id; no session or unknown id keeps the demo fallback. */
    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        if (sessionManager.clients.value == null) {
            _state.update { it.copy(hasSession = false, loading = false) }
            return
        }
        val dtoId = smartListRepository.smartListDtoId(stableId)
        if (dtoId == null) {
            _state.update { it.copy(hasSession = true, loading = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(hasSession = true, loading = true, error = null, hasRealSmartList = true) }
            try {
                val detail = smartListRepository.smartListDetail(dtoId) ?: run {
                    _state.update { it.copy(loading = false) }
                    return@launch
                }
                val target = if (detail.target == "SERIES") SmartListTarget.Series else SmartListTarget.Book
                val (books, series) = coroutineScope {
                    val b = async {
                        if (target == SmartListTarget.Book) {
                            smartListRepository.smartListBooks(dtoId, detail.search).map { it.toUiBook() }
                        } else {
                            emptyList()
                        }
                    }
                    val s = async {
                        if (target == SmartListTarget.Series) {
                            smartListRepository.smartListSeries(dtoId, detail.search).map { it.toUiSeries() }
                        } else {
                            emptyList()
                        }
                    }
                    b.await() to s.await()
                }
                val thumbs = buildMap {
                    books.forEach { book ->
                        DetailIdRegistry.bookDtoId(book.id)?.let { dtoBookId ->
                            smartListRepository.bookThumbnailUrl(dtoBookId)?.let { put(book.id, it) }
                        }
                    }
                    series.forEach { item ->
                        DetailIdRegistry.seriesDtoId(item.id)?.let { dtoSeriesId ->
                            smartListRepository.seriesThumbnailUrl(dtoSeriesId)?.let { put(item.id, it) }
                        }
                    }
                }
                _state.update {
                    it.copy(
                        loading = false,
                        name = detail.name,
                        summary = detail.summary.ifBlank { null },
                        target = target,
                        modifiedLabel = "更新于 ${detail.lastModifiedDate.take(10)}",
                        books = books,
                        series = series,
                        heroThumbnail = smartListRepository.smartListThumbnailUrl(dtoId),
                        thumbnails = thumbs,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    /** 更新名称/简介/条件；成功后重拉详情与命中。 */
    fun save(name: String, summary: String, search: kotlinx.serialization.json.JsonObject?, onDone: (Boolean) -> Unit) {
        val stableId = loadedId ?: return onDone(false)
        val dtoId = smartListRepository.smartListDtoId(stableId) ?: return onDone(false)
        viewModelScope.launch {
            val ok = smartListRepository.updateSmartList(dtoId, name, summary, search)
            if (ok) {
                loadedId = null
                load(stableId)
            }
            onDone(ok)
        }
    }

    /** 删除当前智能列表；成功后 UI 返回上一层。 */
    fun delete(onDeleted: () -> Unit) {
        val dtoId = loadedId?.let { smartListRepository.smartListDtoId(it) } ?: return
        viewModelScope.launch {
            if (smartListRepository.deleteSmartList(dtoId)) onDeleted()
        }
    }
}
