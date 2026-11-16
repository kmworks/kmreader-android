package com.kmworks.kmreader.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.Paged
import com.kmworks.kmreader.core.data.SearchRepository
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Section detail ("更多") state; items are Book/Series, paged with bottom load-more. */
data class DashboardSectionDetailUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val items: List<Any> = emptyList(),
    val hasMore: Boolean = false,
    /** 库范围菜单数据：服务器真实库列表。 */
    val libraries: List<LibraryDto> = emptyList(),
    /** 库范围选择（null = 全部）；分页查询经 libraryIds 参数过滤。 */
    val libraryScope: String? = null,
)

/**
 * Re-fetches a dashboard section feed with page/size so the "更多" page can load more,
 * aligned with DashboardSectionDetailView (pageSize 50).
 */
@HiltViewModel
class DashboardSectionDetailViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val libraryRepository: LibraryRepository,
    private val serverProfileStore: ServerProfileStore,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardSectionDetailUiState())
    val state = _state.asStateFlow()

    private var sectionId: String? = null
    private var page = 0
    private var loadingMore = false
    /** 代际号：库范围切换/重新绑定后，丢弃在途旧查询的结果。 */
    private var generation = 0

    /** Bind to a section; only the first call per section id triggers a fresh load. */
    fun start(section: DashboardSection<out Any>) {
        if (sectionId == section.id) return
        sectionId = section.id
        page = 0
        loadingMore = false
        generation++
        _state.value = DashboardSectionDetailUiState(loading = true)
        viewModelScope.launch { loadPage(0) }
        viewModelScope.launch {
            val libraries = runCatching { libraryRepository.libraries() }.getOrDefault(emptyList())
            _state.update { it.copy(libraries = libraries) }
        }
    }

    /** 库范围切换（LibraryScopeMenu）：重置分页并按 libraryIds 重新拉取（null = 全部）。 */
    fun setLibraryScope(libraryId: String?) {
        if (_state.value.libraryScope == libraryId) return
        generation++
        page = 0
        loadingMore = false
        _state.update {
            it.copy(
                libraryScope = libraryId,
                items = emptyList(),
                hasMore = false,
                error = null,
                loading = true,
                loadingMore = false,
            )
        }
        viewModelScope.launch { loadPage(0) }
    }

    /** 触底加载下一页（并发保护 + 无更多直接返回）。 */
    fun loadMore() {
        if (loadingMore || !_state.value.hasMore) return
        loadingMore = true
        viewModelScope.launch { loadPage(page + 1) }
    }

    private suspend fun loadPage(target: Int) {
        val id = sectionId ?: return
        val gen = generation
        _state.update { it.copy(loadingMore = true) }
        try {
            val result = fetch(id, target, PAGE_SIZE)
            if (gen != generation) return // 代际已切换，丢弃旧结果
            if (result == null) {
                _state.update {
                    it.copy(loading = false, loadingMore = false, error = "该分区暂不支持分页")
                }
                return
            }
            page = target
            _state.update { st ->
                val existing = if (target == 0) emptyList() else st.items
                val appended = result.items.filterNot { item ->
                    existing.any { current -> sameId(current, item) }
                }
                st.copy(
                    loading = false,
                    loadingMore = false,
                    error = null,
                    items = existing + appended,
                    hasMore = result.hasMore,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen != generation) return // 代际已切换，不覆盖新查询的状态
            _state.update {
                it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败")
            }
        } finally {
            if (gen == generation) loadingMore = false
        }
    }

    private suspend fun fetch(sectionId: String, page: Int, size: Int): Paged<Any>? {
        val libraryIds = _state.value.libraryScope?.let { listOf(it) }
        return when (sectionId) {
            "keepReading" -> searchRepository.keepReadingPage(
                page, size, libraryIds,
                readDateAfter = serverProfileStore.recentReadingCutoff(),
            ).eraseType()
            "onDeck" -> searchRepository.onDeckPage(page, size, libraryIds).eraseType()
            "recentlyReleasedBooks" -> searchRepository.recentlyReleasedBooksPage(page, size, libraryIds).eraseType()
            "recentlyAddedBooks" -> searchRepository.latestBooksPage(page, size, libraryIds).eraseType()
            "recentlyAddedSeries" -> searchRepository.latestSeriesPage(page, size, libraryIds).eraseType()
            "recentlyUpdatedSeries" -> searchRepository.recentlyUpdatedSeriesPage(page, size, libraryIds).eraseType()
            "recentlyReadBooks" -> searchRepository.recentlyReadBooksPage(page, size, libraryIds).eraseType()
            else -> null
        }
    }

    private fun <T> Paged<T>.eraseType(): Paged<Any> = Paged(items.toList().map { it as Any }, hasMore)

    private fun sameId(a: Any, b: Any): Boolean = when {
        a is Book && b is Book -> a.id == b.id
        a is Series && b is Series -> a.id == b.id
        else -> false
    }

    private companion object {
        /** 原作 DashboardSectionDetailView pageSize。 */
        const val PAGE_SIZE = 50
    }
}
