package com.kmworks.kmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ReaderRepository
import com.kmworks.kmreader.core.logging.AppLog
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
    /** Application scope: the exit flush must survive this view model being cleared with the page. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** Solo-page set (server page numbers, persisted per book; mirrors the original soloPagesByBookId). */
    private val _soloPages = MutableStateFlow<Set<Int>>(emptySet())
    val soloPages: StateFlow<Set<Int>> = _soloPages.asStateFlow()

    private var loadedBookId: Int? = null

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

    /** Loads the page list + progress resume + series direction; idempotent for repeated calls with the same book. */
    fun load(book: Book) {
        if (loadedBookId == book.id) return
        loadedBookId = book.id
        // Reset progress-gate state on book change (mirrors the original rebuilding the gate on every loadBook)
        sessionStartPage = null
        hasRecordedProgress = false
        lastReportedPage = 0
        val dtoId = readerRepository.bookDtoId(book.id)
        if (dtoId == null) {
            _uiState.value = ReaderUiState(status = ReaderPagesStatus.Error)
            return
        }
        _uiState.value = ReaderUiState(status = ReaderPagesStatus.Loading, bookDtoId = dtoId)
        viewModelScope.launch {
            // Solo-page set (persisted locally; failures do not block)
            runCatching { settingsStore.soloPages(dtoId).first() }
                .onSuccess { _soloPages.value = it }

            val progress = runCatching { detailRepository.bookDetail(dtoId)?.readProgress }.getOrNull()
            val seriesDirection = runCatching {
                detailRepository.bookDetail(dtoId)
                    ?.seriesId
                    ?.let { detailRepository.seriesDetail(it) }
                    ?.metadata
                    ?.readingDirection
            }.getOrNull()

            val dtos = readerRepository.pages(dtoId)
            _uiState.value = when {
                dtos.isEmpty() -> ReaderUiState(status = ReaderPagesStatus.Error, bookDtoId = dtoId)
                else -> {
                    // Mirrors the original loadBook: incognito or completed → start from page 0
                    val resumePage = if (incognito || progress?.completed == true) null else progress?.page
                    // Gate anchor = resume page (otherwise the pager jumping from 0 to the resume page would count as "turned N pages" and record immediately)
                    sessionStartPage = resumePage ?: dtos.firstOrNull()?.number
                    ReaderUiState(
                        status = ReaderPagesStatus.Ready,
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
                    )
                }
            }
        }
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

