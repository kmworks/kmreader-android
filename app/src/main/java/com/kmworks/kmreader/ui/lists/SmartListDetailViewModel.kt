package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.os.SystemClock
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ContentChange
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.ContentChangeKind
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.data.SmartListRepository
import com.kmworks.kmreader.core.data.PatchOutcome
import com.kmworks.kmreader.core.data.observeChanges
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
    /** Server search document, shown as a filter summary in the editor (null while loading). */
    val search: kotlinx.serialization.json.JsonObject? = null,
    /** Hero thumbnail (smart-list cover endpoint). */
    val heroThumbnail: String? = null,
)

/** Real smart list detail + hits via the kmrs private endpoints. */
@HiltViewModel
class SmartListDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smartListRepository: SmartListRepository,
    private val sessionManager: SessionManager,
    private val contentChangeBus: ContentChangeBus,
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
    private var contentJob: kotlinx.coroutines.Job? = null

    /** [SystemClock.elapsedRealtime] of the last read, used to skip a redundant settle re-read. */
    private var lastLoadAtMs = 0L

    init {
        observeContentChanges()
    }

    /**
     * Writes issued here or on another screen: read state of the hits on screen is patched in place,
     * and changes this page cannot represent (its own metadata/conditions) re-read the list.
     */
    private fun observeContentChanges() {
        contentJob = viewModelScope.launch {
            contentChangeBus.observeChanges(
                patch = { patchContentChange(it) },
                reload = { reloadDetail() },
                settle = { settlePage() },
            )
        }
    }

    private fun reloadDetail() {
        val stableId = loadedId ?: return
        loadedId = null
        load(stableId)
    }

    /**
     * A smart list is either books or series, never both. On a series list a book write leaves the series rows'
     * aggregates stale (the server computes them out of the books), so it settles; a books list has nothing above
     * the row that could go stale.
     */
    private fun patchContentChange(change: ContentChange): PatchOutcome {
        if (!patchInPlace(change)) return PatchOutcome.Declined
        val aggregatesMoved = change is ContentChange.Books &&
            (change.kind == ContentChangeKind.ReadStateChanged ||
                change.kind == ContentChangeKind.ProgressChanged)
        return if (aggregatesMoved && currentTarget == SmartListTarget.Series) {
            PatchOutcome.PendingSettle
        } else {
            PatchOutcome.Patched
        }
    }

    /**
     * Re-reads the first page of a series smart list and merges it by id. Merging keeps what a reload would drop:
     * the pages already loaded and the scroll position.
     *
     * Skipped when the page was read just now; that read already saw the post-write numbers.
     */
    private fun settlePage() {
        if (SystemClock.elapsedRealtime() - lastLoadAtMs < SETTLE_SUPPRESS_MILLIS) return
        if (currentTarget != SmartListTarget.Series) return
        val dtoId = currentDtoId ?: return
        val search = currentSearch ?: return
        viewModelScope.launch {
            val page = runCatching {
                smartListRepository.smartListSeries(dtoId, search, page = 0, size = PAGE_SIZE)
            }.getOrNull() ?: return@launch
            val byId = page.items.mapNotNull { dto ->
                DetailIdRegistry.seriesStableId(dto.id)?.let { it to dto.toUiSeries() }
            }.toMap()
            lastLoadAtMs = SystemClock.elapsedRealtime()
            _state.update { st ->
                st.copy(series = st.series?.map { row -> byId[row.id] ?: row })
            }
        }
    }

    private fun patchInPlace(change: ContentChange): Boolean = when (change) {
        is ContentChange.Books -> {
            val ids = change.dtoIds.mapNotNull { DetailIdRegistry.bookStableId(it) }.toSet()
            when (change.kind) {
                ContentChangeKind.ReadStateChanged -> patchBooksRead(ids, change.read ?: return true)
                ContentChangeKind.ProgressChanged -> patchBooksProgress(ids, change.progress ?: return true)
                // Renamed, deleted or re-analyzed: only the server knows the new rows.
                else -> !showsAnyBook(ids)
            }
        }
        is ContentChange.Series -> {
            val ids = change.dtoIds.mapNotNull { DetailIdRegistry.seriesStableId(it) }.toSet()
            when (change.kind) {
                ContentChangeKind.ReadStateChanged -> patchSeriesRead(ids, change.read ?: return true)
                else -> !showsAnySeries(ids)
            }
        }
        is ContentChange.SmartLists -> {
            // This list's own name/conditions: re-read. Its deletion closes the page,
            // so there is nothing left to refresh.
            val dtoId = currentDtoId
            dtoId == null || dtoId !in change.dtoIds || change.kind == ContentChangeKind.Deleted
        }
        // Collections and read lists are not this page's subject.
        else -> true
    }

    private fun showsAnyBook(ids: Set<Int>): Boolean =
        ids.isNotEmpty() && _state.value.books.orEmpty().any { it.id in ids }

    private fun showsAnySeries(ids: Set<Int>): Boolean =
        ids.isNotEmpty() && _state.value.series.orEmpty().any { it.id in ids }

    private fun patchBooksRead(ids: Set<Int>, read: Boolean): Boolean {
        if (!showsAnyBook(ids)) return true
        _state.update { state ->
            state.copy(
                books = state.books.orEmpty().map { book ->
                    if (book.id in ids) book.copy(progress = if (read) 1f else null) else book
                },
            )
        }
        return true
    }

    private fun patchBooksProgress(ids: Set<Int>, progress: Float): Boolean {
        if (!showsAnyBook(ids)) return true
        _state.update { state ->
            state.copy(
                books = state.books.orEmpty().map { book ->
                    if (book.id in ids) book.copy(progress = progress) else book
                },
            )
        }
        return true
    }

    private fun patchSeriesRead(ids: Set<Int>, read: Boolean): Boolean {
        if (!showsAnySeries(ids)) return true
        _state.update { state ->
            state.copy(
                series = state.series.orEmpty().map { series ->
                    if (series.id in ids) {
                        series.copy(
                            progress = if (read) 1f else null,
                            unreadCount = if (read) 0 else series.bookCount,
                        )
                    } else {
                        series
                    }
                },
            )
        }
        return true
    }

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
        lastLoadAtMs = SystemClock.elapsedRealtime()
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
                    search = null,
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
                        search = detail.search,
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

    /** Update name/summary/conditions; the write signal re-reads the detail and hits. */
    fun save(name: String, summary: String, search: kotlinx.serialization.json.JsonObject?, onDone: (Boolean) -> Unit) {
        val stableId = loadedId ?: return onDone(false)
        val dtoId = smartListRepository.smartListDtoId(stableId) ?: return onDone(false)
        viewModelScope.launch {
            onDone(smartListRepository.updateSmartList(dtoId, name, summary, search))
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

        /**
         * A settle re-read within this window of the last read is redundant: that read already saw the state the
         * settle was waiting for.
         */
        const val SETTLE_SUPPRESS_MILLIS = 3_000L
    }
}
