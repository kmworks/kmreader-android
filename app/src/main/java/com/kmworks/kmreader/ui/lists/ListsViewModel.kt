package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.data.SmartListRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import com.kmworks.kmreader.data.SampleData.ListEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lists screen state; 无会话时各列表为 null（UI 显示空态）。 */
data class ListsUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
    val smartLists: List<ListEntry>? = null,
    /** Thumbnails keyed by stable Int series/book id (see Mappers.stableId). */
    val thumbnails: Map<Int, String> = emptyMap(),
)

/** 触发整页刷新的 SSE 类别（对齐 DashboardViewModel 模式）。 */
private val REFRESH_KINDS = setOf(
    SseEventKind.Book,
    SseEventKind.Series,
    SseEventKind.ReadList,
    SseEventKind.Collection,
    SseEventKind.SmartList,
)

/** Real collections / read lists / smart lists（智能列表失败时整组隐藏，不报错）。 */
@HiltViewModel
class ListsViewModel @Inject constructor(
    private val listRepository: ListRepository,
    private val seriesRepository: SeriesRepository,
    private val smartListRepository: SmartListRepository,
    private val sseService: SseService,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ListsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _state.update {
                        it.copy(
                            hasSession = false,
                            loading = false,
                            collections = null,
                            readLists = null,
                            smartLists = null,
                            thumbnails = emptyMap(),
                        )
                    }
                } else {
                    load()
                }
            }
        }
        // SSE 驱动的防抖刷新（对齐 DashboardViewModel）
        viewModelScope.launch {
            sseService.events
                .filter { it.kind in REFRESH_KINDS }
                .debounce(3_000)
                .collect {
                    if (_state.value.hasSession) load()
                }
        }
    }

    private suspend fun load() {
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        try {
            val (collections, readLists, smartLists) = coroutineScope {
                val c = async { listRepository.collections() }
                val r = async { listRepository.readLists() }
                val s = async { smartListRepository.smartLists() }
                Triple(c.await(), r.await(), s.await())
            }
            val collectionEntries = collections.map { dto ->
                ListEntry(
                    id = dto.id.hashCode(),
                    title = dto.name,
                    count = dto.seriesIds.size,
                    hue = hueForKey(dto.id),
                    pinned = false,
                )
            }
            val readListEntries = readLists.map { dto ->
                ListEntry(
                    id = dto.id.hashCode(),
                    title = dto.name,
                    count = dto.bookIds.size,
                    hue = hueForKey(dto.id),
                    pinned = false,
                    modifiedLabel = "更新于 ${dto.lastModifiedDate.take(10)}",
                )
            }
            // DTO 无计数字段；目标类型/更新日期来自服务端
            val smartListEntries = smartLists.map { dto ->
                ListEntry(
                    id = dto.id.hashCode(),
                    title = dto.name,
                    count = 0,
                    hue = hueForKey(dto.id),
                    pinned = false,
                    targetLabel = if (dto.target == "SERIES") "系列" else "书籍",
                    modifiedLabel = "更新于 ${dto.lastModifiedDate.take(10)}",
                )
            }
            // Strip covers keyed by entry id: collection first series / readList first book /
            // smart list thumbnail.
            val thumbs = buildMap {
                collections.forEach { dto ->
                    dto.seriesIds.firstOrNull()?.let { firstId ->
                        seriesRepository.thumbnailUrl(firstId)?.let { url -> put(dto.id.hashCode(), url) }
                    }
                }
                readLists.forEach { dto ->
                    dto.bookIds.firstOrNull()?.let { firstId ->
                        smartListRepository.bookThumbnailUrl(firstId)?.let { url -> put(dto.id.hashCode(), url) }
                    }
                }
                smartLists.forEach { dto ->
                    smartListRepository.smartListThumbnailUrl(dto.id)?.let { url -> put(dto.id.hashCode(), url) }
                }
            }
            _state.update {
                it.copy(
                    loading = false,
                    collections = collectionEntries,
                    readLists = readListEntries,
                    smartLists = smartListEntries,
                    thumbnails = thumbs,
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    error = e.message ?: "加载失败",
                    collections = null,
                    readLists = null,
                    smartLists = null,
                )
            }
        }
    }
}

private fun hueForKey(key: String): Float = (key.hashCode().toLong().and(0x7fffffffL) % 360L).toFloat()
