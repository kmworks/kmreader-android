package com.kmworks.kmreader.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.pdf.PdfDocument
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.pdf.PdfAndroidxLoader
import com.kmworks.kmreader.core.pdf.PdfRepository
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class PdfReaderUiState(
    val hasSession: Boolean = false,
    val bookDtoId: String? = null,
    val loading: Boolean = false,
    /** Download progress; null when contentLength is unknown (indeterminate). */
    val downloadProgress: Float? = null,
    val error: Boolean = false,
    val ready: Boolean = false,
    val pageCount: Int = 0,
    val initialPage: Int = 1,
    /** androidx.pdf document instance; rendering and the text layer are managed by PdfView. */
    val document: PdfDocument? = null,
)

@HiltViewModel
class PdfReaderViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val pdfRepository: PdfRepository,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val sessionManager: SessionManager,
    private val appSettings: AppSettingsStore,
    /** Application scope: the exit flush must survive this view model being cleared with the page. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    /** Page-turn threshold for recording progress (mirrors the original AppConfig.progressRecordingThreshold, takes effect immediately from the settings page). */
    @Volatile
    private var progressThreshold: Int = ReaderProgressRecordingGate.THRESHOLD

    init {
        viewModelScope.launch {
            appSettings.progressRecordingThreshold.collect { progressThreshold = it }
        }
    }

    private var lastBook: Book? = null
    private var document: PdfDocument? = null
    private var lastReportedPage = 0

    // Progress-reporting gate (mirrors the original PdfReaderViewModel's ReaderProgressRecordingGate):
    // an unread book must move at least 3 pages to report; a book already in progress reports after 1 page; completion/already-recorded always passes.
    private var sessionStartPage: Int? = null
    private var wasInProgress: Boolean = false
    private var hasRecorded: Boolean = false

    /** Incognito reading: skips progress reporting. */
    var incognito: Boolean = false

    /** Entry point: enters the error state when there is no real mapping (no demo fallback). */
    fun load(book: Book) {
        if (lastBook?.id == book.id) return
        lastBook = book
        val dtoId = pdfRepository.bookDtoId(book.id)
        if (dtoId == null) {
            _uiState.update { it.copy(hasSession = false, loading = false, error = true) }
            return
        }
        _uiState.update {
            it.copy(hasSession = true, bookDtoId = dtoId, loading = true, error = false, ready = false)
        }
        viewModelScope.launch {
            val file =
                pdfRepository.downloadPdf(dtoId) { copied, total ->
                    _uiState.update { s ->
                        s.copy(downloadProgress = if (total > 0) copied / total.toFloat() else null)
                    }
                }
            if (file == null) {
                _uiState.update { it.copy(loading = false, error = true) }
                return@launch
            }
            val doc = PdfAndroidxLoader(context).open(file)
            if (doc == null) {
                _uiState.update { it.copy(loading = false, error = true) }
                return@launch
            }
            document = doc
            val progress = detailRepository.bookDetail(dtoId)?.readProgress
            wasInProgress = progress != null && !progress.completed
            hasRecorded = false
            val initialPage =
                when {
                    progress == null || progress.completed -> 1
                    else -> progress.page.coerceIn(1, doc.pageCount)
                }
            // Gate anchor = resume page (otherwise the page jump on open would cross the threshold and record immediately)
            sessionStartPage = initialPage
            _uiState.update {
                it.copy(
                    loading = false,
                    ready = true,
                    pageCount = doc.pageCount,
                    initialPage = initialPage,
                    document = doc,
                )
            }
        }
    }

    /** Full-text search: returns the list of matching page numbers (1-based, ascending). */
    suspend fun search(query: String): List<Int> {
        val doc = document ?: return emptyList()
        return runCatching {
                val results = doc.searchDocument(query, 0 until doc.pageCount)
                (0 until results.size()).map { results.keyAt(it) + 1 }
            }
            .getOrDefault(emptyList())
    }

    /** Reports the current page (de-duplicated; last page marks completion). Skipped when incognito. */
    fun reportProgress(page: Int) {
        if (incognito) return
        val dtoId = _uiState.value.bookDtoId ?: return
        if (page == lastReportedPage) return
        val pageCount = _uiState.value.pageCount
        if (pageCount <= 0) return
        if (sessionStartPage == null) sessionStartPage = page
        val completed = page >= pageCount
        if (!admitsRecording(page, completed, pageCount)) return
        lastReportedPage = page
        viewModelScope.launch {
            val ok = actionRepository.updateBookReadProgress(dtoId, page, completed)
            if (ok) hasRecorded = true
        }
    }

    /**
     * Exit flush: reports the page reached even when the session never crossed the recording threshold, then tells
     * the screens showing this book so they patch the new progress instead of waiting for SSE.
     */
    fun flushProgress(page: Int) {
        if (incognito) return
        val dtoId = _uiState.value.bookDtoId ?: return
        val pageCount = _uiState.value.pageCount
        if (pageCount <= 0 || page <= 0) return
        if (page == lastReportedPage) return
        lastReportedPage = page
        val completed = page >= pageCount
        val progress = (page.toFloat() / pageCount).coerceIn(0f, 1f)
        appScope.launch {
            runCatching { actionRepository.updateBookReadProgress(dtoId, page, completed) }
                .onSuccess { ok ->
                    if (ok) {
                        hasRecorded = true
                        actionRepository.announceBookProgress(dtoId, completed, progress)
                    }
                }
        }
    }

    /** Mirrors the original ReaderProgressRecordingGate: threshold from settings, clamped to pageCount-1, req=1 for books already in progress. */
    private fun admitsRecording(page: Int, completed: Boolean, pageCount: Int): Boolean {
        if (completed || hasRecorded) return true
        val start = sessionStartPage ?: return false
        val threshold = minOf(progressThreshold, pageCount - 1)
        val requiredDistance = if (wasInProgress) minOf(threshold, 1) else threshold
        if (requiredDistance <= 0) return true
        return abs(page - start) >= requiredDistance
    }

    fun retry() {
        val book = lastBook ?: return
        lastBook = null
        load(book)
    }

    override fun onCleared() {
        document?.close()
        document = null
    }
}
