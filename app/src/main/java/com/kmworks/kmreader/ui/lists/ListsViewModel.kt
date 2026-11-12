package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.SmartListRepository
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import com.kmworks.kmreader.data.SampleData.ListEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    val loadingMore: Boolean = false,
    val error: String? = null,
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
    val smartLists: List<ListEntry>? = null,
    val collectionsHasMore: Boolean = true,
    val readListsHasMore: Boolean = true,
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
    private val smartListRepository: SmartListRepository,
    private val sseService: SseService,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ListsUiState())
    val state = _state.asStateFlow()

    private var collectionsPage = 0
    private var readListsPage = 0
    private var loadingMoreCollections = false
    private var loadingMoreReadLists = false

    init {
        viewModelScope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    collectionsPage = 0
                    readListsPage = 0
                    _state.update {
                        it.copy(
                            hasSession = false,
                            loading = false,
                            loadingMore = false,
                            collections = null,
                            readLists = null,
                            smartLists = null,
                            collectionsHasMore = true,
                            readListsHasMore = true,
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
        collectionsPage = 0
        readListsPage = 0
        try {
            val (collectionPage, readListPage, smartLists) = coroutineScope {
                val c = async { listRepository.collectionsPage(page = 0, size = PAGE_SIZE) }
                val r = async { listRepository.readListsPage(page = 0, size = PAGE_SIZE) }
                val s = async { smartListRepository.smartLists() }
                Triple(c.await(), r.await(), s.await())
            }
            val collectionEntries = collectionPage.items.map { dto ->
                dto.toListEntry()
            }
            val readListEntries = readListPage.items.map { dto ->
                dto.toListEntry().copy(
                    modifiedLabel = "更新于 ${dto.lastModifiedDate.take(10)}",
                )
            }
            val smartListEntries = smartLists.map { dto ->
                dto.toListEntry().copy(
                    targetLabel = if (dto.target == "SERIES") "系列" else "书籍",
                    modifiedLabel = "更新于 ${dto.lastModifiedDate.take(10)}",
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    collections = collectionEntries,
                    readLists = readListEntries,
                    smartLists = smartListEntries,
                    collectionsHasMore = collectionPage.hasMore,
                    readListsHasMore = readListPage.hasMore,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = e.message ?: "加载失败",
                    collections = null,
                    readLists = null,
                    smartLists = null,
                )
            }
        }
    }

    /** 收藏 strip 触底加载下一页。 */
    fun loadMoreCollections() {
        val state = _state.value
        if (loadingMoreCollections || state.loading || !state.collectionsHasMore) return
        loadingMoreCollections = true
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val nextPage = collectionsPage + 1
                val result = listRepository.collectionsPage(page = nextPage, size = PAGE_SIZE)
                collectionsPage = nextPage
                _state.update { st ->
                    st.copy(
                        loadingMore = false,
                        collections = st.collections.orEmpty() +
                            result.items.map { it.toListEntry() }.filterNot { entry ->
                                st.collections.orEmpty().any { it.id == entry.id }
                            },
                        collectionsHasMore = result.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, error = e.message ?: "加载更多失败") }
            } finally {
                loadingMoreCollections = false
            }
        }
    }

    /** 阅读列表 strip 触底加载下一页。 */
    fun loadMoreReadLists() {
        val state = _state.value
        if (loadingMoreReadLists || state.loading || !state.readListsHasMore) return
        loadingMoreReadLists = true
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val nextPage = readListsPage + 1
                val result = listRepository.readListsPage(page = nextPage, size = PAGE_SIZE)
                readListsPage = nextPage
                _state.update { st ->
                    st.copy(
                        loadingMore = false,
                        readLists = st.readLists.orEmpty() +
                            result.items.map {
                                it.toListEntry().copy(
                                    modifiedLabel = "更新于 ${it.lastModifiedDate.take(10)}",
                                )
                            }.filterNot { entry ->
                                st.readLists.orEmpty().any { it.id == entry.id }
                            },
                        readListsHasMore = result.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, error = e.message ?: "加载更多失败") }
            } finally {
                loadingMoreReadLists = false
            }
        }
    }

    private companion object {
        /** 收藏/阅读列表每页 20 条（原作 ListsBrowseView 的 pageSize）。 */
        const val PAGE_SIZE = 20
    }
}
