package com.kmworks.kmreader.ui.reader

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import com.kmworks.kmreader.core.settings.AppSettingsStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.AdjacentBooksResolver
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.data.ReaderAdjacentBooks
import com.kmworks.kmreader.core.epub.EpubChapter
import com.kmworks.kmreader.core.epub.EpubFootnote
import com.kmworks.kmreader.core.epub.EpubFootnoteNote
import com.kmworks.kmreader.core.epub.EpubHref
import com.kmworks.kmreader.core.epub.EpubJs
import com.kmworks.kmreader.core.epub.EpubJsBridge
import com.kmworks.kmreader.core.epub.EpubLinkDto
import com.kmworks.kmreader.core.epub.EpubPageInsets
import com.kmworks.kmreader.core.epub.EpubPositionIndex
import com.kmworks.kmreader.core.epub.EpubPositionIndexStore
import com.kmworks.kmreader.core.epub.EpubRepository
import com.kmworks.kmreader.core.epub.EpubResource
import com.kmworks.kmreader.core.epub.EpubScrollWebView
import com.kmworks.kmreader.core.epub.EpubThemeConfig
import com.kmworks.kmreader.core.epub.EpubWeb
import com.kmworks.kmreader.core.epub.EpubWebViewClient
import com.kmworks.kmreader.core.epub.buildEpubPositionIndex
import com.kmworks.kmreader.core.epub.computeEpubPageInsets
import com.kmworks.kmreader.core.epub.createEpubWebView
import com.kmworks.kmreader.core.epub.epubPositionIndexKey
import com.kmworks.kmreader.core.reader.EpubBookmark
import com.kmworks.kmreader.core.reader.EpubBookmarkStore
import com.kmworks.kmreader.core.reader.EpubOverlayItem
import com.kmworks.kmreader.core.download.OfflineLibrary
import com.kmworks.kmreader.core.fonts.FontRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.reading.ReadListReadingService
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.CustomFont
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLDecoder
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** EPUB reader UI state. */
data class EpubUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val bookDtoId: String? = null,
    val chapters: List<EpubChapter> = emptyList(),
    val toc: List<EpubLinkDto> = emptyList(),
    val currentChapterIndex: Int = 0,
    val totalProgression: Float = 0f,
    val scrollToFraction: Float? = null,
    /** Books adjacent to this one (read list order, else series); resolved after the first page shows. */
    val adjacent: ReaderAdjacentBooks = ReaderAdjacentBooks(),
    /**
     * The publication's own reading progression from the manifest (`ltr` / `rtl` / `ttb` / `btt` /
     * `auto`), which Komga derives from the series reading direction. Null while loading or when the
     * manifest omits it, so the reader falls back to the global default.
     */
    val readingProgression: String? = null,
    /** Read list this session continues in (names the end page's relation row instead of the series). */
    val readListName: String? = null,
)

/**
 * EPUB reader state (mirrors the WebPub manifest route of EpubReaderView):
 * manifest → chapters; positions → chapter weights (cached, fetched off the first-render path);
 * progression GET restores / PUT reports throttled.
 */
@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val epubRepository: EpubRepository,
    private val positionIndexStore: EpubPositionIndexStore,
    private val offlineLibrary: OfflineLibrary,
    private val fontRepository: FontRepository,
    private val bookmarkStore: EpubBookmarkStore,
    private val appSettings: AppSettingsStore,
    @ApplicationContext private val context: Context,
    private val actionRepository: ActionRepository,
    private val adjacentBooksResolver: AdjacentBooksResolver,
    private val readListReadingService: ReadListReadingService,
    /** Application scope: the exit flush must survive this view model being cleared with the page. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EpubUiState(hasSession = sessionManager.clients.value != null),
    )
    val uiState: StateFlow<EpubUiState> = _uiState.asStateFlow()

    /** Font families downloaded locally (data source for the font picker in the settings sheet). */
    private val _downloadedFamilies = MutableStateFlow<Set<String>>(emptySet())
    val downloadedFamilies: StateFlow<Set<String>> = _downloadedFamilies.asStateFlow()

    /** Bookmarks of the open book, in reading order (the store is keyed by book DTO id). */
    private val _bookmarks = MutableStateFlow<List<EpubBookmark>>(emptyList())
    val bookmarks: StateFlow<List<EpubBookmark>> = _bookmarks.asStateFlow()
    private var bookmarkJob: Job? = null

    /** App-wide theme mode ("system"/"light"/"dark"): the reader theme follows the APP theme,
     *  not the raw system dark flag (mirrors MainActivity's theme resolution). */
    val themeMode: StateFlow<String> = appSettings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsStore.THEME_SYSTEM)

    private var bookDtoId: String? = null
    private var loadedBookId: Int = -1

    /** Read list the current book was opened from; kept so the end page can retry the sibling lookup. */
    private var sessionReadListStableId: Int? = null
    /**
     * Positions of the open book, compacted to per-chapter weights + sorted progressions. Fetched off
     * the first-render path and cached: a long novel's list is megabytes (2.5 MB / 15,865 entries for
     * an 8,431-page book), so it must neither block the first chapter nor be re-downloaded per open.
     */
    private var positionIndex: EpubPositionIndex? = null
    private var equalWeight: Float = 1f
    private var currentChapterFraction: Float = 0f
    private var reportJob: Job? = null

    // Progress reporting gate (mirrors ReaderProgressRecordingGate in the original
    // EpubReaderViewModel): an unread book reports only after moving more than 3 pages, a book
    // already in progress reports after 1 page; completion / already recorded always passes.
    private var sessionStartTotalProgression: Float? = null
    private var estimatedTotalPages: Int = 0
    private var wasInProgress: Boolean = false
    private var hasRecorded: Boolean = false

    /** Threshold for "record progress only after turning N pages" (setting; mirrors the original AppConfig.progressRecordingThreshold). */
    @Volatile
    private var progressThreshold: Int = ReaderProgressRecordingGate.THRESHOLD

    init {
        viewModelScope.launch {
            appSettings.progressRecordingThreshold.collect { progressThreshold = it }
        }
    }

    /** Incognito reading: skips progress reporting. */
    var incognito: Boolean = false

    private val deviceId: String = "kmreader-android"
    private val deviceName: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    /**
     * Loads the manifest, positions and the saved progression.
     *
     * [readListStableId] is the read list the book was opened from, so the session continues in its
     * order; [startAtEnd] opens the last chapter at its bottom (explicit end-of-book entries — the
     * backward seam resumes the previous book's own progress instead).
     */
    fun load(book: Book, readListStableId: Int? = null, startAtEnd: Boolean = false) {
        if (loadedBookId == book.id) return
        loadedBookId = book.id
        sessionReadListStableId = readListStableId
        if (sessionManager.clients.value == null) return
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val dtoId = DetailIdRegistry.bookDtoId(book.id)
            if (dtoId == null) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = context.getString(R.string.epub_error_locate_book),
                )
                return@launch
            }
            bookDtoId = dtoId
            refreshFonts()
            // Bookmarks follow the book DTO id, so a cache rebuild (the Room file is restartable)
            // never loses them. Re-collected per book: the flow is cheap and always in reading order.
            bookmarkJob?.cancel()
            bookmarkJob = viewModelScope.launch {
                bookmarkStore.bookmarks(dtoId).collect { _bookmarks.value = it }
            }
            // Offline first: a downloaded EPUB carries its own manifest, so the TOC and the
            // reading order survive without the server; the network manifest stays authoritative.
            val manifest = runCatching { epubRepository.manifest(dtoId) }.getOrNull()
                ?: offlineLibrary.epubManifest(dtoId)
            val order = manifest?.readingOrder.orEmpty()
                .filter { !it.href.isNullOrBlank() }
            if (order.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = context.getString(R.string.epub_error_load_toc),
                )
                return@launch
            }
            val chapters = order.mapIndexed { index, link ->
                EpubChapter(
                    index = index,
                    // Canonical path; resource URLs and progression hrefs are compared through it.
                    href = EpubHref.normalize(link.href.orEmpty()),
                    title = link.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.epub_chapter_fallback, index + 1),
                )
            }
            equalWeight = 1f / chapters.size
            // Positions only refine the whole-book percentage and the reported locator — they never
            // affect the layout, so the first chapter must not wait for them. A cache hit answers
            // off the main thread in microseconds; a miss is fetched below, after the page is up.
            val positionKey = epubPositionIndexKey(chapters, book.pageCount)
            positionIndex = positionIndexStore.cached(dtoId, positionKey)
            estimatedTotalPages = positionIndex?.total ?: 0

            // Restore saved progression (mirrors decodeRemoteWebPubProgression fallback to start)
            val saved = epubRepository.savedProgression(dtoId)
            wasInProgress = saved != null
            sessionStartTotalProgression = null
            hasRecorded = false
            var startIndex = 0
            var startFraction: Float? = null
            if (saved != null) {
                val index = chapters.indexOfFirst { EpubHref.matches(it.href, saved.locator.href) }
                if (index >= 0) {
                    startIndex = index
                    startFraction = saved.locator.locations?.progression
                }
            }
            // Explicit end-of-book entry: open the book on its last page instead of its saved position
            if (startAtEnd) {
                startIndex = chapters.lastIndex
                startFraction = 1f
            }
            currentChapterFraction = startFraction ?: 0f
            _uiState.value = _uiState.value.copy(
                loading = false,
                bookDtoId = dtoId,
                chapters = chapters,
                toc = manifest?.toc.orEmpty(),
                currentChapterIndex = startIndex,
                scrollToFraction = startFraction,
                totalProgression = totalFor(chapters, startIndex, startFraction ?: 0f),
                readingProgression = manifest?.metadata?.readingProgression,
            )

            resolveAdjacent(dtoId, readListStableId)
            if (positionIndex == null) {
                loadPositionIndex(dtoId, positionKey)
            }
        }
    }

    /**
     * Fetches the positions list after the first page is already on screen, then applies it: weights
     * for the whole-book percentage, progressions for snapping the reported locator. A failure is
     * silent — the reader keeps the equal-weight approximation and an unsnapped report.
     */
    private fun loadPositionIndex(dtoId: String, key: String) {
        viewModelScope.launch {
            val positions = epubRepository.positions(dtoId) ?: return@launch
            if (_uiState.value.bookDtoId != dtoId || _uiState.value.chapters.isEmpty()) return@launch
            val index = buildEpubPositionIndex(positions)
            if (index.total <= 0) return@launch
            positionIndexStore.remember(dtoId, key, index)
            positionIndex = index
            estimatedTotalPages = index.total
            // Weights change the whole-book percentage without the reader moving, so the recording
            // gate's baseline has to move with them: otherwise refitting from equal weights to the
            // real ones looks like a large jump and records a book that was only just opened.
            val state = _uiState.value
            val rebased = totalFor(state.chapters, state.currentChapterIndex, currentChapterFraction)
            if (sessionStartTotalProgression != null) sessionStartTotalProgression = rebased
            _uiState.value = state.copy(totalProgression = rebased)
        }
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
        _uiState.value = _uiState.value.copy(adjacent = adjacent, readListName = context?.name)
    }

    /** Retries the sibling lookup after it failed (the end page offers this when the device was offline). */
    fun retryAdjacent() {
        val dtoId = _uiState.value.bookDtoId ?: return
        _uiState.value = _uiState.value.copy(adjacent = ReaderAdjacentBooks())
        viewModelScope.launch { resolveAdjacent(dtoId, sessionReadListStableId) }
    }

    /** Scroll-metrics callback from the JS bridge: updates the in-chapter and whole-book fractions, then reports throttled (mirrors the original updateThrottleInterval=2s). */
    fun onMetrics(chapterFraction: Float) {
        val state = _uiState.value
        if (state.chapters.isEmpty()) return
        currentChapterFraction = chapterFraction.coerceIn(0f, 1f)
        val total = totalFor(state.chapters, state.currentChapterIndex, currentChapterFraction)
        if (sessionStartTotalProgression == null) {
            sessionStartTotalProgression = total
        }
        _uiState.value = state.copy(totalProgression = total)
        scheduleReport()
    }

    /** Jump to a chapter (table of contents). */
    fun goToChapter(index: Int) {
        val state = _uiState.value
        if (index !in state.chapters.indices) return
        flushReport()
        selectChapter(index, null)
    }

    /**
     * Advance one chapter, landing on its start. False when already on the last chapter — the
     * caller then opens the end page instead (mirrors the iOS goToNextPage chapter seam).
     */
    fun nextChapter(): Boolean {
        val state = _uiState.value
        if (state.currentChapterIndex >= state.chapters.lastIndex) return false
        flushReport()
        selectChapter(state.currentChapterIndex + 1, 0f)
        return true
    }

    /**
     * Back one chapter, landing on its last page: fraction 1 is the sentinel for "the target
     * chapter's end" (mirrors the iOS targetPageIndex = -1), in both flow styles.
     */
    fun previousChapter(): Boolean {
        val state = _uiState.value
        if (state.currentChapterIndex <= 0) return false
        flushReport()
        selectChapter(state.currentChapterIndex - 1, 1f)
        return true
    }

    /** Whole-book fraction seek (bottom-bar slider): derives the chapter and in-chapter fraction from the chapter weights. */
    fun seekTo(totalFraction: Float) {
        val state = _uiState.value
        if (state.chapters.isEmpty()) return
        flushReport()
        val target = totalFraction.coerceIn(0f, 1f)
        var acc = 0f
        state.chapters.forEachIndexed { index, chapter ->
            val weight = weightOf(chapter)
            if (target <= acc + weight || index == state.chapters.lastIndex) {
                val fraction = if (weight > 0f) {
                    ((target - acc) / weight).coerceIn(0f, 1f)
                } else {
                    0f
                }
                selectChapter(index, fraction)
                return
            }
            acc += weight
        }
    }

    /**
     * Bookmark at the current position, or drop the one already there (the chrome capsule is a
     * toggle, mirroring Koharia's toggleBookmarkAtCurrentLocator).
     *
     * The position is matched to a page, not to the pixel: [currentChapterFraction] moves with
     * every scroll tick, so an exact comparison would never find the bookmark the user just made.
     * Incognito reading never writes (and never reads a list) — nothing about that session may
     * survive.
     */
    fun toggleBookmark() {
        if (incognito) return
        val dtoId = bookDtoId ?: return
        val state = _uiState.value
        val chapter = state.chapters.getOrNull(state.currentChapterIndex) ?: return
        val fraction = currentChapterFraction
        val existing = _bookmarks.value.firstOrNull { bookmark ->
            bookmark.chapterIndex == state.currentChapterIndex &&
                abs(bookmark.chapterFraction - fraction) <= BOOKMARK_MATCH_TOLERANCE
        }
        viewModelScope.launch {
            if (existing != null) {
                bookmarkStore.delete(dtoId, existing.id)
            } else {
                bookmarkStore.save(
                    dtoId,
                    EpubBookmark(
                        id = java.util.UUID.randomUUID().toString(),
                        chapterIndex = state.currentChapterIndex,
                        chapterFraction = fraction,
                        totalProgression = state.totalProgression,
                        chapterTitle = chapter.title,
                        createdAtMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun deleteBookmark(bookmarkId: String) {
        val dtoId = bookDtoId ?: return
        viewModelScope.launch { bookmarkStore.delete(dtoId, bookmarkId) }
    }

    /** Consumes the one-shot scroll-restore command. */
    fun consumeScroll() {
        if (_uiState.value.scrollToFraction != null) {
            _uiState.value = _uiState.value.copy(scrollToFraction = null)
        }
    }

    /** Reports the current progress immediately (called before exiting or switching chapters). */
    fun flushReport() {
        reportJob?.cancel()
        reportJob = null
        doReport(flush = true)
    }

    /** Forwards resource interception (called synchronously on the WebViewClient thread). */
    fun fetchResource(href: String): EpubResource? {
        val dtoId = bookDtoId ?: return null
        // Downloaded EPUB first: no network round trip and the reader works offline. The zip
        // stores resources as plain paths, which is exactly the normalized href.
        offlineLibrary.epubResourceBlocking(dtoId, href)?.let {
            return if (it.mimeType == "application/octet-stream") {
                EpubResource(it.data, EpubWeb.mediaTypeFor(href), it.encoding)
            } else {
                it
            }
        }
        val base = epubRepository.baseUrl ?: return null
        // Path-style URL first (the documented resource route), then the legacy query form for
        // servers that only accept the href as a query parameter.
        val resource = epubRepository.fetchResourceBlocking(
            EpubWeb.resourcePathUrl(base, dtoId, href),
        ) ?: epubRepository.fetchResourceBlocking(
            EpubWeb.resourceRequestUrl(base, dtoId, href),
        ) ?: return null
        return if (resource.mimeType == "application/octet-stream") {
            EpubResource(resource.data, EpubWeb.mediaTypeFor(href), resource.encoding)
        } else {
            resource
        }
    }

    /** Canonical href of the chapter matching [href] (null when it points outside the spine). */
    private fun state0ChapterHrefFor(href: String): String? =
        _uiState.value.chapters.firstOrNull { EpubHref.matches(it.href, href) }?.href

    /** Reads a local font for the fake-domain fonts route (called synchronously on the WebViewClient thread). */
    fun fetchLocalFont(path: String): EpubResource? = fontRepository.localFontResource(path)

    /** @font-face css for the downloaded font families (urls already rewritten to the fake-domain route). */
    fun fontCssFor(family: String): String? = fontRepository.localCss(family)

    /** Refreshes the set of downloaded font families. */
    fun refreshFonts() {
        _downloadedFamilies.value = fontRepository.downloadedFamilies()
    }

    private fun selectChapter(index: Int, scrollFraction: Float?) {
        currentChapterFraction = scrollFraction ?: 0f
        _uiState.value = _uiState.value.copy(
            currentChapterIndex = index,
            scrollToFraction = scrollFraction,
            totalProgression = totalFor(_uiState.value.chapters, index, currentChapterFraction),
        )
    }

    private fun scheduleReport() {
        if (reportJob?.isActive == true) return
        reportJob = viewModelScope.launch {
            delay(2_000)
            doReport()
        }
    }

    /**
     * Reports the current position. Every report goes through the recording gate (mirrors the original
     * flushProgress guard), so an unread or finished book only records after the reader moves the configured number
     * of pages. [flush] is the leaving-the-reader path: it survives this view model being cleared and announces the
     * new progress so the screens showing this book do not wait for SSE.
     */
    private fun doReport(flush: Boolean = false) {
        // Incognito: skip progress reporting (restoring is still kept)
        if (incognito) return
        val state = _uiState.value
        val dtoId = bookDtoId ?: return
        val chapter = state.chapters.getOrNull(state.currentChapterIndex) ?: return
        val fraction = currentChapterFraction
        // Report the snapped position and the percentage OF THAT position: the server stores both halves
        // in one locator, so deriving the percentage from the unsnapped fraction left a locator whose
        // progression and totalProgression disagreed with each other.
        val snapped = snapToPosition(chapter.href, fraction)
        val total = totalFor(state.chapters, state.currentChapterIndex, snapped)
        // Completed = last chapter scrolled to its end (approximates the original reachedLastPage:
        // Android only knows the in-chapter fraction)
        val completed = state.currentChapterIndex >= state.chapters.lastIndex && fraction >= 0.99f
        if (!admitsRecording(total, completed)) return
        val launchScope = if (flush) appScope else viewModelScope
        launchScope.launch {
            val ok = epubRepository.reportProgression(
                bookDtoId = dtoId,
                href = chapter.href,
                chapterFraction = snapped,
                totalProgression = total,
                deviceId = deviceId,
                deviceName = deviceName,
            )
            if (ok) {
                hasRecorded = true
                if (flush) actionRepository.announceBookProgress(dtoId, completed, total)
            }
        }
    }

    /**
     * Mirrors the original ReaderProgressRecordingGate: 0 disables the gate, completion or an earlier recording
     * always passes, the threshold is clamped to the estimated page count, and a book already in progress only
     * needs one page.
     */
    private fun admitsRecording(currentTotal: Float, completed: Boolean): Boolean {
        if (completed || hasRecorded) return true
        val start = sessionStartTotalProgression ?: return false
        val pageCount = estimatedTotalPages
        val requiredDistance = ReaderProgressRecordingGate
            .requiredDistance(progressThreshold, pageCount, wasInProgress)
        if (requiredDistance <= 0) return true
        // Without positions the whole-book percentage stands in for pages (a 100-page book) so these books still
        // follow the reader instead of never recording.
        val scale = if (pageCount > 0) pageCount.toFloat() else 100f
        return (abs(currentTotal - start) * scale).toInt() >= requiredDistance
    }

    private fun weightOf(chapter: EpubChapter): Float =
        positionIndex?.weightOf(chapter.href) ?: equalWeight

    /**
     * Snaps the in-chapter fraction to a Komga position of that chapter. The server (Komga/kmrs)
     * requires the reported progression to exactly match a position or sit strictly between two
     * positions of the same href — a raw fraction past the chapter's last position is rejected with
     * 400 "Invalid progression". Sending back the exact f32 the server advertised round-trips to an
     * exact match.
     *
     * The FIRST position at or after [fraction] wins: positions are spaced by the server's own
     * pagination, so the nearest one can sit slightly before what the reader has read and makes a
     * forward read walk its saved position backwards. Past the chapter's last position the last one
     * is the only valid answer.
     */
    private fun snapToPosition(href: String, fraction: Float): Float {
        val progressions = positionIndex?.progressionsByHref?.get(href).orEmpty()
        if (progressions.isEmpty()) return fraction
        return progressions.firstOrNull { it >= fraction } ?: progressions.last()
    }

    private fun totalFor(chapters: List<EpubChapter>, index: Int, fraction: Float): Float {
        var acc = 0f
        chapters.take(index).forEach { acc += weightOf(it) }
        return (acc + fraction * (chapters.getOrNull(index)?.let { weightOf(it) } ?: 0f))
            .coerceIn(0f, 1f)
    }

    private companion object {
        /**
         * How close two in-chapter fractions must be to count as the same bookmark (1% of a
         * chapter). Wide enough that the scroll tick under a page still matches the bookmark made
         * on it, narrow enough that a bookmark on the neighbouring page stays a separate entry.
         */
        const val BOOKMARK_MATCH_TOLERANCE = 0.01f
    }
}

/** EPUB reader (mirrors EpubReaderView): WebView WebPub pipeline + DIVINA-style chrome. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpubReaderScreen(
    book: Book,
    onBack: () -> Unit,
    incognito: Boolean = false,
    viewModel: EpubReaderViewModel = hiltViewModel(),
    settingsViewModel: DivinaSettingsViewModel = hiltViewModel(),
    /** The read list the book was opened from (null from everywhere else), kept across neighbouring books. */
    readListStableId: Int? = null,
    /** Open the book on its last page (explicit end-of-book entry; the backward seam resumes progress). */
    startAtEnd: Boolean = false,
    /** Opens a neighbouring book with the reader for its format; [startAtEnd] travels on the route. */
    onOpenBook: (Book, Boolean) -> Unit = { _, _ -> },
    onVisitSeries: (Book) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onBrowseFilter: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(book.id, incognito, startAtEnd) {
        viewModel.incognito = incognito
        viewModel.load(book, readListStableId, startAtEnd)
    }
    if (state.hasSession) {
        RealEpubReader(
            book = book,
            state = state,
            viewModel = viewModel,
            settingsViewModel = settingsViewModel,
            onBack = onBack,
            incognito = incognito,
            onOpenBook = onOpenBook,
            onVisitSeries = onVisitSeries,
            onOpenReadList = onOpenReadList,
            onBrowseFilter = onBrowseFilter,
        )
    } else {
        // No session: no demo skeleton, just an error-state exit
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.reader_connection_required),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.reader_back), color = Color.White)
                }
            }
        }
    }
}

/** Real WebView EPUB body. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RealEpubReader(
    book: Book,
    state: EpubUiState,
    viewModel: EpubReaderViewModel,
    settingsViewModel: DivinaSettingsViewModel,
    onBack: () -> Unit,
    incognito: Boolean,
    onOpenBook: (Book, Boolean) -> Unit,
    onVisitSeries: (Book) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onBrowseFilter: (String) -> Unit = {},
) {
    // EPUB preferences are persisted globally (mirrors the original @AppStorage epubPreferences), so
    // the reader and the defaults page share the same copy
    val epubSettings by settingsViewModel.epubSettings.collectAsStateWithLifecycle()
    // Volume-key page turning is the shared DIVINA setting (the original AppStorage key lives on the
    // reader settings sheet, not on the EPUB preferences), so read the global copy here.
    val readerSettings by settingsViewModel.settings.collectAsStateWithLifecycle()
    // Margin-note ("info overlay") preferences, persisted globally like the theme settings
    val epubOverlay by settingsViewModel.epubOverlay.collectAsStateWithLifecycle()
    // The reader theme follows the APP theme setting, not the raw system dark flag (the user
    // may run the app dark on a light system, or vice versa); "system" still defers to the OS.
    val appThemeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = when (appThemeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    // Reading direction: ONLY the publication's own reading progression (mirrors the iOS
    // tapReadingDirection — ltr/auto/missing all resolve to LTR). The global comic default
    // direction must not leak into EPUB: a manga RTL default would mirror a Chinese novel's
    // tap zones and page order (left tap turned pages "forward", right tap went backwards).
    val rtl = state.readingProgression?.trim()?.lowercase() == "rtl"
    val downloadedFamilies by viewModel.downloadedFamilies.collectAsStateWithLifecycle()
    val fontCss = remember(epubSettings.typeface, downloadedFamilies) {
        if (epubSettings.typeface in downloadedFamilies) {
            viewModel.fontCssFor(epubSettings.typeface)
        } else {
            null
        }
    }
    val viewportWidthDp = LocalConfiguration.current.screenWidthDp
    // Reserve vertical room for the header/footer info overlay in the page layout itself, so
    // text never slides under the overlay (mirrors the iOS containerInsets top/bottom +24pt,
    // applied only when at least one slot can show content). The chrome-visible slots count too:
    // with every reading slot set to "none" the page would otherwise reserve nothing at all, and
    // the progress note that appears above the chrome landed straight on the first line.
    val overlayHasContent = epubOverlay.readerHeaderLeading != EpubOverlayItem.NONE ||
        epubOverlay.readerHeaderCenter != EpubOverlayItem.NONE ||
        epubOverlay.readerHeaderTrailing != EpubOverlayItem.NONE ||
        epubOverlay.readerFooterLeading != EpubOverlayItem.NONE ||
        epubOverlay.readerFooterCenter != EpubOverlayItem.NONE ||
        epubOverlay.readerFooterTrailing != EpubOverlayItem.NONE ||
        epubOverlay.controlsHeaderCenter != EpubOverlayItem.NONE ||
        epubOverlay.controlsFooterCenter != EpubOverlayItem.NONE
    // Page insets: the header/footer overlay band plus, when content must not draw under it, the
    // display-cutout band. The two reservations are resolved together in computeEpubPageInsets
    // (CSS px, NOT physical px — the injected page CSS consumes these as padding in CSS px, and
    // handing it density-scaled values once ate four lines per page). The overlay keeps the old
    // "both edges take the larger system bar" rule (the footer label sits on top of the
    // navigation bar); the cutout, its horizontal edges and the overlay-off case are what is new.
    // *IgnoringVisibility keeps the page layout from reflowing while the chrome toggles the
    // immersive bars on and off.
    val windowInsetsCss = rememberReaderWindowInsetsCss()
    val pageInsets = computeEpubPageInsets(
        cutoutTopVPx = windowInsetsCss.cutoutTop,
        cutoutBottomVPx = windowInsetsCss.cutoutBottom,
        cutoutLeftVPx = windowInsetsCss.cutoutLeft,
        cutoutRightVPx = windowInsetsCss.cutoutRight,
        statusBarTopVPx = windowInsetsCss.statusBarTop,
        navigationBarBottomVPx = windowInsetsCss.navigationBarBottom,
        overlayHasContent = overlayHasContent,
        drawUnderCutout = readerSettings.drawUnderCutout,
    )
    val themeConfig = remember(
        epubSettings, dark, fontCss, rtl, viewportWidthDp, state.readingProgression, pageInsets,
        readerSettings.drawUnderCutout, windowInsetsCss.cutoutTop, windowInsetsCss.cutoutBottom,
    ) {
        mapEpubThemeConfig(
            epubSettings, dark, fontCss, rtl, viewportWidthDp, state.readingProgression, pageInsets,
            // Only the cutout part is clipped in scrolled mode: the overlay band scrolls with the
            // text today and must not gain a permanent strip (see EpubThemeConfig.cutoutTopVPx).
            cutoutTopVPx = if (readerSettings.drawUnderCutout) 0 else windowInsetsCss.cutoutTop,
            cutoutBottomVPx = if (readerSettings.drawUnderCutout) 0 else windowInsetsCss.cutoutBottom,
        )
    }
    var latestConfig by remember { mutableStateOf(themeConfig) }
    SideEffect { latestConfig = themeConfig }

    var chromeVisible by remember { mutableStateOf(false) }
    // Quick-actions panel of the floating chrome (mirrors the iOS showingQuickActions): lives
    // under the bottom-end menu button and folds whenever the chrome hides
    var quickActionsExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showCustomFonts by remember { mutableStateOf(false) }
    // A modal sheet (or the full-page fonts screen) covers the reader, so the floating chrome must
    // hide too: left visible it would bleed through the dialog dim. chromeVisible itself is kept —
    // dismissing the sheet restores the chrome as it was, and the reading overlay stays consistent.
    val sheetOpen = showSettings || showDetailSheet || showToc || showCustomFonts
    val chromeShown = chromeVisible && !sheetOpen
    var scrub by remember { mutableStateOf<Float?>(null) }
    var pendingScroll by remember { mutableStateOf<Float?>(null) }
    // Bumped for every chapter load and stamped into the page's JS after injection: measure
    // passes carry it back, so stale callbacks from the previous chapter can be dropped
    var loadToken by remember { mutableStateOf(0) }
    // onPageReady fires on BOTH onPageCommitVisible and onPageFinished for the same load (the
    // finished callback is only the fallback for loads where commit never fires). Injecting the
    // script twice re-runs __kmApplyTheme mid-load, which re-measures and re-imposes whatever
    // fraction the half-laid-out strip happens to sit at — destroying a queued end-of-chapter
    // restore (the "back-seam lands at the chapter start" bug).
    var injectedForToken by remember { mutableStateOf(-1) }
    var webView by remember { mutableStateOf<EpubScrollWebView?>(null) }
    // The WebView stays invisible until the restored progression has been applied: the chapter is
    // loaded from its top, so without this the first paint shows the chapter start and the injected
    // scroll only lands one or more frames later (same first-frame jump as the pager's cell 0)
    var restorePositioned by remember { mutableStateOf(false) }
    // Bitmap of the page being left, shown over the WebView while the next chapter loads (see
    // the chapter-change effect); fades out once the new page has been positioned.
    var chapterSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    // Whether the WebView has ever revealed a positioned page in this session. The chapter
    // effect cannot gate the leaving-page snapshot on restorePositioned: the scrollToFraction
    // effect clears that flag first on a backward seam (fraction 1.0 > 0), so the snapshot
    // silently never happened and the load window showed a blank panel instead of the old page.
    var webViewEverRevealed by remember { mutableStateOf(false) }
    var showingEndPage by remember { mutableStateOf(false) }
    // Mirror image of the end page: swiping past the START of chapter 0 shows the previous
    // book's card (mirrors the DIVINA PreviousEnd pager cell)
    var showingPreviousPage by remember { mutableStateOf(false) }
    // Paged-mode measure results (scrolled mode always reports 1/0)
    var pageCount by remember { mutableStateOf(1) }
    var currentPage by remember { mutableStateOf(0) }
    // Actual reading direction reported by the page JS: 0 = ltr, 1 = rtl, 2 = vertical.
    // Vertical books paginate downward but pages stack right-to-left, so the tap zones mirror.
    var jsDirection by remember { mutableStateOf(0) }
    // Last reported in-chapter fraction: the scrolled-mode edge test for page stepping
    var lastFraction by remember { mutableStateOf(0f) }
    // Bookmarks of the open book (the store is keyed by the book DTO id) and whether the position
    // on screen is one of them — the capsule is a toggle, so the icon reflects that state
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val bookmarkedHere = bookmarks.any { bookmark ->
        bookmark.chapterIndex == state.currentChapterIndex &&
            abs(bookmark.chapterFraction - lastFraction) <= 0.01f
    }
    // Anchor of a jump (in-book link or toc entry), applied once the target chapter's measure
    // pass reports. Chapter-scoped: a failed load must not let a stale anchor fire on whatever
    // chapter the reader lands on next.
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    var pendingAnchorChapter by remember { mutableStateOf<Int?>(null) }
    // Note bubble shown after tapping a footnote reference. Kept out of the ViewModel on purpose:
    // it is pure presentation of the page's own DOM, and it must die with the chapter load (a
    // bubble floating over the next chapter would be showing text that is no longer on screen).
    var footnoteNote by remember { mutableStateOf<EpubFootnoteNote?>(null) }
    // Link-jump back stack (mirrors readest's History.back): following an in-book link pushes
    // (chapterIndex, in-chapter fraction); the floating return chip pops it and jumps back.
    var linkBackStack by remember { mutableStateOf<List<Pair<Int, Float>>>(emptyList()) }
    // Cooldown so one overscroll drag cannot skip several chapters (the seam callback repeats
    // while the finger holds past the edge)
    var lastSeamAt by remember { mutableStateOf(0L) }

    // Chapter seams (mirrors the iOS goToNextPage/goToPreviousPage): past the last page of the
    // book the end page opens, otherwise the neighbouring chapter loads. The paged WebView drives
    // the position itself, so "beyond the edge" here always means a chapter switch.
    // Centre-tap chrome toggle (mirrors the iOS toggleControls): hiding the chrome always folds
    // the quick-actions panel with it
    fun toggleChrome() {
        chromeVisible = !chromeVisible
        if (!chromeVisible) quickActionsExpanded = false
    }

    /**
     * Jump to a bookmark: a different chapter loads and is positioned through [pendingScroll],
     * the same slot the toc and the link-jump return chip use; the current chapter just scrolls,
     * because selecting the chapter that is already on screen never reloads it.
     */
    fun jumpToBookmark(bookmark: EpubBookmark) {
        if (bookmark.chapterIndex !in state.chapters.indices) return
        showToc = false
        chromeVisible = false
        quickActionsExpanded = false
        if (bookmark.chapterIndex != state.currentChapterIndex) {
            pendingScroll = bookmark.chapterFraction
            restorePositioned = false
            viewModel.goToChapter(bookmark.chapterIndex)
        } else {
            webView?.evaluateJavascript(
                "window.__kmScrollToFraction(${bookmark.chapterFraction})",
                null,
            )
        }
    }

    fun seamNext() {
        val now = System.currentTimeMillis()
        if (now - lastSeamAt < 900L) {
            return
        }
        lastSeamAt = now
        if (EpubEndPageTrigger.shouldShow(state.currentChapterIndex, state.chapters.size)) {
            if (!showingEndPage) {
                showingEndPage = true
                chromeVisible = false
                quickActionsExpanded = false
            }
        } else {
            viewModel.nextChapter()
        }
    }

    fun seamPrevious() {
        val now = System.currentTimeMillis()
        if (now - lastSeamAt < 900L) {
            return
        }
        lastSeamAt = now
        if (!viewModel.previousChapter() && state.adjacent.previous != null && !showingPreviousPage) {
            // Chapter 0's leading edge: surface the previous book instead of swallowing the gesture
            showingPreviousPage = true
            chromeVisible = false
            quickActionsExpanded = false
        }
    }

    // One page step in either flow style: paged jumps a page, scrolled scrolls a screenful, and
    // crossing the chapter edge falls through to the seam.
    fun turnPage(forward: Boolean) {
        if (themeConfig.paged) {
            val target = currentPage + (if (forward) 1 else -1)
            when {
                target < 0 -> seamPrevious()
                target >= pageCount -> seamNext()
                else -> webView?.evaluateJavascript("window.__kmGoToPage($target, true)", null)
            }
        } else if (forward && lastFraction >= 0.999f) {
            seamNext()
        } else if (!forward && lastFraction <= 0.001f) {
            seamPrevious()
        } else {
            webView?.evaluateJavascript(
                "window.__kmScrollByViewport(${if (forward) 1 else -1}, true)",
                null,
            )
        }
    }

    // Callback holders: the bridge and the WebView outlive recompositions, so they invoke through
    // rememberUpdatedState to always act on the current state.
    val scope = rememberCoroutineScope()
    val tapHandler = rememberUpdatedState<(Float) -> Unit> { x ->
        if (themeConfig.paged) {
            // Tap zones (mirrors the iOS TapZoneHelper): the outer quarters turn a page, the
            // centre toggles the chrome; RTL and vertical writing mirror the zones.
            val mirror = themeConfig.rtl || jsDirection == 2
            val zone = when {
                x < 0.25f -> if (mirror) 1 else -1
                x > 0.75f -> if (mirror) -1 else 1
                else -> 0
            }
            when (zone) {
                -1 -> turnPage(false)
                1 -> turnPage(true)
                else -> toggleChrome()
            }
        } else {
            toggleChrome()
        }
    }
    val paginationHandler = rememberUpdatedState<(Int, Int, Int, Int) -> Unit> { total, page, token, dir ->
        // Stale measure pass from the previous chapter (its backstop timers and late image
        // loads outlive the loadUrl call): ignore it, or the reveal fires before the new
        // chapter is positioned and the old page flashes (mirrors the iOS generation token).
        if (token != loadToken) return@rememberUpdatedState
        pageCount = total
        currentPage = page
        jsDirection = dir
        // The first measure pass of a chapter means the restored position has been applied:
        // reveal the WebView (covers both flow styles; later passes are idempotent)
        restorePositioned = true
        webViewEverRevealed = true
        // Only the measure pass of the chapter the anchor belongs to consumes it: a toc entry or
        // in-book link can target a file that fails to load, and the anchor would otherwise be
        // applied to the following chapter (mirrors the pendingAnchor guard in readest).
        if (pendingAnchorChapter == state.currentChapterIndex) {
            pendingAnchorChapter = null
            pendingAnchor?.let { anchor ->
                pendingAnchor = null
                webView?.evaluateJavascript("window.__kmGoToAnchor(${JSONObject.quote(anchor)})", null)
            }
        }
    }
    val swipeHandler = rememberUpdatedState<(Float, Float) -> Unit> { dx, vx ->
        val width = webView?.width?.toFloat() ?: 0f
        // Comic-pager feel: the page follows the finger live (onDrag), so a modest fling or a
        // quarter-page drag already turns; anything weaker animates back to the current page.
        val strong = abs(vx) > 300f || (width > 0f && abs(dx) > width * 0.25f)
        // Vertical books page along scrollTop but keep horizontal swipes: in vertical-rl the
        // page progression is right-to-left, so a leftward swipe goes forward (mirrors foliate).
        val forward = when {
            jsDirection == 2 -> dx < 0f
            themeConfig.rtl -> dx > 0f
            else -> dx < 0f
        }
        if (strong) {
            turnPage(forward)
        } else {
            webView?.evaluateJavascript("window.__kmGoToPage($currentPage, true)", null)
        }
    }
    // In-book links (chapter xrefs, footnotes, TOC anchors inside the text): resolve them against
    // the reading order instead of letting the WebView navigate, which would desync the chapter
    // index and the progress reporting.
    val navigateHandler = rememberUpdatedState<(String, String?) -> Boolean>(
        { href, anchor ->
            fun navigateFallback() {
                val index = state.chapters.indexOfFirst { EpubHref.matches(it.href, href) }
                if (index >= 0) {
                    if (index != state.currentChapterIndex) {
                        // Remember where the jump started so the return chip can bring the
                        // reader back (readest pushes every goTo onto its history stack)
                        linkBackStack = linkBackStack + (state.currentChapterIndex to lastFraction)
                        pendingAnchor = anchor
                        pendingAnchorChapter = index
                        viewModel.goToChapter(index)
                    } else if (anchor != null) {
                        linkBackStack = linkBackStack + (state.currentChapterIndex to lastFraction)
                        webView?.evaluateJavascript(
                            "window.__kmGoToAnchor(${JSONObject.quote(anchor)})",
                            null,
                        )
                    }
                }
            }
            // Notes and footnote references that could not be extracted in place (cross-file
            // targets, empty bodies) navigate like every other in-book link: jump to the anchor,
            // push the return chip (linkBackStack). The extractable ones never reach here — the
            // page JS intercepts the tap and reports through footnoteHandler instead.
            navigateFallback()
            // Unknown in-book targets are still swallowed: loading them raw would leave the book
            true
        },
    )

    // A note reference tap: the page JS already extracted and flattened the note body, so this
    // only has to show the bubble — no navigation, no chapter change. A payload that cannot be
    // used (token from the previous chapter, an empty body) falls back to the plain jump so the
    // tap is never silently swallowed.
    val footnoteHandler = rememberUpdatedState<(String) -> Unit> { json ->
        val note = EpubFootnote.parse(json, loadToken)
        when {
            note == null -> Unit
            !note.isRenderable -> navigateHandler.value(EpubWeb.hrefFromUrl(note.href) ?: note.href, note.fragment)
            else -> {
                // The floating chrome would sit under the bubble and fight it for taps
                chromeVisible = false
                footnoteNote = note
            }
        }
    }

    // Metrics carry the page's load token (like the pagination callback): a throttled report
    // still in flight from the previous chapter in the loadUrl window must not be attributed
    // to the new chapter index, or the whole-book progression (and the persisted progression
    // it feeds) jumps to a wrong value.
    val metricsHandler = rememberUpdatedState<(Float, Int) -> Unit>(
        { fraction, token ->
            if (token != loadToken) return@rememberUpdatedState
            lastFraction = fraction
            viewModel.onMetrics(fraction)
        },
    )

    val bridge = remember {
        EpubJsBridge(
            metricsCallback = { fraction, token -> metricsHandler.value(fraction, token) },
            tapCallback = { x -> tapHandler.value(x) },
            paginationCallback = { total, page, token, dir -> paginationHandler.value(total, page, token, dir) },
            linkCallback = { url ->
                // In-book link click reported by the page JS (it preventDefaults because a
                // same-document hash hop never reaches shouldOverrideUrlLoading)
                val uri = android.net.Uri.parse(url)
                if (uri.host == EpubWeb.FAKE_HOST) {
                    val href = EpubWeb.hrefFromUrl(url)
                    if (href != null) navigateHandler.value(href, uri.fragment)
                }
            },
            footnoteCallback = { json -> footnoteHandler.value(json) },
        )
    }

    LaunchedEffect(state.scrollToFraction) {
        state.scrollToFraction?.let {
            pendingScroll = it
            // A mid-book jump (saved progression, previous-chapter seam landing on the last page)
            // is applied after the measure pass: hide the WebView until the pagination callback
            // confirms the position, exactly like the initial restore
            if (it > 0f) restorePositioned = false
            viewModel.consumeScroll()
        }
    }
    // Never leave the WebView hidden if the position never arrives (load error, dead JS). This
    // backstop must outlast the JS measurement chain (stylesReady 2s cap + imagesReady 3s cap +
    // the 10s hard backstop in kickMeasure, mirroring the iOS 10s global one): a shorter timer
    // reveals the UNRESTORED page 0 mid-wait, which then jumps to the last page once the real
    // restore lands — the "backward turn flashes the chapter start on image chapters" bug
    // (image loads push the chain past 3.6s; a pure-text book never comes close).
    LaunchedEffect(restorePositioned) {
        if (!restorePositioned) {
            delay(12_000)
            restorePositioned = true
        }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) restorePositioned = true
    }
    LaunchedEffect(themeConfig) {
        webView?.evaluateJavascript(
            "window.__kmApplyTheme && window.__kmApplyTheme(${themeConfig.toJs()})",
            null,
        )
    }
    LaunchedEffect(state.bookDtoId, state.currentChapterIndex, webView) {
        val dtoId = state.bookDtoId ?: return@LaunchedEffect
        val chapter = state.chapters.getOrNull(state.currentChapterIndex) ?: return@LaunchedEffect
        // Hide the old chapter while the new one loads and measures: the container's themed
        // background shows through instead of a white flash, and the pagination callback (or
        // the safety timer) reveals the positioned page. A live page leaves a bitmap snapshot
        // of itself first (Koharia's PixelCopy approach adapted to a single WebView — the iOS
        // three-slot deck keeps the old page alive, we cannot), so the user sees the old page
        // until the new one is ready, then an instant swap.
        footnoteNote = null
        if (webViewEverRevealed) chapterSnapshot = webView?.takeSnapshot()
        restorePositioned = false
        loadToken += 1
        webView?.loadUrl(EpubWeb.pageUrl(dtoId, chapter.href))
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.flushReport() }
    }

    // Immersive mode: hide the system bars while the chrome is hidden and restore them when it is
    // shown (mirrors iOS EpubReaderView .statusBarHidden)
    val view = LocalView.current
    DisposableEffect(view, chromeVisible) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeVisible) {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // The effect body runs after the composition apply phase, so by then the AndroidView factory
    // has already assigned `webView`: any key derived from it (or any value captured in the body)
    // already sees the NEW instance, and the null -> instance key change then destroys the WebView
    // that was just created (the reader loads into a dead WebView: black screen, no taps). A
    // constant key keeps this effect alive for the whole composition and only destroys the WebView
    // when the reader actually leaves the screen.
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    val pageBackground = remember(themeConfig) {
        Color(android.graphics.Color.parseColor(themeConfig.background))
    }
    // End-page text follows the page background (no second parseColor: lint flags it as String.toColorInt)
    val pageContent = if (pageBackground.luminance() > 0.5f) Color.Black else Color.White
    // Chrome follows the page theme (mirrors the iOS material-adaptive bars): the bar is the page
    // background slightly translucent, its text and icons the page's foreground. A hairline
    // divider separates the bar from the page instead of the old flat black slab.
    // Floating chrome tint: the page background near-opaque, so the buttons read on both light
    // and dark themes (mirrors the iOS borderedProminent chrome look)
    // Floating chrome sits directly over the page text, so it must read as solid: an opaque
    // disc in the page color plus a hairline border (the iOS borderedProminent material look).
    val chromeBackground = pageBackground
    val chromeBorder = pageContent.copy(alpha = 0.10f)

    // Overscroll past an edge is a chapter seam in scrolled mode (mirrors the iOS overscroll
    // chapter switch, reached here by dragging beyond the edge instead of panning past the last
    // page). The paged WebView never overscrolls — its seams come from swipes and tap zones.
    val onBottomOverscroll = rememberUpdatedState {
        if (!themeConfig.paged) seamNext()
    }
    val onTopOverscroll = rememberUpdatedState {
        if (!themeConfig.paged) seamPrevious()
    }

    // Volume keys turn pages instead of changing the volume. The WebView takes the window focus on
    // touch, so a Compose preview-key handler would never see the press: the reader registers a
    // window-level handler that MainActivity offers before the view hierarchy (see
    // MainActivity.dispatchKeyEvent) and consumes, leaving the system volume untouched.
    VolumeKeyPagingEffect(
        enabled = readerSettings.volumeButtonPageTurnEnabled &&
            !sheetOpen && !showingEndPage && !showingPreviousPage,
        rtl = rtl,
    ) { page ->
        turnPage(page == VolumeKeyPage.Next)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground),
    ) {
        AndroidView(
            factory = { context ->
                val client = EpubWebViewClient(
                    resolveResource = viewModel::fetchResource,
                    onPageReady = { view ->
                        if (injectedForToken != loadToken) {
                            injectedForToken = loadToken
                            view.evaluateJavascript(EpubJs.injectScript(latestConfig), null)
                            view.evaluateJavascript("window.__kmLoadToken = $loadToken", null)
                            val fraction = pendingScroll
                            pendingScroll = null
                            if (fraction != null && fraction > 0f) {
                                // Queued inside the JS until the measure pass has sized the pages;
                                // the reveal then comes from the pagination callback (safety timer in
                                // the restorePositioned effect covers a dead page)
                                view.evaluateJavascript("window.__kmScrollToFraction($fraction)", null)
                            }
                        }
                    },
                    onNavigate = { href, anchor -> navigateHandler.value(href, anchor) },
                    resolveFont = viewModel::fetchLocalFont,
                )
                createEpubWebView(
                    context,
                    bridge,
                    client,
                    onBottomOverscroll = { onBottomOverscroll.value() },
                    onTopOverscroll = { onTopOverscroll.value() },
                ).also { webView = it }
            },
            update = { view ->
                view.pagedMode = themeConfig.paged
                view.onSwipe = { dx, vx -> swipeHandler.value(dx, vx) }
                view.onWheelTurn = { forward -> turnPage(forward) }
                // Finger-following page drag (the comic pager's feel). MotionEvent deltas are
                // physical px but the paged strip lives in CSS px: with the viewport pinned to
                // the device width at scale 1 the ratio is exactly the screen density.
                val cssDensity = view.resources.displayMetrics.density
                view.onDrag = { dx ->
                    if (themeConfig.paged) {
                        view.evaluateJavascript(
                            "window.__kmDragTo && window.__kmDragTo(${dx / cssDensity})",
                            null,
                        )
                    }
                }
                // The WebView's own background paints before the chapter CSS does: without this
                // it flashes white in dark mode on every chapter load
                view.setBackgroundColor(android.graphics.Color.parseColor(themeConfig.background))
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    // Hidden until the restored page position has been applied (see onPageReady).
                    // 0.01 instead of 0 (the iOS reader does the same): HWUI skips drawing a
                    // zero-alpha view, the WebView's draw functor then never runs, Chromium gets
                    // no BeginFrames and the surface stays FROZEN on the last pre-hide frame —
                    // revealing it flashes that stale frame (the chapter start you turned away
                    // from) for a frame or two. 0.01 is invisible but keeps frames flowing, so
                    // the reveal shows the live, already-restored page.
                    .alpha(if (restorePositioned) 1f else 0.01f),
        )

        // Frozen copy of the previous page: covers the blank WebView window between loadUrl and
        // the positioned reveal, then swaps out INSTANTLY (no crossfade — iOS/readest reveal the
        // new page with a hard cut; a fade makes a backward turn flash the old chapter's first
        // page over the revealed last page). draw() ignores the Compose alpha gate, so the
        // capture still works while the WebView is hidden.
        if (!restorePositioned) {
            chapterSnapshot?.let { snapshot ->
                Image(
                    bitmap = snapshot,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (chapterSnapshot != null) {
            SideEffect { chapterSnapshot = null }
        }

        if (state.loading) {
            // The WebView JS tap bridge is not injected until the chapter page is ready,
            // so handle taps here to let the chrome toggle while loading
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { toggleChrome() } },
            )
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = pageContent,
            )
        }
        state.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = pageContent.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }

        // Margin notes (mirrors the iOS WebPubInfoOverlay): configurable header/footer slots
        // around the page, reduced to the two "controls" slots while the chrome is up. Pure
        // text — touches fall through to the WebView below.
        if (!showingEndPage && !showingPreviousPage) {
            EpubReadingOverlay(
                prefs = epubOverlay,
                showingControls = chromeShown,
                paged = themeConfig.paged,
                // Mirrors iOS: oneshot shows the book title and hides the series line
                bookTitle = if (book.oneshot) book.title else book.seriesTitle,
                chapterTitle = state.chapters.getOrNull(state.currentChapterIndex)?.title,
                totalProgression = scrub ?: state.totalProgression,
                currentPage = currentPage,
                totalPages = if (themeConfig.paged) pageCount else 0,
                chapterProgressFraction = if (themeConfig.paged) null else lastFraction,
                isRtl = rtl || jsDirection == 2,
                textColor = pageContent,
                drawUnderCutout = readerSettings.drawUnderCutout,
            )
        }

        // Floating chrome (mirrors the iOS controlsOverlay): the round close button now sits
        // top-leading (its old top-trailing slot carries the bookmark toggle), and the round
        // quick-actions button stays bottom-trailing with the capsule panel that holds the
        // scrubber, contents, book info, settings and the neighbouring books.
        AnimatedVisibility(
            visible = chromeShown,
            modifier = Modifier.align(Alignment.TopStart),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(top = 8.dp, start = 12.dp),
            ) {
                FloatingChromeButton(onClick = onBack, background = chromeBackground, border = chromeBorder) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.epub_close),
                        tint = pageContent,
                    )
                }
            }
        }

        // Bookmark toggle, on the trailing edge the close button used to own. Incognito reading
        // keeps no bookmarks, so the same slot shows the incognito capsule instead of a bookmark
        // button that could not do anything.
        AnimatedVisibility(
            visible = chromeShown,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(top = 8.dp, end = 12.dp),
            ) {
                if (incognito) {
                    // An indicator, not an action: no click target, dimmed like the old bare icon.
                    FloatingChromeButton(
                        onClick = null,
                        background = chromeBackground,
                        border = chromeBorder,
                    ) {
                        Icon(
                            Icons.Filled.VisibilityOff,
                            contentDescription = stringResource(R.string.reader_incognito),
                            tint = pageContent.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    FloatingChromeButton(
                        onClick = viewModel::toggleBookmark,
                        background = chromeBackground,
                        border = chromeBorder,
                    ) {
                        Icon(
                            imageVector = if (bookmarkedHere) {
                                Icons.Filled.Bookmark
                            } else {
                                Icons.Outlined.BookmarkBorder
                            },
                            contentDescription = stringResource(
                                if (bookmarkedHere) {
                                    R.string.epub_bookmark_remove
                                } else {
                                    R.string.epub_bookmark_add
                                },
                            ),
                            tint = pageContent,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chromeShown,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(end = 12.dp, bottom = 16.dp),
            ) {
                // Quick-actions panel (mirrors the iOS quickActionsPanel): trailing-aligned
                // capsules above the menu button; the label leads, the icon trails.
                AnimatedVisibility(
                    visible = quickActionsExpanded,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 10.dp),
                    ) {
                        // Total-progress scrubber (the old bottom bar's slider lives in the panel
                        // now, so the reading surface stays chrome-free)
                        val progress = scrub ?: state.totalProgression
                        Slider(
                            value = progress,
                            onValueChange = { scrub = it },
                            onValueChangeFinished = {
                                scrub?.let(viewModel::seekTo)
                                scrub = null
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = pageContent,
                                activeTrackColor = pageContent,
                                inactiveTrackColor = pageContent.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier
                                .width(240.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(chromeBackground)
                                .padding(horizontal = 12.dp),
                        )
                        QuickActionCapsule(
                            // The contents capsule doubles as the progress readout (mirrors the
                            // iOS "Contents · 42.3%")
                            text = stringResource(R.string.epub_toc) +
                                " · " + "%.1f%%".format(progress * 100f),
                            icon = Icons.AutoMirrored.Filled.List,
                            background = chromeBackground,
                            border = chromeBorder,
                            contentColor = pageContent,
                            onClick = {
                                quickActionsExpanded = false
                                showToc = true
                            },
                        )
                        QuickActionCapsule(
                            text = stringResource(R.string.epub_book_info),
                            icon = Icons.Outlined.Info,
                            background = chromeBackground,
                            border = chromeBorder,
                            contentColor = pageContent,
                            onClick = {
                                quickActionsExpanded = false
                                showDetailSheet = true
                            },
                        )
                        QuickActionCapsule(
                            text = stringResource(R.string.reader_settings_title),
                            icon = Icons.Filled.Settings,
                            background = chromeBackground,
                            border = chromeBorder,
                            contentColor = pageContent,
                            onClick = {
                                quickActionsExpanded = false
                                showSettings = true
                            },
                        )
                        // Neighbouring books (the old overflow menu entries): the read list order,
                        // else the series; another reader takes over through onOpenBook. Both
                        // sides resume on their own progress, never on the end page.
                        state.adjacent.previous?.let { previous ->
                            QuickActionCapsule(
                                text = stringResource(R.string.reader_menu_previous_book),
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                background = chromeBackground,
                                border = chromeBorder,
                                contentColor = pageContent,
                                onClick = {
                                    quickActionsExpanded = false
                                    viewModel.flushReport()
                                    onOpenBook(previous, false)
                                },
                            )
                        }
                        state.adjacent.next?.let { next ->
                            QuickActionCapsule(
                                text = stringResource(R.string.reader_menu_next_book),
                                icon = Icons.AutoMirrored.Filled.ArrowForward,
                                background = chromeBackground,
                                border = chromeBorder,
                                contentColor = pageContent,
                                onClick = {
                                    quickActionsExpanded = false
                                    viewModel.flushReport()
                                    onOpenBook(next, false)
                                },
                            )
                        }
                    }
                }
                FloatingChromeButton(
                    onClick = { quickActionsExpanded = !quickActionsExpanded },
                    background = chromeBackground,
                    border = chromeBorder,
                ) {
                    Icon(
                        if (quickActionsExpanded) Icons.Filled.Close else Icons.Filled.Menu,
                        contentDescription = stringResource(R.string.epub_quick_actions),
                        tint = pageContent,
                    )
                }
            }
        }

        // Link-jump return chip (mirrors readest's history back() affordance): after following
        // an in-book link, a floating pill offers the trip back to where the jump started.
        if (linkBackStack.isNotEmpty() && !showingEndPage && !showingPreviousPage) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(start = 16.dp, bottom = 16.dp)
                    .clip(CircleShape)
                    .background(chromeBackground)
                    .clickable {
                        val target = linkBackStack.last()
                        linkBackStack = linkBackStack.dropLast(1)
                        if (target.first != state.currentChapterIndex) {
                            pendingScroll = target.second
                            if (target.second > 0f) restorePositioned = false
                            viewModel.goToChapter(target.first)
                        } else {
                            webView?.evaluateJavascript(
                                "window.__kmScrollToFraction(${target.second})",
                                null,
                            )
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = pageContent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.epub_end_return),
                    color = pageContent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // Note bubble (replaces the old "jump to the note, return chip back" behaviour for
        // references whose body is in the same file; the chip above still serves the cross-file
        // ones, which the page JS cannot extract). Dismissal is the Popup's own: back press and a
        // tap outside both route to onDismiss, so it folds before the chrome or the reader does.
        footnoteNote?.let { note ->
            EpubFootnotePopup(
                note = note,
                themeConfig = themeConfig,
                pageBackground = pageBackground,
                contentColor = pageContent,
                fetchResource = viewModel::fetchResource,
                onDismiss = { footnoteNote = null },
                onFollowLink = { href, fragment ->
                    footnoteNote = null
                    navigateHandler.value(EpubWeb.hrefFromUrl(href) ?: href, fragment)
                },
            )
        }

        // System back dismisses a transition page before leaving the reader (the old end page's
        // "Return" button role); with the bars up it folds the chrome first (readest-style
        // back-key interception), so an accidental back never drops the book outright.
        BackHandler(enabled = showingEndPage) { showingEndPage = false }
        BackHandler(enabled = showingPreviousPage) { showingPreviousPage = false }
        BackHandler(enabled = chromeVisible && !showingEndPage && !showingPreviousPage) {
            chromeVisible = false
            quickActionsExpanded = false
        }
        // With the panel open, back folds the panel first (it is composed last, so it wins)
        BackHandler(enabled = quickActionsExpanded && chromeVisible) { quickActionsExpanded = false }

        // End page (mirrors the DIVINA end-page cell): the current book's card on the leading
        // side, the next book (or the caught-up state) trailing, so finishing a book rolls
        // straight into the next one. A swipe past the bottom of the last chapter opens it.
        if (showingEndPage) {
            ReaderEndPage(
                book = book,
                adjacent = state.adjacent,
                unreadCount = null,
                relationTitle = state.readListName ?: book.seriesTitle,
                isRtl = rtl,
                contentColor = pageContent,
                onOpenNext = { nextBook ->
                    viewModel.flushReport()
                    onOpenBook(nextBook, false)
                },
                onRetry = viewModel::retryAdjacent,
                onClose = onBack,
                // Tapping the current book's card returns into it (the old page's "Return")
                onOpenPrevious = { showingEndPage = false },
                modifier = Modifier
                    .fillMaxSize()
                    .background(pageBackground)
                    // Swallow taps so they cannot reach the WebView behind the end page
                    .pointerInput(Unit) { detectTapGestures { } },
            )
        }

        // Previous-book transition page (mirror image of the end page): a swipe past the top of
        // chapter 0 offers the previous book; its "next" side is this book and dismisses back
        // into it (mirrors the DIVINA PreviousEnd cell).
        val adjacentPrevious = state.adjacent.previous
        if (showingPreviousPage && adjacentPrevious != null) {
            ReaderEndPage(
                book = adjacentPrevious,
                adjacent = ReaderAdjacentBooks(next = book, loaded = true),
                unreadCount = null,
                relationTitle = state.readListName ?: adjacentPrevious.seriesTitle,
                isRtl = rtl,
                contentColor = pageContent,
                onOpenNext = { showingPreviousPage = false },
                onRetry = viewModel::retryAdjacent,
                onClose = onBack,
                onOpenPrevious = {
                    viewModel.flushReport()
                    onOpenBook(adjacentPrevious, false)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(pageBackground)
                    .pointerInput(Unit) { detectTapGestures { } },
            )
        }
    }

    if (showSettings) {
        val serverFontLabel = stringResource(R.string.fonts_server)
        val downloadedLabel = stringResource(R.string.fonts_downloaded)
        EpubSettingsSheet(
            settings = epubSettings,
            onSettingsChange = { new -> settingsViewModel.updateEpub { new } },
            common = readerSettings,
            onCommonUpdate = { transform -> settingsViewModel.update(transform) },
            customFonts = downloadedFamilies.sorted().map { family ->
                CustomFont(
                    id = family.hashCode(),
                    name = family,
                    fileName = serverFontLabel,
                    sizeLabel = downloadedLabel,
                )
            },
            onOpenCustomFonts = {
                showSettings = false
                showCustomFonts = true
            },
            overlay = epubOverlay,
            onOverlayChange = { new -> settingsViewModel.updateEpubOverlay { new } },
            onDismiss = { showSettings = false },
        )
    }
    if (showCustomFonts) {
        // Full-page overlay inside the reader (mirrors the AppRoot SettingsPage full-page route;
        // there is no cross-screen navigation available and the reader stays on top of the stack)
        CustomFontsScreen(onBack = {
            showCustomFonts = false
            viewModel.refreshFonts()
            showSettings = true
        })
    }
    if (showToc) {
        EpubChapterSheet(
            chapters = state.chapters,
            toc = state.toc,
            currentIndex = state.currentChapterIndex,
            bookmarks = bookmarks,
            onBookmarkJump = { bookmark -> jumpToBookmark(bookmark) },
            onBookmarkDelete = { bookmark -> viewModel.deleteBookmark(bookmark.id) },
            onJump = { index, anchor ->
                showToc = false
                if (index == state.currentChapterIndex && anchor != null) {
                    // The toc entry lives in the file already on screen: "jumping" to that
                    // chapter would be a silent no-op (selectChapter with an unchanged index
                    // never reloads), so move inside the current page box instead. The anchor
                    // is an element id, which __kmGoToAnchor resolves to the column it starts in.
                    webView?.evaluateJavascript(
                        "window.__kmGoToAnchor(${JSONObject.quote(anchor)})",
                        null,
                    )
                } else {
                    // Different file: hand the anchor to the measure pass of the newly loaded
                    // chapter (the same slot in-book links use), then switch chapters.
                    pendingAnchor = anchor
                    pendingAnchorChapter = index
                    viewModel.goToChapter(index)
                }
            },
            onDismiss = { showToc = false },
        )
    }
    if (showDetailSheet) {
        ReaderDetailSheet(
            book = book,
            onDismiss = { showDetailSheet = false },
            onVisitSeries = onVisitSeries,
            onOpenReadList = onOpenReadList,
            onBrowseFilter = onBrowseFilter,
        )
    }
}

/**
 * Freeze the currently displayed page into a bitmap (chapter-change crossfade). draw() renders
 * the WebView's own content and ignores the Compose alpha gate, so this also works while the
 * WebView is hidden mid-restore.
 */
private fun EpubScrollWebView.takeSnapshot(): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    return runCatching {
        val bitmap = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        draw(android.graphics.Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()
}

/**
 * Round chrome button on a tinted disc (mirrors the iOS ReaderControlButtonStyle circle).
 *
 * A null [onClick] renders the same disc as a status indicator instead of a button: the chrome
 * carries one non-interactive capsule (the incognito marker) that must not look like a control.
 */
@Composable
private fun FloatingChromeButton(
    onClick: (() -> Unit)?,
    background: Color,
    border: Color,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, border, CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    // Inert, but still swallows the tap: this disc sits inside the top-trailing
                    // page-turn zone, so letting it fall through would turn the page.
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                },
            ),
    ) {
        content()
    }
}

/** Capsule of the quick-actions panel (mirrors the iOS quickActionButton: label leads, icon trails). */
@Composable
private fun QuickActionCapsule(
    text: String,
    icon: ImageVector,
    background: Color,
    border: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = contentColor)
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
    }
}

/** Flattened toc row (path identifies the expanded state, depth drives the indentation). */
private data class TocRow(
    val link: EpubLinkDto,
    val path: String,
    val depth: Int,
    val hasChildren: Boolean,
)

private fun flattenToc(
    links: List<EpubLinkDto>,
    expanded: Set<String>,
    pathPrefix: String,
    depth: Int,
    out: MutableList<TocRow>,
) {
    links.forEachIndexed { index, link ->
        val path = if (pathPrefix.isEmpty()) "$index" else "$pathPrefix/$index"
        val children = link.children.orEmpty()
        out += TocRow(link, path, depth, children.isNotEmpty())
        if (children.isNotEmpty() && path in expanded) {
            flattenToc(children, expanded, path, depth + 1, out)
        }
    }
}

private fun tocContainsHref(href: String, links: List<EpubLinkDto>): Boolean =
    links.any { EpubHref.matches(it.href, href) || tocContainsHref(href, it.children.orEmpty()) }

/** Containers holding the current entry are expanded by default (mirrors the DisclosureGroup init in EpubTocSheetView). */
private fun initialTocExpanded(
    links: List<EpubLinkDto>,
    currentHref: String?,
    pathPrefix: String = "",
    into: MutableSet<String> = mutableSetOf(),
): Set<String> {
    if (currentHref == null) return into
    links.forEachIndexed { index, link ->
        val path = if (pathPrefix.isEmpty()) "$index" else "$pathPrefix/$index"
        val children = link.children.orEmpty()
        if (children.isNotEmpty() && tocContainsHref(currentHref, children)) {
            into += path
            initialTocExpanded(children, currentHref, path, into)
        }
    }
    return into
}

private fun firstDescendantHref(link: EpubLinkDto): String? =
    link.href ?: link.children.orEmpty().firstNotNullOfOrNull(::firstDescendantHref)

/**
 * `#fragment` of a manifest/toc href, URL-decoded (null when the href carries none).
 *
 * A toc entry usually points at a section *inside* a spine file (`text00005.html#CHP5-1-3`), and
 * many books pack dozens of entries into one file — 卡拉马佐夫兄弟 puts 28 of its 119 entries in a
 * single 372-page file. Dropping the fragment collapses every one of them onto the file's first
 * page, which is what "章节跳转不对" looks like.
 */
private fun hrefAnchorFragment(href: String): String? {
    val raw = href.substringAfter('#', "")
    if (raw.isEmpty()) return null
    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    return decoded.ifEmpty { null }
}

/** Manifest toc sheet (mirrors EpubTocSheetView: hierarchical indentation, expandable containers, bookmark highlight on the current entry). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpubChapterSheet(
    chapters: List<EpubChapter>,
    toc: List<EpubLinkDto>,
    currentIndex: Int,
    bookmarks: List<EpubBookmark>,
    onBookmarkJump: (EpubBookmark) -> Unit,
    onBookmarkDelete: (EpubBookmark) -> Unit,
    onJump: (Int, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Contents is the tab the sheet opens on; bookmarks are one tap away instead of a second sheet
    var tab by remember { mutableIntStateOf(0) }
    val currentHref = chapters.getOrNull(currentIndex)?.href
    var expanded by remember { mutableStateOf(initialTocExpanded(toc, currentHref)) }
    val rows = remember(toc, expanded) {
        mutableListOf<TocRow>().also { flattenToc(toc, expanded, "", 0, it) }
    }
    // Open scrolled to the current entry, roughly centred (mirrors the iOS ScrollViewReader
    // scrollTo(currentLink, anchor: .center) and readest's scrollToIndex align:center)
    val listState = rememberLazyListState()
    val tocCenterOffset = with(LocalDensity.current) { -180.dp.roundToPx() }
    LaunchedEffect(Unit) {
        val target = if (rows.isEmpty()) {
            currentIndex
        } else {
            rows.indexOfFirst { it.link.href != null && EpubHref.matches(it.link.href, currentHref ?: "") }
        }
        if (target > 0) listState.scrollToItem(target, tocCenterOffset)
    }

    fun jumpTo(link: EpubLinkDto) {
        val href = firstDescendantHref(link) ?: return
        val index = chapters.indexOfFirst { EpubHref.matches(it.href, href) }
        // Carry the fragment: the entry names a section inside the file, and files are routinely
        // shared by several entries (see hrefAnchorFragment). Null keeps the plain chapter jump.
        if (index >= 0) onJump(index, hrefAnchorFragment(href))
    }

    AppModalDialog(onDismissRequest = onDismiss) {
        // Tabs double as the sheet header (no separate title line: the sheet is a navigation
        // surface, and both tabs name what they hold)
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            divider = { HorizontalDivider() },
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.epub_toc), maxLines = 1) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.epub_bookmarks), maxLines = 1) },
            )
        }
        if (tab == 1) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (bookmarks.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.epub_bookmarks_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 24.dp),
                        )
                    }
                } else {
                    items(bookmarks.size, key = { position -> bookmarks[position].id }) { position ->
                        val bookmark = bookmarks[position]
                        BookmarkEntryRow(
                            title = bookmark.chapterTitle.ifBlank { "#${bookmark.chapterIndex + 1}" },
                            progress = bookmark.totalProgression,
                            onClick = { onBookmarkJump(bookmark) },
                            onDelete = { onBookmarkDelete(bookmark) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            // Dialog card bounds the height, so the toc list just takes the remaining space below
            // the tabs and scrolls inside the card.
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                if (rows.isEmpty()) {
                    // Missing toc: fall back to a flat readingOrder list
                    items(chapters.size) { index ->
                        val chapter = chapters[index]
                        val current = index == currentIndex
                        TocEntryRow(
                            title = chapter.title,
                            depth = 0,
                            hasChildren = false,
                            expanded = false,
                            isCurrent = current,
                            onClick = { onJump(index, null) },
                            onToggle = {},
                        )
                    }
                } else {
                    items(rows.size) { index ->
                        val row = rows[index]
                        val isCurrent = EpubHref.matches(row.link.href, currentHref)
                        TocEntryRow(
                            title = row.link.title ?: row.link.href ?: "",
                            depth = row.depth,
                            hasChildren = row.hasChildren,
                            expanded = row.path in expanded,
                            isCurrent = isCurrent,
                            onClick = { jumpTo(row.link) },
                            onToggle = {
                                expanded = if (row.path in expanded) {
                                    expanded - row.path
                                } else {
                                    expanded + row.path
                                }
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** One bookmark in the sheet: chapter title, whole-book progress, jump on tap, delete trailing. */
@Composable
private fun BookmarkEntryRow(
    title: String,
    progress: Float,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                "%.1f%%".format(progress * 100f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.epub_bookmark_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Single toc row: indent by depth, container shows expand toggle, current shows bookmark. */
@Composable
private fun TocEntryRow(
    title: String,
    depth: Int,
    hasChildren: Boolean,
    expanded: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (20 + depth * 16).dp, end = 8.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isCurrent) FontWeight.Bold else null,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = stringResource(R.string.epub_toc_current),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(18.dp),
            )
        }
        if (hasChildren) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) {
                        stringResource(R.string.epub_collapse)
                    } else {
                        stringResource(R.string.epub_expand)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** EpubReaderSettings → WebView injected config (theme colors mirror the iOS ReaderTheme hexes). */
private fun mapEpubThemeConfig(
    settings: EpubReaderSettings,
    dark: Boolean,
    fontCss: String?,
    rtl: Boolean = false,
    viewportWidthDp: Int = 0,
    readingProgression: String? = null,
    pageInsets: EpubPageInsets = EpubPageInsets(),
    /** Cutout band to clip at the scroll viewport in scrolled mode; 0 when content may draw under it. */
    cutoutTopVPx: Int = 0,
    cutoutBottomVPx: Int = 0,
): EpubThemeConfig {
    // Every family resolves to a light/dark pair, like the iOS ReaderTheme light*/dark* cases —
    // the old mapping had grey placeholders for sepia/green and a mixed-up quiet pair.
    val (background, foreground) = when (normalizeEpubTheme(settings.theme)) {
        EPUB_THEME_QUIET -> if (dark) "#1E1E1E" to "#BDBDBD" else "#FAFAFA" to "#111111"
        EPUB_THEME_SEPIA -> if (dark) "#382E25" to "#E3D5C1" else "#F4ECD8" to "#5C4A37"
        EPUB_THEME_INK_GREEN -> if (dark) "#1B261B" to "#D1E0D1" else "#C7EDCC" to "#1A3A1F"
        else -> if (dark) "#000000" to "#E0E0E0" else "#FFFFFF" to "#000000"
    }
    val fontFamily = when (normalizeEpubTypeface(settings.typeface)) {
        EPUB_TYPEFACE_PUBLISHER -> ""
        EPUB_TYPEFACE_SYSTEM -> "serif"
        else -> cssFontFamilyValue(settings.typeface)
    }
    return EpubThemeConfig(
        background = background,
        foreground = foreground,
        fontSizePx = settings.fontSizeSp.toInt().coerceIn(12, 32),
        fontFamily = fontFamily,
        lineHeight = settings.lineHeight,
        marginHPx = (settings.margins * 24f).toInt().coerceIn(8, 64),
        publisherStyles = settings.publisherStyles,
        fontCss = fontCss,
        rtl = rtl,
        // Manifest-declared vertical (ttb/btt): forces writing-mode even when the publisher
        // styles omit it. Content-declared vertical is detected in the page JS instead.
        vertical = readingProgression?.lowercase() in listOf("ttb", "btt"),
        paged = normalizeEpubFlowStyle(settings.flowStyle) == EPUB_FLOW_PAGED,
        colCount = when (normalizeEpubColumns(settings.columns)) {
            EPUB_COLUMNS_DUAL -> 2
            // Mirrors the iOS resolvedReadiumColumnCount auto rule: paged-only, 900dp wide
            // viewport or wider gets two columns (tablets, landscape, desktop windows)
            EPUB_COLUMNS_AUTO -> if (viewportWidthDp >= 900) 2 else 1
            else -> 1
        },
        // The slider is 0..1 (0.4 = regular); the CSS weight steps by 100 (mirrors the iOS
        // EpubThemePreferences font-weight mapping)
        fontWeight = (100 + (settings.fontWeight * 8f).roundToInt() * 100).coerceIn(100, 900),
        animatePageTurns = settings.animatePageTurns,
        insetVPx = pageInsets.topVPx,
        insetBottomVPx = pageInsets.bottomVPx,
        insetLeftVPx = pageInsets.leftVPx,
        insetRightVPx = pageInsets.rightVPx,
        cutoutTopVPx = cutoutTopVPx,
        cutoutBottomVPx = cutoutBottomVPx,
        // Every family resolves to a light/dark pair, so "dark theme" is exactly the dark slot
        // being active — drives the JS dark-mode overrides (links, hard-coded colors, images).
        darkTheme = dark,
    )
}

/** CSS font-family value (mirrors the original cssFontFamilyValue: quoted when it contains spaces or quotes). */
private fun cssFontFamilyValue(name: String): String =
    if (name.any { it == ' ' || it == '"' }) {
        "\"${name.replace("\"", "\\\"")}\""
    } else {
        name
    }
