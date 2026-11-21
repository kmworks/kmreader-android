package com.kmworks.kmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.AdjacentBooksResolver
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ReaderAdjacentBooks
import com.kmworks.kmreader.core.data.ReaderReadListContext
import com.kmworks.kmreader.core.data.ReaderRepository
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.reading.ReadListReadingService
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.settings.DivinaSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.ReaderPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Three-state page list (no fake-data fallback; failures go to the error state with retry). */
enum class ReaderPagesStatus { Loading, Ready, Error }

data class ReaderUiState(
    val status: ReaderPagesStatus = ReaderPagesStatus.Loading,
    /** The book these pages belong to (the reader stays open across books, so the screen keeps them apart). */
    val bookId: Int? = null,
    /** Real pages (width/height from PageDto). */
    val pages: List<ReaderPage> = emptyList(),
    /** Komga id of the current book (null when unmapped; pages unavailable). */
    val bookDtoId: String? = null,
    /**
     * Server page number to resume from (mirrors the original DivinaReaderView.loadBook:
     * incognito or completed → null to start from page 0; otherwise readProgress.page).
     */
    val initialPage: Int? = null,
    /** The book was in progress when the session started (mirrors the original wasInProgress: the gate's minimum distance relaxes to 1). */
    val wasInProgress: Boolean = false,
    /** Series metadata direction (raw: LEFT_TO_RIGHT/RIGHT_TO_LEFT/VERTICAL/WEBTOON, mirrors SeriesDto.metadata.readingDirection). */
    val seriesDirection: String? = null,
    /** Series unread count (`SeriesDto.booksUnreadCount`) for the end-page "N unread left" line. */
    val unreadCount: Int? = null,
    /** The read list this session continues in (null when it reads in series order). */
    val readListId: String? = null,
    /** Name of [readListId], shown instead of the series title on the end page. */
    val readListName: String? = null,
    /** Adjacent books for the end page (mirrors the original resolveAdjacentBooks result). */
    val adjacent: ReaderAdjacentBooks = ReaderAdjacentBooks(),
)

/**
 * DIVINA reader: fetches the real page list per Book, progress-resume semantics, solo-page set,
 * and progress reporting (mirrors the gating semantics of the original ReaderProgressRecordingGate).
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val readerRepository: ReaderRepository,
    private val actionRepository: ActionRepository,
    private val detailRepository: DetailRepository,
    private val settingsStore: DivinaSettingsStore,
    private val appSettings: AppSettingsStore,
    private val readListReadingService: ReadListReadingService,
    private val adjacentBooksResolver: AdjacentBooksResolver,
    /** Application scope: the exit flush must survive this view model being cleared with the page. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** Solo-page set (server page numbers, persisted per book; mirrors the original soloPagesByBookId). */
    private val _soloPages = MutableStateFlow<Set<Int>>(emptySet())
    val soloPages: StateFlow<Set<Int>> = _soloPages.asStateFlow()

    private var loadedBookId: Int? = null

    /** The read list this session continues in, resolved once per session so continuing keeps the order. */
    private var sessionReadListContext: ReaderReadListContext? = null

    /** Incognito reading: skips progress reporting. */
    var incognito: Boolean = false

    /** Page-turn threshold for recording progress (mirrors the original AppConfig.progressRecordingThreshold, takes effect immediately from the settings page). */
    @Volatile
    private var progressThreshold: Int = ReaderProgressRecordingGate.THRESHOLD

    init {
        viewModelScope.launch {
            appSettings.progressRecordingThreshold.collect { progressThreshold = it }
        }
    }

    /**
     * Loads the page list + progress resume + series direction; idempotent for repeated calls with the same book.
     *
     * [readListStableId] is the read list the book was opened from (null from everywhere else): it wins over
     * the implicit "a read list the user is reading contains this book" resolution, the same precedence the
     * original ReaderPresentationManager applies.
     *
     * [startAtEnd] lands on the last page instead of the resume page. No caller uses it for the backward
     * seam any more (that one resumes the previous book's own progress, so walking back does not record the
     * book as finished); it is kept for entries that really mean "the end of this book".
     */
    fun load(book: Book, readListStableId: Int? = null, startAtEnd: Boolean = false) {
        if (loadedBookId == book.id) return
        loadedBookId = book.id
        // Reset progress-gate state on book change (mirrors the original rebuilding the gate on every loadBook)
        sessionStartPage = null
        hasRecordedProgress = false
        lastReportedPage = 0
        val dtoId = readerRepository.bookDtoId(book.id)
        if (dtoId == null) {
            _uiState.value = ReaderUiState(status = ReaderPagesStatus.Error, bookId = book.id)
            return
        }
        _uiState.value = ReaderUiState(
            status = ReaderPagesStatus.Loading,
            bookId = book.id,
            bookDtoId = dtoId,
        )
        viewModelScope.launch {
            // Solo-page set (persisted locally; failures do not block)
            runCatching { settingsStore.soloPages(dtoId).first() }
                .onSuccess { _soloPages.value = it }

            val detail = runCatching { detailRepository.bookDetail(dtoId) }.getOrNull()
            val progress = detail?.readProgress
            val series = runCatching {
                detail?.seriesId?.let { detailRepository.seriesDetail(it) }
            }.getOrNull()
            val seriesDirection = series?.metadata?.readingDirection
            val unreadCount = series?.booksUnreadCount

            val dtos = readerRepository.pages(dtoId)
            _uiState.value = when {
                dtos.isEmpty() -> ReaderUiState(
                    status = ReaderPagesStatus.Error,
                    bookId = book.id,
                    bookDtoId = dtoId,
                )
                else -> {
                    // Mirrors the original loadBook: incognito or completed → start from page 0; startAtEnd
                    // (only explicit end-of-book entries now) lands on the last page instead
                    val resumePage = when {
                        startAtEnd -> dtos.lastOrNull()?.number
                        incognito || progress?.completed == true -> null
                        else -> progress?.page
                    }
                    // Gate anchor = resume page (otherwise the pager jumping from 0 to the resume page would count as "turned N pages" and record immediately)
                    sessionStartPage = resumePage ?: dtos.firstOrNull()?.number
                    ReaderUiState(
                        status = ReaderPagesStatus.Ready,
                        bookId = book.id,
                        pages = dtos.map { dto ->
                            ReaderPage(
                                id = dto.number,
                                number = dto.number,
                                // PageDto width/height are nullable: unknown treated as portrait (does not affect pairing logic)
                                width = dto.width ?: 0,
                                height = dto.height ?: 0,
                            )
                        },
                        bookDtoId = dtoId,
                        initialPage = resumePage,
                        wasInProgress = progress != null && progress.completed != true,
                        seriesDirection = seriesDirection,
                        unreadCount = unreadCount,
                    )
                }
            }
            // Deferred, like the original: neither the read list context nor the sibling lookup delays the
            // first render, and both are consumed only by the end page and the continuation.
            if (_uiState.value.status == ReaderPagesStatus.Ready) {
                val context = resolveReadListContext(dtoId, readListStableId)
                if (_uiState.value.bookDtoId != dtoId) return@launch
                if (context != null) {
                    _uiState.value = _uiState.value.copy(
                        readListId = context.readListId,
                        readListName = context.name,
                    )
                    readListReadingService.record(context.readListId, dtoId)
                }
                resolveAdjacent(dtoId, context?.readListId)
            }
        }
    }

    /**
     * The read list this session continues in: the explicit entry point wins, otherwise a read list the
     * user is reading that contains this book (mirrors ReaderPresentationManager.resolvedReadListContext).
     * Null when the continuation setting is off or neither applies.
     */
    private suspend fun resolveReadListContext(
        dtoId: String,
        readListStableId: Int?,
    ): ReaderReadListContext? {
        sessionReadListContext?.let { return it }
        val context = adjacentBooksResolver.readListContext(dtoId, readListStableId)
        sessionReadListContext = context
        return context
    }

    /**
     * Resolves the previous/next sibling books (mirrors the original resolveAdjacentBooks). Only the end
     * page consumes the result, so a failure is recorded on the state instead of failing the load.
     */
    private suspend fun resolveAdjacent(dtoId: String, readListId: String?) {
        val adjacent = adjacentBooksResolver.resolve(dtoId, readListId)
        // A different book was opened while the lookup was in flight
        if (_uiState.value.bookDtoId != dtoId) return
        _uiState.value = _uiState.value.copy(adjacent = adjacent)
    }

    /** Retry the sibling lookup after it failed (offline during the end page). */
    fun retryAdjacent() {
        val state = _uiState.value
        val dtoId = state.bookDtoId ?: return
        _uiState.value = state.copy(adjacent = ReaderAdjacentBooks())
        viewModelScope.launch { resolveAdjacent(dtoId, state.readListId) }
    }

    /**
     * Re-reads the series unread count. Called when the end page becomes visible: a completing progress
     * write only moves the count once the server has stored it (mirrors the original
     * scheduleRemainingUnreadCountSettleRefresh), so the first copy of the count can be the
     * pre-completion one.
     */
    fun refreshUnreadCount() {
        val dtoId = _uiState.value.bookDtoId ?: return
        viewModelScope.launch {
            val detail = runCatching { detailRepository.bookDetail(dtoId) }.getOrNull()
            val series = runCatching {
                detail?.seriesId?.let { detailRepository.seriesDetail(it) }
            }.getOrNull() ?: return@launch
            if (_uiState.value.bookDtoId != dtoId) return@launch
            _uiState.value = _uiState.value.copy(unreadCount = series.booksUnreadCount)
        }
    }

    /**
     * Opens a sibling book in the same reader session (mirrors the original openAdjacentBook: progress is
     * flushed, the reader options are preserved, and only the loaded book changes). The read cursor lands
     * on that book's own resume page; [startAtEnd] stays available for explicit end-of-book entries.
     */
    fun openBook(book: Book, startAtEnd: Boolean = false) {
        if (book.id == loadedBookId && !startAtEnd) return
        loadedBookId = null
        load(book, startAtEnd = startAtEnd)
    }

    fun retry(book: Book) {
        loadedBookId = null
        load(book)
    }

    // ---- Solo pages (mirrors the original toggleSoloPage: only portrait pages can be solo, persisted per book) ----
    fun toggleSoloPage(pageNumber: Int) {
        val dtoId = _uiState.value.bookDtoId ?: return
        val next = _soloPages.value.toMutableSet().apply {
            if (!remove(pageNumber)) add(pageNumber)
        }.toSet()
        _soloPages.value = next
        viewModelScope.launch {
            runCatching { settingsStore.setSoloPages(dtoId, next) }
                .onFailure { AppLog.w("ReaderVM", "soloPages persist failed: ${it.message}") }
        }
    }

    /** Full-page image URL (null without a real session; UI shows a placeholder). */
    fun pageUrl(pageNumber: Int): String? =
        _uiState.value.bookDtoId?.let { readerRepository.pageUrl(it, pageNumber) }

    /** Page thumbnail URL (for the thumbnail strip). */
    fun pageThumbnailUrl(pageNumber: Int): String? =
        _uiState.value.bookDtoId?.let { readerRepository.pageThumbnailUrl(it, pageNumber) }

    /**
     * First page of a neighbouring book: the end page and the continuation gesture open one of them
     * straight away, so its image is warmed before the seam is crossed.
     */
    fun firstPageUrl(bookId: Int): String? =
        readerRepository.bookDtoId(bookId)?.let { readerRepository.pageUrl(it, 1) }

    // ---- Progress reporting (mirrors the original ReaderProgressRecordingGate) ----
    /** Session start page (first observed settled page). */
    private var sessionStartPage: Int? = null
    private var hasRecordedProgress = false
    private var lastReportedPage: Int = 0

    /** Reports reading progress (last page of the settled unit). Skipped when incognito; de-duplicated reporting only after passing the gate. */
    fun reportProgress(page: Int) {
        if (incognito) return
        val state = _uiState.value
        val dtoId = state.bookDtoId ?: return
        val pageCount = state.pages.size
        if (pageCount <= 0) return
        if (sessionStartPage == null) sessionStartPage = page

        val completed = page >= pageCount
        if (!admitsRecording(page, completed, pageCount)) return
        hasRecordedProgress = true
        if (page == lastReportedPage) return
        lastReportedPage = page
        viewModelScope.launch {
            runCatching {
                actionRepository.updateBookReadProgress(
                    bookDtoId = dtoId,
                    page = page,
                    completed = completed,
                )
            }.onFailure { AppLog.w("ReaderVM", "progress PUT failed page=$page: ${it.message}") }
        }
    }

    /**
     * Flush on exit (mirrors the original flushProgress()=enqueue(from:current,to:nil)): the same recording gate
     * applies, so opening a book and closing it without moving records nothing. When the gate passes, the write
     * survives this view model being cleared and the screens showing this book are told about it, so they do not
     * wait for SSE to catch up.
     */
    fun flushProgress(currentPage: Int) {
        if (incognito) return
        val state = _uiState.value
        val dtoId = state.bookDtoId ?: return
        val pageCount = state.pages.size
        if (pageCount <= 0 || currentPage <= 0) return
        if (currentPage == lastReportedPage) return
        if (sessionStartPage == null) sessionStartPage = currentPage
        val completed = currentPage >= pageCount
        // The exit flush is gated like every other report: a session that never crossed the threshold must not
        // turn an unread (or finished) book into an in-progress one just because the reader was opened.
        if (!admitsRecording(currentPage, completed, pageCount)) return
        hasRecordedProgress = true
        lastReportedPage = currentPage
        val progress = (currentPage.toFloat() / pageCount).coerceIn(0f, 1f)
        appScope.launch {
            runCatching { actionRepository.updateBookReadProgress(dtoId, currentPage, completed) }
                .onSuccess { ok ->
                    if (ok) actionRepository.announceBookProgress(dtoId, completed, progress)
                }
                .onFailure { AppLog.w("ReaderVM", "final progress PUT failed page=$currentPage: ${it.message}") }
        }
    }

    /**
     * Mirrors the original ReaderProgressRecordingGate: 0 disables the gate, completion or an earlier recording
     * always passes, the required distance is clamped to the page count, and a book already in progress only needs
     * one page.
     */
    private fun admitsRecording(page: Int, completed: Boolean, pageCount: Int): Boolean {
        if (completed || hasRecordedProgress) return true
        val start = sessionStartPage ?: return false
        val requiredDistance = ReaderProgressRecordingGate
            .requiredDistance(progressThreshold, pageCount, _uiState.value.wasInProgress)
        return requiredDistance <= 0 || abs(page - start) >= requiredDistance
    }
}

/** Progress-gate constants and math (mirrors the original ReaderProgressRecordingGate + AppConfig.progressRecordingThreshold). */
object ReaderProgressRecordingGate {
    const val THRESHOLD = 3

    /**
     * How far the reader has to move before a report may be recorded; 0 means the gate is off. The threshold never
     * exceeds pageCount-1 so a short book can still be finished, and a book already in progress only needs one page.
     * A non-positive page count (positions unavailable) leaves the threshold unclamped.
     */
    fun requiredDistance(threshold: Int, pageCount: Int, wasInProgress: Boolean): Int {
        if (threshold <= 0) return 0
        var required = threshold
        if (pageCount > 0) required = min(required, pageCount - 1)
        if (wasInProgress) required = min(required, 1)
        return required
    }
}

