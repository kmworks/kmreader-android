package com.kmworks.kmreader.ui.reader

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.pdf.PdfDocumentHolder
import com.kmworks.kmreader.core.pdf.PdfRepository
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the native (PdfRenderer) PDF reader; the androidx.pdf engine has its own
 * [PdfReaderUiState]. While `ready` is false the screen shows the download/loading flow or an error.
 */
data class NativePdfReaderUiState(
    val hasSession: Boolean = false,
    val bookDtoId: String? = null,
    /** Downloading (including the first open of the document). */
    val loading: Boolean = false,
    /** Download progress 0..1; null when the server does not send Content-Length. */
    val downloadProgress: Float? = null,
    val error: Boolean = false,
    val ready: Boolean = false,
    val pageCount: Int = 0,
    /** Initial page (1-based, from the server read progress or [load]'s startAtEnd). */
    val initialPage: Int = 1,
)

/**
 * Native PDF reader: downloads the whole book (`filesDir/pdf/`, or the offline download when one
 * exists) and renders page bitmaps with [PdfDocumentHolder]. Reading progress uses the same
 * `PATCH /api/v1/books/{id}/read-progress` path and the same recording gate as the other readers.
 */
@HiltViewModel
class NativePdfReaderViewModel
@Inject
constructor(
    private val pdfRepository: PdfRepository,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val appSettings: AppSettingsStore,
    /** Application scope: the exit flush must survive this view model being cleared with the page. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NativePdfReaderUiState())
    val uiState: StateFlow<NativePdfReaderUiState> = _uiState.asStateFlow()

    /** Incognito reading: skips progress reporting. */
    var incognito: Boolean = false

    /** Page-turn threshold for recording progress (same gate as the other readers). */
    @Volatile
    private var progressThreshold: Int = ReaderProgressRecordingGate.THRESHOLD

    private var lastBook: Book? = null
    private var holder: PdfDocumentHolder? = null
    private var lastReportedPage = 0

    private var sessionStartPage: Int? = null
    private var wasInProgress: Boolean = false
    private var hasRecorded: Boolean = false

    // Current page plus its neighbours, so scrolling does not re-render and cannot grow unbounded.
    private val pageCache = LruCache<Int, Bitmap>(6)

    init {
        viewModelScope.launch {
            appSettings.progressRecordingThreshold.collect { progressThreshold = it }
        }
    }

    /**
     * Entry point: enters the error state when there is no real mapping. [startAtEnd] opens the last
     * page (explicit end-of-book entries); otherwise the server read progress decides.
     */
    fun load(book: Book, startAtEnd: Boolean = false) {
        if (lastBook?.id == book.id) return
        lastBook = book
        val dtoId = pdfRepository.bookDtoId(book.id)
        if (dtoId == null) {
            _uiState.update { it.copy(hasSession = false, loading = false, error = true) }
            return
        }
        _uiState.update {
            it.copy(
                hasSession = true,
                bookDtoId = dtoId,
                loading = true,
                downloadProgress = null,
                error = false,
                ready = false,
            )
        }
        viewModelScope.launch {
            val file = pdfRepository.downloadPdf(dtoId) { copied, total ->
                _uiState.update { s ->
                    s.copy(
                        downloadProgress = if (total > 0) {
                            (copied.toFloat() / total).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                    )
                }
            }
            if (file == null) {
                _uiState.update { it.copy(loading = false, error = true) }
                return@launch
            }
            val opened = withContext(Dispatchers.IO) {
                runCatching { PdfDocumentHolder(file) }.getOrNull()
            }
            if (opened == null || opened.pageCount <= 0) {
                opened?.close()
                _uiState.update { it.copy(loading = false, error = true) }
                return@launch
            }
            holder?.close()
            holder = opened
            pageCache.evictAll()
            lastReportedPage = 0
            val progress = detailRepository.bookDetail(dtoId)?.readProgress
            wasInProgress = progress != null && !progress.completed
            hasRecorded = false
            val initialPage = when {
                startAtEnd -> opened.pageCount
                progress == null || progress.completed -> 1
                progress.page > 0 -> progress.page.coerceIn(1, opened.pageCount)
                else -> 1
            }
            // The gate anchor is the resume page, so simply opening the book records nothing.
            sessionStartPage = initialPage
            _uiState.update {
                it.copy(
                    loading = false,
                    ready = true,
                    pageCount = opened.pageCount,
                    initialPage = initialPage,
                )
            }
        }
    }

    fun retry() {
        val book = lastBook ?: return
        lastBook = null
        load(book)
    }

    /** Renders the page (1-based); a cache hit returns the cached bitmap. */
    suspend fun renderPage(page: Int, targetWidth: Int): Bitmap? {
        val index = page - 1
        pageCache.get(index)?.let { return it }
        val rendered = withContext(Dispatchers.Default) {
            holder?.renderPage(index, targetWidth)
        } ?: return null
        pageCache.put(index, rendered)
        return rendered
    }

    /** Reports the current page (de-duplicated; the last page marks completion). Skipped when incognito. */
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
     * Exit flush: reports the page reached through the same recording gate as [reportProgress], so
     * opening a book and closing it without moving records nothing. The write survives this view
     * model being cleared, and the screens showing this book are told about the new progress.
     */
    fun flushProgress(page: Int) {
        if (incognito) return
        val dtoId = _uiState.value.bookDtoId ?: return
        val pageCount = _uiState.value.pageCount
        if (pageCount <= 0 || page <= 0) return
        if (page == lastReportedPage) return
        if (sessionStartPage == null) sessionStartPage = page
        val completed = page >= pageCount
        if (!admitsRecording(page, completed, pageCount)) return
        lastReportedPage = page
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

    /** Mirrors the original ReaderProgressRecordingGate: threshold from settings, clamped to the page count. */
    private fun admitsRecording(page: Int, completed: Boolean, pageCount: Int): Boolean {
        if (completed || hasRecorded) return true
        val start = sessionStartPage ?: return false
        val requiredDistance = ReaderProgressRecordingGate
            .requiredDistance(progressThreshold, pageCount, wasInProgress)
        return requiredDistance <= 0 || abs(page - start) >= requiredDistance
    }

    override fun onCleared() {
        pageCache.evictAll()
        holder?.close()
        holder = null
    }
}
