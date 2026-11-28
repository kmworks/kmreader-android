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
import com.kmworks.kmreader.core.data.Paged
import com.kmworks.kmreader.core.data.PagedFeed
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
    /** Stored sharing; the editor prefills it and only admins can change it. */
    val visibility: String = SmartListVisibilityPrivate,
    val sharedWithUserIds: List<String> = emptyList(),
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
    /**
     * Hit feeds (a smart list is books or series, never both). Both read the current DTO and search document,
     * so a reload of the same list keeps the pages already on screen (see [PagedFeed]).
     */
    private val booksFeed = PagedFeed(
        pageSize = PAGE_SIZE,
        identity = { book: Book -> book.id },
        fetch = { page, size ->
            currentDtoId?.let { dtoId ->
                smartListRepository.smartListBooks(dtoId, currentSearch, page = page, size = size)
                    ?.let { result ->
                        Paged(result.items.map { dto -> dto.toUiBook() }, result.hasMore, result.totalElements)
                    }
            }
        },
    )
    private val seriesFeed = PagedFeed(
        pageSize = PAGE_SIZE,
        identity = { series: Series -> series.id },
        fetch = { page, size ->
            currentDtoId?.let { dtoId ->
                smartListRepository.smartListSeries(dtoId, currentSearch, page = page, size = size)
                    ?.let { result ->
                        Paged(result.items.map { dto -> dto.toUiSeries() }, result.hasMore, result.totalElements)
                    }
            }
        },
    )
    /** Bumped on every whole-list load: a load-more that lands on top of one must not overwrite it. */
    private var generation = 0
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
        val gen = ++generation
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
        // Captured before the reset below: a reload of the same list re-reads the pages it had, while a new list
        // starts from page 0.
        val previousTarget = currentTarget
        val previousPages = if (previousTarget == SmartListTarget.Series) seriesFeed.page else booksFeed.page
        val sameContent = contentId == stableId
        currentDtoId = dtoId
        currentSearch = null
        currentTarget = null
        lastLoadAtMs = SystemClock.elapsedRealtime()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    hasSession = true,
                    loading = true,
                    error = null,
                    hasRealSmartList = true,
                    // Only a different smart list drops its rows; a reload of the same one keeps them on screen.
                    books = if (sameContent) it.books else null,
                    series = if (sameContent) it.series else null,
                    hasMore = if (sameContent) it.hasMore else false,
                    search = null,
                    visibility = SmartListVisibilityPrivate,
                    sharedWithUserIds = emptyList(),
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
                // Same smart list and same target is the same query; switching either starts from page 0.
                val sameQuery = sameContent && previousTarget == target
                val keepPages = if (sameQuery) previousPages else 0
                val books = if (target == SmartListTarget.Book) {
                    val current = if (sameQuery) _state.value.books.orEmpty() else emptyList()
                    booksFeed.reload(current, keepPages, newQuery = !sameQuery)
                } else {
                    emptyList()
                }
                val series = if (target == SmartListTarget.Series) {
                    val current = if (sameQuery) _state.value.series.orEmpty() else emptyList()
                    seriesFeed.reload(current, keepPages, newQuery = !sameQuery)
                } else {
                    emptyList()
                }
                if (gen != generation) return@launch
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
                        books = books,
                        series = series,
                        hasMore = if (target == SmartListTarget.Book) booksFeed.hasMore else seriesFeed.hasMore,
                        search = detail.search,
                        visibility = detail.visibility,
                        sharedWithUserIds = detail.sharedWithUserIds,
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
        val gen = generation
        val state = _state.value
        if (currentDtoId == null) return
        val target = currentTarget ?: return
        if (state.loading || state.loadingMore || !state.hasMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                if (target == SmartListTarget.Book) {
                    val books = booksFeed.append(state.books.orEmpty())
                    if (gen != generation) { // a newer load owns the window; only clear this page's spinner
                        _state.update { it.copy(loadingMore = false) }
                        return@launch
                    }
                    _state.update { st ->
                        // A whole-list reload started meanwhile and owns the window: this page must not
                        // overwrite it (the reload's deeper page count already covers it).
                        if (st.loading) {
                            st.copy(loadingMore = false)
                        } else {
                            st.copy(loadingMore = false, books = books, hasMore = booksFeed.hasMore)
                        }
                    }
                } else {
                    val series = seriesFeed.append(state.series.orEmpty())
                    if (gen != generation) { // a newer load owns the window; only clear this page's spinner
                        _state.update { it.copy(loadingMore = false) }
                        return@launch
                    }
                    _state.update { st ->
                        // A whole-list reload started meanwhile and owns the window: this page must not
                        // overwrite it (the reload's deeper page count already covers it).
                        if (st.loading) {
                            st.copy(loadingMore = false)
                        } else {
                            st.copy(loadingMore = false, series = series, hasMore = seriesFeed.hasMore)
                        }
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
    fun save(
        name: String,
        summary: String,
        search: kotlinx.serialization.json.JsonObject?,
        visibility: String?,
        sharedWithUserIds: List<String>?,
        onDone: (Boolean) -> Unit,
    ) {
        val stableId = loadedId ?: return onDone(false)
        val dtoId = smartListRepository.smartListDtoId(stableId) ?: return onDone(false)
        viewModelScope.launch {
            onDone(
                smartListRepository.updateSmartList(
                    dtoId = dtoId,
                    name = name,
                    summary = summary,
                    search = search,
                    visibility = visibility,
                    sharedWithUserIds = sharedWithUserIds,
                ),
            )
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
