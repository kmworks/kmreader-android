package com.kmworks.kmreader.ui.reader

import android.content.Context
import androidx.core.util.size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.pdf.PdfDocument
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.AdjacentBooksResolver
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ReaderAdjacentBooks
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.pdf.PdfAndroidxLoader
import com.kmworks.kmreader.core.pdf.PdfOutlineExtractor
import com.kmworks.kmreader.core.pdf.PdfRepository
import com.kmworks.kmreader.core.pdf.PdfTocEntry
import com.kmworks.kmreader.core.reading.ReadListReadingService
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One search hit: the matching page (1-based) and a context snippet around its first match. */
data class PdfSearchResult(val pageNumber: Int, val snippet: String)

data class PdfReaderUiState(
    val hasSession: Boolean = false,
    val bookDtoId: String? = null,
    val loading: Boolean = false,
    /** Download progress; null when contentLength is unknown (indeterminate). */
    val downloadProgress: Float? = null,
    /** File is local and the document is being opened (the iOS "Preparing PDF…" loading stage). */
    val preparing: Boolean = false,
    val error: Boolean = false,
    val ready: Boolean = false,
    val pageCount: Int = 0,
    val initialPage: Int = 1,
    /** androidx.pdf document instance; rendering and the text layer are managed by PdfView. */
    val document: PdfDocument? = null,
    /** Books adjacent to this one (read list order, else series); resolved after the file opens. */
    val adjacent: ReaderAdjacentBooks = ReaderAdjacentBooks(),
    /**
     * Reading direction the series declares (`LEFT_TO_RIGHT` / `RIGHT_TO_LEFT` / `VERTICAL` /
     * `WEBTOON`); null while the lookup is in flight or when it failed, so the reader stays on the
     * global default (mirrors the original PdfReaderView.fetchSeries fallback).
     */
    val seriesDirection: String? = null,
    /** Parsed PDF outline (flattened with depth); empty while parsing or when the file has none. */
    val toc: List<PdfTocEntry> = emptyList(),
)

@HiltViewModel
class PdfReaderViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val pdfRepository: PdfRepository,
    private val pdfOutlineExtractor: PdfOutlineExtractor,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val sessionManager: SessionManager,
    private val appSettings: AppSettingsStore,
    private val adjacentBooksResolver: AdjacentBooksResolver,
    private val readListReadingService: ReadListReadingService,
    private val contentChangeBus: ContentChangeBus,
    /** Application scope: the exit flush must survive this view model being cleared with the page. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    /** Page-turn threshold for recording progress (mirrors the original AppConfig.progressRecordingThreshold, takes effect immediately from the settings page). */
    @Volatile
    private var progressThreshold: Int = ReaderProgressRecordingGate.THRESHOLD

    init {
        // A PDF reader is a reading session too: hold list refreshes while it is open and release them on close,
        // the same deferral the EPUB reader applies (see ContentChangeBus.readerOpened).
        contentChangeBus.readerOpened()
        viewModelScope.coroutineContext[Job]?.invokeOnCompletion { contentChangeBus.readerClosed() }
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

    /**
     * Entry point: enters the error state when there is no real mapping (no demo fallback).
     *
     * [readListStableId] is the read list the book was opened from, so the session continues in its
     * order; [startAtEnd] opens the last page (explicit end-of-book entries — the backward seam resumes
     * the previous book's own progress instead).
     */
    fun load(book: Book, readListStableId: Int? = null, startAtEnd: Boolean = false) {
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
                preparing = false,
                error = false,
                ready = false,
            )
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
            _uiState.update { it.copy(preparing = true) }
            val doc = PdfAndroidxLoader(context).open(file)
            if (doc == null) {
                _uiState.update { it.copy(loading = false, error = true) }
                return@launch
            }
            document = doc
            val detail = detailRepository.bookDetail(dtoId)
            val progress = detail?.readProgress
            wasInProgress = progress != null && !progress.completed
            hasRecorded = false
            val initialPage =
                when {
                    startAtEnd -> doc.pageCount
                    progress == null || progress.completed -> 1
                    else -> progress.page.coerceIn(1, doc.pageCount)
                }
            // Gate anchor = resume page (otherwise the page jump on open would cross the threshold and record immediately)
            sessionStartPage = initialPage
            _uiState.update {
                it.copy(
                    loading = false,
                    preparing = false,
                    ready = true,
                    pageCount = doc.pageCount,
                    initialPage = initialPage,
                    document = doc,
                )
            }

            resolveSeriesDirection(dtoId, detail?.seriesId)
            resolveAdjacent(dtoId, readListStableId)

            // Outline parsing runs after the first page so it never delays opening the book;
            // failures degrade to an empty list and the TOC entry point stays hidden.
            launch {
                val toc = pdfOutlineExtractor.extract(file)
                if (_uiState.value.bookDtoId == dtoId) {
                    _uiState.update { it.copy(toc = toc) }
                }
            }
        }
    }

    /**
     * Reads the series reading direction the same way the DIVINA reader does
     * (`series.metadata.readingDirection`), after the first page so the lookup never delays opening
     * the book. A failure keeps the global default (runCatching, not an error state): the direction
     * is a presentation hint, not something that can break the session.
     */
    private suspend fun resolveSeriesDirection(dtoId: String, seriesId: String?) {
        if (seriesId.isNullOrBlank()) return
        val direction = runCatching {
            detailRepository.seriesDetail(seriesId)?.metadata?.readingDirection
        }.getOrNull()
        // A different book was opened while the lookup was in flight
        if (_uiState.value.bookDtoId != dtoId) return
        _uiState.update { it.copy(seriesDirection = direction) }
    }

    /**
     * Resolves the read list the book belongs to (recording it, so the next open lands in the same
     * continuation junction) and then the neighbouring books, without delaying the first page.
     */
    private suspend fun resolveAdjacent(dtoId: String, readListStableId: Int?) {
        val context = runCatching {
            adjacentBooksResolver.readListContext(dtoId, readListStableId)
        }.getOrNull()
        if (context != null) {
            runCatching { readListReadingService.record(context.readListId, dtoId) }
        }
        val adjacent = adjacentBooksResolver.resolve(dtoId, context?.readListId)
        // A different book was opened while the lookup was in flight
        if (_uiState.value.bookDtoId != dtoId) return
        _uiState.update { it.copy(adjacent = adjacent) }
    }

    /**
     * Full-text search with one result per matching page (the page's first match), mirroring the
     * iOS PdfReaderViewModel.search: the snippet is 40 characters before / 80 after the match with
     * whitespace collapsed, and the list is capped at 200 results. The match set comes from
     * androidx.pdf (PDFium); the snippet text comes from the page's text contents, so pages whose
     * text cannot be extracted still appear, with an empty snippet.
     */
    suspend fun searchWithSnippets(query: String): List<PdfSearchResult> {
        val doc = document ?: return emptyList()
        return runCatching {
                val matches = doc.searchDocument(query, 0 until doc.pageCount)
                val results = ArrayList<PdfSearchResult>()
                for (i in 0 until matches.size) {
                    val page = matches.keyAt(i)
                    val text =
                        doc.getPageContent(page)
                            ?.textContents
                            ?.joinToString(" ") { it.text }
                            .orEmpty()
                    val start = text.indexOf(query, ignoreCase = true)
                    val snippet = if (start >= 0) buildSnippet(text, start, start + query.length) else ""
                    results.add(PdfSearchResult(pageNumber = page + 1, snippet = snippet))
                    if (results.size >= MAX_SEARCH_RESULTS) break
                }
                results
            }
            .getOrDefault(emptyList())
    }

    /** Mirrors the iOS buildSnippet: -40/+80 window, collapsed whitespace, "..." at cut ends. */
    private fun buildSnippet(text: String, start: Int, end: Int): String {
        val lower = (start - SNIPPET_CONTEXT_BEFORE).coerceAtLeast(0)
        val upper = (end + SNIPPET_CONTEXT_AFTER).coerceAtMost(text.length)
        var snippet = text.substring(lower, upper).replace(Regex("\\s+"), " ").trim()
        if (lower > 0) snippet = "...$snippet"
        if (upper < text.length) snippet += "..."
        return snippet
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
     * Exit flush: reports the page reached, but only through the same recording gate as [reportProgress], so opening
     * a book and closing it without moving records nothing. When the gate passes the write survives this view model
     * being cleared, and the screens showing this book are told about it so they patch the new progress instead of
     * waiting for SSE.
     */
    fun flushProgress(page: Int) {
        if (incognito) return
        val dtoId = _uiState.value.bookDtoId ?: return
        val pageCount = _uiState.value.pageCount
        if (pageCount <= 0 || page <= 0) return
        if (page == lastReportedPage) return
        if (sessionStartPage == null) sessionStartPage = page
        val completed = page >= pageCount
        // Below the threshold this leaves lastReportedPage untouched, so a later report/flush can still record.
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

    /** Mirrors the original ReaderProgressRecordingGate: threshold from settings, clamped to the page count; req=1 for books already in progress. */
    private fun admitsRecording(page: Int, completed: Boolean, pageCount: Int): Boolean {
        if (completed || hasRecorded) return true
        val start = sessionStartPage ?: return false
        val requiredDistance = ReaderProgressRecordingGate
            .requiredDistance(progressThreshold, pageCount, wasInProgress)
        return requiredDistance <= 0 || abs(page - start) >= requiredDistance
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

    private companion object {
        /** iOS caps the search result list at 200 entries. */
        const val MAX_SEARCH_RESULTS = 200
        /** iOS buildSnippet: 40 characters of context before the match, 80 after it. */
        const val SNIPPET_CONTEXT_BEFORE = 40
        const val SNIPPET_CONTEXT_AFTER = 80
    }
}
