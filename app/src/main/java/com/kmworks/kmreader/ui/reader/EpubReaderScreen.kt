package com.kmworks.kmreader.ui.reader

import android.app.Activity
import android.content.Context
import com.kmworks.kmreader.core.settings.AppSettingsStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.epub.EpubChapter
import com.kmworks.kmreader.core.epub.EpubJs
import com.kmworks.kmreader.core.epub.EpubJsBridge
import com.kmworks.kmreader.core.epub.EpubLinkDto
import com.kmworks.kmreader.core.epub.EpubRepository
import com.kmworks.kmreader.core.epub.EpubResource
import com.kmworks.kmreader.core.epub.EpubThemeConfig
import com.kmworks.kmreader.core.epub.EpubWeb
import com.kmworks.kmreader.core.epub.EpubWebViewClient
import com.kmworks.kmreader.core.epub.createEpubWebView
import com.kmworks.kmreader.core.fonts.FontRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.CustomFont
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

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
)

/**
 * EPUB reader state (mirrors the WebPub manifest route of EpubReaderView):
 * manifest → chapters, positions → chapter weights, progression GET restores / PUT reports throttled.
 */
@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val epubRepository: EpubRepository,
    private val fontRepository: FontRepository,
    private val appSettings: AppSettingsStore,
    @ApplicationContext private val context: Context,
    private val actionRepository: ActionRepository,
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

    private var bookDtoId: String? = null
    private var loadedBookId: Int = -1
    private var weightsByHref: Map<String, Float> = emptyMap()
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

    /** Loads the manifest, positions and the saved progression. */
    fun load(book: Book) {
        if (loadedBookId == book.id) return
        loadedBookId = book.id
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
            val manifest = epubRepository.manifest(dtoId)
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
                    href = link.href.orEmpty(),
                    title = link.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.epub_chapter_fallback, index + 1),
                )
            }
            equalWeight = 1f / chapters.size
            val positions = epubRepository.positions(dtoId)
            val positionsTotal = positions?.total ?: 0
            weightsByHref = positions
                ?.takeIf { it.total > 0 }
                ?.positions
                ?.groupingBy { it.href }
                ?.eachCount()
                ?.mapValues { it.value.toFloat() / positionsTotal }
                .orEmpty()
            estimatedTotalPages = positionsTotal

            // Restore saved progression (mirrors decodeRemoteWebPubProgression fallback to start)
            val saved = epubRepository.savedProgression(dtoId)
            wasInProgress = saved != null
            sessionStartTotalProgression = null
            hasRecorded = false
            var startIndex = 0
            var startFraction: Float? = null
            if (saved != null) {
                val index = chapters.indexOfFirst { it.href == saved.locator.href }
                if (index >= 0) {
                    startIndex = index
                    startFraction = saved.locator.locations?.progression
                }
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
            )
        }
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
        val base = epubRepository.baseUrl ?: return null
        val url = EpubWeb.resourceRequestUrl(base, dtoId, href)
        val resource = epubRepository.fetchResourceBlocking(url) ?: return null
        return if (resource.mimeType == "application/octet-stream") {
            EpubResource(resource.data, EpubWeb.mediaTypeFor(href), resource.encoding)
        } else {
            resource
        }
    }

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
        val total = state.totalProgression
        // Completed = last chapter scrolled to its end (approximates the original reachedLastPage:
        // Android only knows the in-chapter fraction)
        val completed = state.currentChapterIndex >= state.chapters.lastIndex && fraction >= 0.99f
        if (!admitsRecording(total, completed)) return
        val launchScope = if (flush) appScope else viewModelScope
        launchScope.launch {
            val ok = epubRepository.reportProgression(
                bookDtoId = dtoId,
                href = chapter.href,
                chapterFraction = fraction,
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
        weightsByHref[chapter.href] ?: equalWeight

    private fun totalFor(chapters: List<EpubChapter>, index: Int, fraction: Float): Float {
        var acc = 0f
        chapters.take(index).forEach { acc += weightOf(it) }
        return (acc + fraction * (chapters.getOrNull(index)?.let { weightOf(it) } ?: 0f))
            .coerceIn(0f, 1f)
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
    onVisitSeries: (Book) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onBrowseFilter: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(book.id, incognito) {
        viewModel.incognito = incognito
        viewModel.load(book)
    }
    if (state.hasSession) {
        RealEpubReader(
            book = book,
            state = state,
            viewModel = viewModel,
            settingsViewModel = settingsViewModel,
            onBack = onBack,
            incognito = incognito,
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
    onVisitSeries: (Book) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onBrowseFilter: (String) -> Unit = {},
) {
    // EPUB preferences are persisted globally (mirrors the original @AppStorage epubPreferences), so
    // the reader and the defaults page share the same copy
    val epubSettings by settingsViewModel.epubSettings.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val downloadedFamilies by viewModel.downloadedFamilies.collectAsStateWithLifecycle()
    val fontCss = remember(epubSettings.typeface, downloadedFamilies) {
        if (epubSettings.typeface in downloadedFamilies) {
            viewModel.fontCssFor(epubSettings.typeface)
        } else {
            null
        }
    }
    val themeConfig = remember(epubSettings, dark, fontCss) {
        mapEpubThemeConfig(epubSettings, dark, fontCss)
    }
    var latestConfig by remember { mutableStateOf(themeConfig) }
    SideEffect { latestConfig = themeConfig }

    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showCustomFonts by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var pendingScroll by remember { mutableStateOf<Float?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The WebView stays invisible until the restored progression has been applied: the chapter is
    // loaded from its top, so without this the first paint shows the chapter start and the injected
    // scroll only lands one or more frames later (same first-frame jump as the pager's cell 0)
    var restorePositioned by remember { mutableStateOf(false) }

    val bridge = remember {
        EpubJsBridge(
            onMetrics = viewModel::onMetrics,
            onTap = { chromeVisible = !chromeVisible },
        )
    }

    LaunchedEffect(state.scrollToFraction) {
        state.scrollToFraction?.let {
            pendingScroll = it
            viewModel.consumeScroll()
        }
    }
    // Never leave the WebView hidden if the chapter failed to load (onPageReady may not fire)
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
    DisposableEffect(webView) {
        onDispose { webView?.destroy() }
    }

    val pageBackground = remember(themeConfig) {
        Color(android.graphics.Color.parseColor(themeConfig.background))
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
                        view.evaluateJavascript(EpubJs.injectScript(latestConfig), null)
                        val fraction = pendingScroll
                        pendingScroll = null
                        if (fraction != null && fraction > 0f) {
                            // Apply the saved progression first, then reveal the WebView once the
                            // scrolled frame has had a chance to commit; revealing inside the JS
                            // callback can still paint the chapter top for a frame
                            view.evaluateJavascript("window.__kmScrollToFraction($fraction)") {
                                view.postDelayed({ restorePositioned = true }, 48L)
                            }
                        } else {
                            // Chapter start (or no saved progression): the first paint is correct
                            restorePositioned = true
                        }
                    },
                    resolveFont = viewModel::fetchLocalFont,
                )
                createEpubWebView(context, bridge, client).also { webView = it }
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    // Hidden until the restored page position has been applied (see onPageReady)
                    .alpha(if (restorePositioned) 1f else 0f),
        )

        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }
        state.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }

        // Top bar (mirrors DIVINA chrome)
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The whole row swallows stray taps so they do not reach the WebView below and
                    // toggle the chrome
                    .pointerInput(Unit) { detectTapGestures { } }
                    .background(Color.Black.copy(alpha = 0.78f))
                    // ignoringVisibility: the layout must not jump when the system bars toggle
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.reader_back),
                        tint = Color.White,
                    )
                }
                // Incognito indicator (mirrors the eye.slash before the top-bar title in the original)
                if (incognito) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = stringResource(R.string.reader_incognito),
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // Mirrors the iOS top-bar title button: tapping the title opens the book
                        // detail sheet
                        .clickable { showDetailSheet = true },
                ) {
                    // Mirrors iOS: oneshot shows the book title and hides "Vol. N"
                    Text(
                        if (book.oneshot) book.title else book.seriesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(
                        state.chapters.getOrNull(state.currentChapterIndex)?.title
                            ?: if (book.oneshot) "EPUB" else stringResource(R.string.epub_volume, book.number),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.common_more),
                            tint = Color.White,
                        )
                    }
                    EpubReaderOptionsMenu(
                        expanded = menuExpanded,
                        items = listOf(
                            // The book-detail entry is the top-bar title (mirrors iOS), so it is not
                            // repeated in the menu
                            EpubMenuItem(stringResource(R.string.reader_settings_title)) {
                                menuExpanded = false
                                showSettings = true
                            },
                            EpubMenuItem(stringResource(R.string.epub_toc)) {
                                menuExpanded = false
                                showToc = true
                            },
                        ),
                        onDismiss = { menuExpanded = false },
                    )
                }
            }
        }

        // Bottom bar: total-progress slider + chapter capsule
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Swallow stray taps so they do not reach the WebView below
                    .pointerInput(Unit) { detectTapGestures { } }
                    .background(Color.Black.copy(alpha = 0.78f))
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                ChapterCapsule(
                    text = stringResource(
                        R.string.epub_chapter_progress,
                        state.currentChapterIndex + 1,
                        state.chapters.size,
                        (progress * 100).toInt(),
                    ),
                    onClick = { showToc = true },
                )
            }
        }
    }

    if (showSettings) {
        val serverFontLabel = stringResource(R.string.fonts_server)
        val downloadedLabel = stringResource(R.string.fonts_downloaded)
        EpubSettingsSheet(
            settings = epubSettings,
            onSettingsChange = { new -> settingsViewModel.updateEpub { new } },
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
            onJump = {
                viewModel.goToChapter(it)
                showToc = false
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

/** EPUB overflow menu item (icon optional). */
private data class EpubMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/** Overflow menu for the EPUB chrome. */
@Composable
private fun EpubReaderOptionsMenu(
    expanded: Boolean,
    items: List<EpubMenuItem>,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label) },
                leadingIcon = item.icon?.let { icon ->
                    { Icon(icon, contentDescription = null) }
                },
                onClick = item.onClick,
            )
        }
    }
}

/** Chapter progress capsule (mirrors PageJumpCapsule styling). */
@Composable
private fun ChapterCapsule(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
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
    links.any { it.href == href || tocContainsHref(href, it.children.orEmpty()) }

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

/** Manifest toc sheet (mirrors EpubTocSheetView: hierarchical indentation, expandable containers, bookmark highlight on the current entry). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpubChapterSheet(
    chapters: List<EpubChapter>,
    toc: List<EpubLinkDto>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentHref = chapters.getOrNull(currentIndex)?.href
    var expanded by remember { mutableStateOf(initialTocExpanded(toc, currentHref)) }
    val rows = remember(toc, expanded) {
        mutableListOf<TocRow>().also { flattenToc(toc, expanded, "", 0, it) }
    }

    fun jumpTo(link: EpubLinkDto) {
        val href = firstDescendantHref(link) ?: return
        val index = chapters.indexOfFirst { it.href == href }
        if (index >= 0) onJump(index)
    }

    AppModalDialog(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.epub_toc),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        // Dialog card bounds the height, so the toc list just takes the remaining space below the
        // title and scrolls inside the card.
        LazyColumn(modifier = Modifier.weight(1f)) {
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
                        onClick = { onJump(index) },
                        onToggle = {},
                    )
                }
            } else {
                items(rows.size) { index ->
                    val row = rows[index]
                    val isCurrent = row.link.href != null && row.link.href == currentHref
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

/** EpubReaderSettings → WebView injected config (theme greys align with the custom themes). */
private fun mapEpubThemeConfig(
    settings: EpubReaderSettings,
    dark: Boolean,
    fontCss: String?,
): EpubThemeConfig {
    val (background, foreground) = when (normalizeEpubTheme(settings.theme)) {
        EPUB_THEME_QUIET -> "#FAFAFA" to "#1E1E1E"
        EPUB_THEME_SEPIA -> "#F0F0F0" to "#2A2A2A"
        EPUB_THEME_INK_GREEN -> "#ECECEC" to "#202020"
        else -> if (dark) "#000000" to "#E0E0E0" else "#FFFFFF" to "#1A1A1A"
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
    )
}

/** CSS font-family value (mirrors the original cssFontFamilyValue: quoted when it contains spaces or quotes). */
private fun cssFontFamilyValue(name: String): String =
    if (name.any { it == ' ' || it == '"' }) {
        "\"${name.replace("\"", "\\\"")}\""
    } else {
        name
    }
