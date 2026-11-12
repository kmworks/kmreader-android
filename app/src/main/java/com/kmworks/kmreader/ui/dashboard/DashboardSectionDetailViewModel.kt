package com.kmworks.kmreader.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.Paged
import com.kmworks.kmreader.core.data.SearchRepository
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
)

/**
 * Re-fetches a dashboard section feed with page/size so the "更多" page can load more,
 * aligned with DashboardSectionDetailView (pageSize 50).
 */
@HiltViewModel
class DashboardSectionDetailViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardSectionDetailUiState())
    val state = _state.asStateFlow()

    private var sectionId: String? = null
    private var page = 0
    private var loadingMore = false

    /** Bind to a section; only the first call per section id triggers a fresh load. */
    fun start(section: DashboardSection<out Any>) {
        if (sectionId == section.id) return
        sectionId = section.id
        page = 0
        loadingMore = false
        _state.value = DashboardSectionDetailUiState(loading = true)
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
        _state.update { it.copy(loadingMore = true) }
        try {
            val result = fetch(id, target, PAGE_SIZE)
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
            _state.update {
                it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败")
            }
        } finally {
            loadingMore = false
        }
    }

    private suspend fun fetch(sectionId: String, page: Int, size: Int): Paged<Any>? =
        when (sectionId) {
            "keepReading" -> searchRepository.keepReadingPage(page, size).eraseType()
            "onDeck" -> searchRepository.onDeckPage(page, size).eraseType()
            "recentlyAddedBooks" -> searchRepository.latestBooksPage(page, size).eraseType()
            "recentlyAddedSeries" -> searchRepository.latestSeriesPage(page, size).eraseType()
            "recentlyUpdatedSeries" -> searchRepository.recentlyUpdatedSeriesPage(page, size).eraseType()
            else -> null
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
