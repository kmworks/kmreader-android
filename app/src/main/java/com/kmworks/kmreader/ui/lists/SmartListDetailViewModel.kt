package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.SmartListRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.data.SmartListTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Smart list detail state; books/series == null keeps the sample demo UI. */
data class SmartListDetailUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** DTO id resolved through the registry (a real list can be edited/deleted). */
    val hasRealSmartList: Boolean = false,
    val name: String? = null,
    val summary: String? = null,
    val target: SmartListTarget? = null,
    val modifiedLabel: String? = null,
    val createdLabel: String? = null,
    val books: List<Book>? = null,
    val series: List<Series>? = null,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** Hero thumbnail (smart-list cover endpoint). */
    val heroThumbnail: String? = null,
)

/** Real smart list detail + hits via the kmrs private endpoints. */
@HiltViewModel
class SmartListDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smartListRepository: SmartListRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SmartListDetailUiState())
    val state = _state.asStateFlow()

    private var loadedId: Int? = null
    /** Entry the current state content belongs to (content resets when the entry changes;
     *  a forced refresh of the same entry keeps it). */
    private var contentId: Int? = null
    private var currentDtoId: String? = null
    private var currentSearch: JsonObject? = null
    private var currentTarget: SmartListTarget? = null
    private var nextPage = 0
    private var loadJob: kotlinx.coroutines.Job? = null

    /** Load once per stable id; no session or unknown id keeps the demo fallback. */
    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val hasSession = sessionManager.clients.value != null
        loadJob?.cancel()
        if (contentId != stableId) {
            _state.value = SmartListDetailUiState(hasSession = hasSession)
        }
        if (!hasSession) {
            _state.update { it.copy(hasSession = false, loading = false) }
            return
        }
        val dtoId = smartListRepository.smartListDtoId(stableId)
        if (dtoId == null) {
            _state.update { it.copy(hasSession = true, loading = false) }
            return
        }
        currentDtoId = dtoId
        currentSearch = null
        currentTarget = null
        nextPage = 0
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    hasSession = true,
                    loading = true,
                    error = null,
                    hasRealSmartList = true,
                    books = null,
                    series = null,
                    hasMore = false,
                )
            }
            try {
                val detail = smartListRepository.smartListDetail(dtoId) ?: run {
                    _state.update { it.copy(loading = false) }
                    return@launch
                }
                val target = if (detail.target == "SERIES") SmartListTarget.Series else SmartListTarget.Book
                currentSearch = detail.search
                currentTarget = target
                val booksPage = if (target == SmartListTarget.Book) {
                    smartListRepository.smartListBooks(dtoId, detail.search, page = 0, size = PAGE_SIZE)
                } else {
                    null
                }
                val seriesPage = if (target == SmartListTarget.Series) {
                    smartListRepository.smartListSeries(dtoId, detail.search, page = 0, size = PAGE_SIZE)
                } else {
                    null
                }
                nextPage = 1
                _state.update {
                    it.copy(
                        loading = false,
                        name = detail.name,
                        summary = detail.summary.ifBlank { null },
                        target = target,
                        modifiedLabel = context.getString(
                            R.string.lists_modified_at,
                            detail.lastModifiedDate.take(10),
                        ),
                        createdLabel = detail.createdDate.takeIf { it.isNotBlank() }
                            ?.let { context.getString(R.string.lists_created_at, it.take(10)) },
                        books = booksPage?.items.orEmpty().map { dto -> dto.toUiBook() },
                        series = seriesPage?.items.orEmpty().map { dto -> dto.toUiSeries() },
                        hasMore = booksPage?.hasMore ?: seriesPage?.hasMore ?: false,
                        heroThumbnail = smartListRepository.smartListThumbnailUrl(dtoId),
                    )
                }
                contentId = stableId
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: context.getString(R.string.common_load_failed))
                }
            }
        }
    }

    fun loadMore() {
        val state = _state.value
        val dtoId = currentDtoId ?: return
        val target = currentTarget ?: return
        if (state.loading || state.loadingMore || !state.hasMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                if (target == SmartListTarget.Book) {
                    val page = smartListRepository.smartListBooks(dtoId, currentSearch, page = nextPage, size = PAGE_SIZE)
                    nextPage += 1
                    _state.update { state ->
                        val seen = state.books.orEmpty().map { it.id }.toMutableSet()
                        val appended = page.items.map { dto -> dto.toUiBook() }.filter { seen.add(it.id) }
                        state.copy(
                            loadingMore = false,
                            books = state.books.orEmpty() + appended,
                            hasMore = page.hasMore && appended.isNotEmpty(),
                        )
                    }
                } else {
                    val page = smartListRepository.smartListSeries(dtoId, currentSearch, page = nextPage, size = PAGE_SIZE)
                    nextPage += 1
                    _state.update { state ->
                        val seen = state.series.orEmpty().map { it.id }.toMutableSet()
                        val appended = page.items.map { dto -> dto.toUiSeries() }.filter { seen.add(it.id) }
                        state.copy(
                            loadingMore = false,
                            series = state.series.orEmpty() + appended,
                            hasMore = page.hasMore && appended.isNotEmpty(),
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loadingMore = false, error = e.message ?: context.getString(R.string.common_load_failed))
                }
            }
        }
    }

    /** Update name/summary/conditions; on success the detail and hits are reloaded. */
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

    /** Delete the current smart list; on success the UI navigates back. */
    fun delete(onDeleted: () -> Unit) {
        val dtoId = loadedId?.let { smartListRepository.smartListDtoId(it) } ?: return
        viewModelScope.launch {
            if (smartListRepository.deleteSmartList(dtoId)) onDeleted()
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
