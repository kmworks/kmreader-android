package com.kmworks.kmreader.ui.reader

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.core.image.BorderCropTransformation
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.ReaderPage
import com.kmworks.kmreader.data.ReaderViewItem
import com.kmworks.kmreader.data.ReaderViewItemGenerator
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private val ChromeScrim = Color.Black.copy(alpha = 0.82f)
private val PageFill = Color(0xFF1C1C1F)
private val PageLine = Color.White.copy(alpha = 0.12f)
private val ChromeText = Color.White

/**
 * Comic reader (DIVINA): real page pipeline (ReaderViewModel → BookPagesApi). Every option in the
 * settings sheet is truly applied to the canvas (background / page number / shadow / page-turn
 * animation / tap zones / double tap / long press / preloading / border cropping).
 *
 * Behavior mirrors the original DivinaReaderView / ReaderViewModel 1:1:
 * - Initial page: completed or incognito starts from page 0, otherwise restores readProgress.page
 *   (the VM provides initialPage)
 * - Reading direction: with forceDefaultReadingDirection use the global default, otherwise the
 *   series direction, otherwise the global default; direction / layout / split-wide / solo cover are
 *   SESSION-scoped state — changes never write back to the defaults (mirrors @State + reset semantics)
 * - Dual page only in landscape (shouldUseDualPage: width > height && layout != single && direction
 *   not vertical/webtoon); layout == dual forces dual pages (same landscape gating)
 * - Split wide pages: none keeps pages whole / auto·ltr·rtl split into halves. Splitting
 *   only applies on a portrait screen (landscape already shows the spread at full size) and only on
 *   an upright page (a 90°/270° rotation makes the page portrait, so nothing is split); each half is a
 *   crop of one side of the page, never a scaled-down whole spread
 * - ltr / rtl: horizontal paging; vertical: vertical paging; webtoon: continuous vertical scroll
 *   (page width and tap-scroll height are adjustable)
 * - Gestures: tap zones (six layouts + inversion), double-tap zoom (can be disabled), pinch
 *   zoom/pan, long-press context menu (share / solo)
 * - Progress: ReaderProgressRecordingGate (3-page threshold / completed bypass / incognito skip),
 *   flushed on exit
 * - Preloading: ReaderPreloadProfile window + 0.3s debounce + "preload on Wi-Fi only" gating
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    book: Book,
    onBack: () -> Unit,
    incognito: Boolean = false,
    viewModel: ReaderViewModel = hiltViewModel(),
    settingsViewModel: DivinaSettingsViewModel = hiltViewModel(),
    onVisitSeries: (Book) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onBrowseFilter: (String) -> Unit = {},
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val defaultDirection by settingsViewModel.defaultDirection.collectAsStateWithLifecycle()
    val wifiOnlyPreload by settingsViewModel.wifiOnlyPreload.collectAsStateWithLifecycle()
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val readerBackground = settings.background.color(darkTheme)
    val pageContentColor = settings.background.contentColor(darkTheme)

    // Mirrors the original shouldShowControls (initially false): the chrome stays hidden while
    // loading and reading; only a tap reveals it
    var chromeVisible by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var pageInitialized by remember { mutableStateOf(false) }
    // Progress-reporting gate: do not report until the restored page has scrolled into place
    // (mirrors iOS, where the first observed page is the restored page; otherwise jumping to the
    // restored page would look like a page turn)
    var progressArmed by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Session-scoped reading preferences (mirroring the original @State initial values: seeded from
    // the defaults, never written back)
    var direction by remember { mutableStateOf<ReadingDirection?>(null) }
    var pageLayout by remember { mutableStateOf<PageLayout?>(null) }
    var splitWideMode by remember { mutableStateOf<SplitWidePageMode?>(null) }
    var soloCoverPage by remember { mutableStateOf<Boolean?>(null) }
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var contextMenuPage by remember { mutableStateOf<ReaderPage?>(null) }

    // Zoom: double tap toggles between 1x and the configured scale, pinch zooms continuously
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoomed = zoom > 1.01f

    // Keep the screen awake while reading (mirrors the original keepScreenAwakeWhileReading, default false)
    val view = LocalView.current
    DisposableEffect(view, settings.keepScreenAwake) {
        view.keepScreenOn = settings.keepScreenAwake
        onDispose { view.keepScreenOn = false }
    }

    // Immersive mode: hide system bars while the chrome is hidden, restore them when the chrome
    // appears (mirrors iOS .statusBarHidden(!shouldShowControls))
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

    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ImageLoaderEntryPoint::class.java,
        )
    }
    val sessionLoader by entryPoint.imageCacheStore().pageLoader.collectAsState()
    val imageLoader = sessionLoader ?: remember { SingletonImageLoader.get(context) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(book, incognito) {
        viewModel.incognito = incognito
        viewModel.load(book)
    }
    // Flush unreported progress when leaving the reader (mirrors the original flushProgress)
    DisposableEffect(Unit) {
        onDispose { viewModel.flushProgress(page) }
    }
    // Diagnostic trail: every reader open/close goes into the app log with the heap in use, so a death
    // between two entries can be attributed to this screen and a memory blow-up is visible right before
    // the process disappears (the log file survives the crash).
    DisposableEffect(book.id) {
        AppLog.i("Reader", "open book=${book.id} incognito=$incognito ${heapFootprint()}")
        onDispose { AppLog.i("Reader", "close book=${book.id} ${heapFootprint()}") }
    }
    val readerPages = uiState.pages
    val soloPages by viewModel.soloPages.collectAsStateWithLifecycle()
    val pageCount = readerPages.size
    val ready = uiState.status == ReaderPagesStatus.Ready

    // After loading: restore the initial page and resolve session preferences (runs only once)
    LaunchedEffect(ready) {
        if (!ready || pageInitialized) return@LaunchedEffect
        page = uiState.initialPage ?: 1
        direction = resolveReadingDirection(
            seriesDirection = uiState.seriesDirection,
            forceDefault = settings.forceDefaultDirection,
            defaultDirection = defaultDirection,
        )
        pageLayout = settings.defaultPageLayout
        splitWideMode = settings.defaultSplitWideMode
        soloCoverPage = settings.defaultSoloCoverPage
        pageInitialized = true
    }

    val directionNow = direction ?: defaultDirection
    val pageLayoutNow = pageLayout ?: settings.defaultPageLayout
    val splitWideNow = splitWideMode ?: settings.defaultSplitWideMode
    val soloCoverNow = soloCoverPage ?: settings.defaultSoloCoverPage

    val isWebtoon = directionNow == ReadingDirection.Webtoon
    val isVertical = directionNow == ReadingDirection.Vertical
    val isPaged = !isWebtoon && !isVertical

    // Dual-page gating (mirrors the original shouldUseDualPage: landscape only; no dual pages for webtoon/vertical)
    val configuration = LocalConfiguration.current
    val isLandscapeScreen = configuration.screenWidthDp > configuration.screenHeightDp
    val allowDualPairs = isLandscapeScreen && pageLayoutNow != PageLayout.Single && isPaged
    val forceDualPairs = allowDualPairs && pageLayoutNow == PageLayout.Dual
    val noCover = !(soloCoverNow && allowDualPairs)

    // Split wide pages only applies in portrait: a landscape screen already shows the spread at full
    // size, so cutting it in half there only squeezes the page. A 90°/270° page rotation turns a wide
    // page into a portrait one, which the original does not split either (its effectiveOrientation is
    // portrait, so the wide-page split eligibility check fails).
    val splitWideApplies = splitWideNow.isEnabled &&
        !isWebtoon &&
        !isLandscapeScreen &&
        rotationDegrees % 180 == 0
    // The first half is the left half of the spread, unless the split mode or the reading direction
    // reads right to left (mirrors isLeftSplitHalf)
    val firstHalfOnLeft = splitWideNow.effectiveReadingDirection(directionNow) != ReadingDirection.Rtl

    val items = remember(
        readerPages,
        allowDualPairs,
        forceDualPairs,
        splitWideNow,
        soloPages,
        noCover,
        isLandscapeScreen,
        rotationDegrees,
        directionNow,
    ) {
        ReaderViewItemGenerator.generate(
            pages = readerPages,
            allowDualPairs = allowDualPairs,
            forceDualPairs = forceDualPairs,
            splitWidePages = splitWideApplies,
            soloPages = soloPages,
            noCover = noCover,
        )
    }
    // The resume page has to be the pager's initial page, not a scroll applied after the first frame:
    // the pager state is created while the list is still loading, so opening a book mid-way would
    // paint cell 0 for a frame and only then jump to the restored cell (the first-page flash). Both
    // scroll states are therefore rebuilt when the list becomes ready, so their first layout is
    // already the cell that holds the resume page.
    val initialViewIndex = remember(ready) {
        if (!ready) {
            0
        } else {
            items.indexOfFirst { it.pages.contains(uiState.initialPage ?: 1) }.coerceAtLeast(0)
        }
    }
    val pagerState = key(ready) {
        rememberPagerState(initialPage = initialViewIndex) { items.size.coerceAtLeast(1) }
    }
    val webtoonState = key(ready) {
        rememberLazyListState(initialFirstVisibleItemIndex = initialViewIndex)
    }
    val scope = rememberCoroutineScope()

    // Page-load lifecycle in the trail: a death before Ready (or right after it) reads very differently
    // from one while paging through already decoded images.
    LaunchedEffect(uiState.status, items.size, uiState.bookDtoId) {
        AppLog.i(
            "Reader",
            "status=${uiState.status} items=${items.size} dtoId=${uiState.bookDtoId} ${heapFootprint()}",
        )
    }

    // First entry (including restored progress): scroll to the cell holding the initial page;
    // progress reporting is armed only once it is in place
    LaunchedEffect(pageInitialized, items) {
        if (!pageInitialized) return@LaunchedEffect
        if (isWebtoon) {
            val target = items.indexOfFirst { it.pages.contains(page) }.coerceAtLeast(0)
            if (target > 0) webtoonState.scrollToItem(target)
        } else {
            val target = items.indexOfFirst { it.pages.contains(page) }
            if (target > 0) pagerState.scrollToPage(target)
        }
        progressArmed = true
    }

    // Pager → page number (paged/vertical modes)
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            items.getOrNull(index)?.pages?.firstOrNull()?.let { page = it }
        }
    }
    // Webtoon scroll → page number
    LaunchedEffect(webtoonState, isWebtoon, items) {
        if (!isWebtoon) return@LaunchedEffect
        snapshotFlow { webtoonState.firstVisibleItemIndex }.collect { index ->
            items.getOrNull(index)?.pages?.firstOrNull()?.let { if (it != page) page = it }
        }
    }
    // Page number (slider/jump/thumbnails) → view
    LaunchedEffect(page, items, isWebtoon) {
        if (!pageInitialized) return@LaunchedEffect
        if (isWebtoon) {
            val target = items.indexOfFirst { it.pages.contains(page) }
            if (target >= 0 && target != webtoonState.firstVisibleItemIndex) webtoonState.scrollToItem(target)
            return@LaunchedEffect
        }
        // A wide page cut in half is two cells that both report the same page number: only seek when
        // the cell on screen does not already show this page, otherwise arriving on the second half
        // would be undone by a snap back to the first half (and going back would skip it entirely)
        if (items.getOrNull(pagerState.currentPage)?.pages?.contains(page) == true) return@LaunchedEffect
        val target = items.indexOfFirst { it.pages.contains(page) }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    // After switching layout/split-wide/direction, land on the cell holding the current page
    // (mirrors regenerateViewStatePreservingCurrentPosition)
    LaunchedEffect(
        allowDualPairs,
        forceDualPairs,
        splitWideNow,
        directionNow,
        soloPages,
        noCover,
        isLandscapeScreen,
        rotationDegrees,
    ) {
        if (!pageInitialized || isWebtoon) return@LaunchedEffect
        val target = items.indexOfFirst { it.pages.contains(page) }
        if (target >= 0) pagerState.scrollToPage(target)
    }

    // Preload window (mirrors ReaderPageLoadScheduler: 0.3s debounce + preloadProfile window + Wi-Fi-only gate)
    LaunchedEffect(pagerState, items, uiState.bookDtoId, imageLoader, settings.preloadProfile, wifiOnlyPreload) {
        snapshotFlow { pagerState.settledPage }
            .debounce(300)
            .collect { index ->
                if (uiState.bookDtoId == null) return@collect
                if (wifiOnlyPreload && !isWifiConnected(context)) return@collect
                val profile = settings.preloadProfile
                for (i in (index - profile.before)..(index + profile.after)) {
                    items.getOrNull(i)?.pages?.forEach { number ->
                        viewModel.pageUrl(number)?.let { url ->
                            imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                        }
                    }
                }
            }
    }
    // Progress reporting (paged/vertical)
    LaunchedEffect(pagerState, items, uiState.bookDtoId, progressArmed) {
        if (!progressArmed) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { index ->
            if (uiState.bookDtoId == null) return@collect
            items.getOrNull(index)?.let { viewModel.reportProgress(it.pages.max()) }
        }
    }
    // Webtoon: preloading + progress reporting
    LaunchedEffect(webtoonState, uiState.bookDtoId, isWebtoon, items, imageLoader, settings.preloadProfile, wifiOnlyPreload, progressArmed) {
        if (!isWebtoon || !progressArmed) return@LaunchedEffect
        snapshotFlow { webtoonState.firstVisibleItemIndex }
            .debounce(300)
            .collect { index ->
                if (uiState.bookDtoId == null) return@collect
                items.getOrNull(index)?.let { viewModel.reportProgress(it.pages.max()) }
                if (wifiOnlyPreload && !isWifiConnected(context)) return@collect
                val profile = settings.preloadProfile
                for (i in (index - profile.before)..(index + profile.after)) {
                    items.getOrNull(i)?.pages?.forEach { n ->
                        viewModel.pageUrl(n)?.let { url ->
                            imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                        }
                    }
                }
            }
    }

    // Single-tap/double-tap/long-press/pinch gestures (run in parallel inside one pointerInput so
    // they do not fight the Pager drag)
    fun stepPage(target: Int) {
        val clamped = target.coerceIn(0, max(0, items.lastIndex))
        scope.launch {
            if (settings.animateTapTurns) pagerState.animateScrollToPage(clamped)
            else pagerState.scrollToPage(clamped)
        }
    }
    // Tap-zone navigation: one page per tap
    fun tapNext() {
        stepPage(pagerState.currentPage + 1)
    }
    fun tapPrevious() {
        stepPage(pagerState.currentPage - 1)
    }
    val gestureModifier = Modifier.pointerInput(
        settings.tapZoneMode,
        settings.tapZoneInversion,
        settings.doubleTapZoomMode,
        settings.doubleTapZoomScale,
        settings.longPressAction,
        settings.animateTapTurns,
        isWebtoon,
        directionNow,
        items,
    ) {
        val viewSize = size
        coroutineScope {
        launch {
            detectTapGestures(
                onTap = { offset ->
                    val action = TapZoneHelper.action(
                        normalizedX = offset.x / viewSize.width,
                        normalizedY = offset.y / viewSize.height,
                        mode = settings.tapZoneMode,
                        inversion = settings.tapZoneInversion,
                        isRtl = directionNow == ReadingDirection.Rtl,
                    )
                    when (action) {
                        TapZoneAction.Previous -> scope.launch {
                            if (isWebtoon) {
                                webtoonState.animateScrollBy(-viewSize.height * settings.webtoonTapScrollPercentage / 100f)
                            } else {
                                tapPrevious()
                            }
                        }
                        TapZoneAction.Next -> scope.launch {
                            if (isWebtoon) {
                                webtoonState.animateScrollBy(viewSize.height * settings.webtoonTapScrollPercentage / 100f)
                            } else {
                                tapNext()
                            }
                        }
                        TapZoneAction.ToggleControls -> chromeVisible = !chromeVisible
                    }
                },
                onDoubleTap = {
                    if (settings.doubleTapZoomMode == DoubleTapZoomMode.Enabled) {
                        if (zoom > 1.01f) {
                            zoom = 1f
                            pan = Offset.Zero
                        } else {
                            zoom = settings.doubleTapZoomScale.coerceIn(1f, 8f)
                        }
                        zoomed = zoom > 1.01f
                    }
                },
                onLongPress = { offset ->
                    if (settings.longPressAction == LongPressAction.ContextMenu) {
                        val item = items.getOrNull(pagerState.currentPage)
                        val number = if (isWebtoon) page else item?.pages?.firstOrNull()
                        contextMenuPage = number?.let { n -> readerPages.firstOrNull { it.number == n } }
                    }
                },
            )
        }
        launch {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var multiTouch = false
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        multiTouch = true
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                            zoomed = zoom > 1.01f
                            if (zoom > 1.01f) pan += panChange
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                    if (event.changes.none { it.pressed }) break
                }
                if (!multiTouch && zoom <= 1.01f) {
                    pan = Offset.Zero
                }
            }
        }
        }
    }

    val animatedZoom by animateFloatAsState(if (zoomed) zoom else 1f, label = "readerZoom")
    val zoomModifier = Modifier.graphicsLayer {
        scaleX = animatedZoom
        scaleY = animatedZoom
        translationX = pan.x
        translationY = pan.y
    }

    // Solo action for the current page (mirrors the original ReaderPageSoloActions: a portrait page
    // inside a dual pair can be shown solo; an already-solo page can be restored)
    val currentReaderPage = readerPages.firstOrNull { it.number == page }
    val currentItem = items.getOrNull(pagerState.currentPage.coerceIn(0, max(0, items.lastIndex)))
    val soloAction: SoloMenuAction? = if (currentReaderPage != null) {
        val isSolo = currentReaderPage.number in soloPages
        val canSolo = allowDualPairs && !currentReaderPage.isLandscape && currentItem is ReaderViewItem.Dual
        if (isSolo || canSolo) {
            SoloMenuAction(
                isSolo = isSolo,
                displayPage = currentReaderPage.number,
                onToggle = { viewModel.toggleSoloPage(currentReaderPage.number) },
            )
        } else {
            null
        }
    } else {
        null
    }

    Box(modifier = Modifier.fillMaxSize().background(readerBackground)) {
        when (uiState.status) {
            ReaderPagesStatus.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = pageContentColor)
            }

            ReaderPagesStatus.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.reader_pages_load_failed),
                    style = MaterialTheme.typography.titleSmall,
                    color = pageContentColor.copy(alpha = 0.85f),
                )
                TextButton(onClick = { viewModel.retry(book) }) {
                    Text(stringResource(R.string.common_retry), color = pageContentColor)
                }
            }

            ReaderPagesStatus.Ready -> {
                when {
                    isWebtoon -> WebtoonReader(
                        items = items,
                        pageWidthPercentage = settings.webtoonPageWidthPercentage,
                        imageLoader = imageLoader,
                        settings = settings,
                        rotationDegrees = rotationDegrees,
                        state = webtoonState,
                        modifier = Modifier.fillMaxSize().then(gestureModifier),
                        pageUrl = viewModel::pageUrl,
                    )

                    isVertical -> VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().then(zoomModifier).then(gestureModifier),
                        userScrollEnabled = !zoomed,
                        contentPadding = PaddingValues(0.dp),
                        // Pre-compose adjacent pages (mirroring the offscreen preload of the iOS
                        // native pager): no loading placeholder flash while turning pages
                        beyondViewportPageCount = 1,
                    ) { index ->
                        ViewerPage(
                            item = items[index],
                            imageLoader = imageLoader,
                            settings = settings,
                            rotationDegrees = rotationDegrees,
                            contentColor = pageContentColor,
                            pageUrl = viewModel::pageUrl,
                            firstHalfOnLeft = firstHalfOnLeft,
                        )
                    }

                    else -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().then(zoomModifier).then(gestureModifier),
                        userScrollEnabled = !zoomed,
                        reverseLayout = directionNow == ReadingDirection.Rtl,
                        contentPadding = PaddingValues(0.dp),
                        // The iOS pager lays pages out edge to edge with no spacing for
                        // either transition, so pages must not be inset here either.
                        pageSpacing = 0.dp,
                        // Pre-compose adjacent pages (mirroring the offscreen preload of the iOS
                        // native pager): no loading placeholder flash while turning pages
                        beyondViewportPageCount = 1,
                    ) { index ->
                        // Cover transition: later pages stay centered while the current page slides
                        // away on top (mirrors the Cover transition)
                        val cover = settings.pageTransition == PageTransitionStyle.Cover
                        val item = items[index]
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (index == pagerState.currentPage) 1f else 0f)
                                .graphicsLayer {
                                    if (cover) {
                                        val relative = index - pagerState.currentPage - pagerState.currentPageOffsetFraction
                                        translationX = -relative * size.width
                                    }
                                },
                        ) {
                            ViewerPage(
                                item = item,
                                imageLoader = imageLoader,
                                settings = settings,
                                rotationDegrees = rotationDegrees,
                                contentColor = pageContentColor,
                                pageUrl = viewModel::pageUrl,
                                firstHalfOnLeft = firstHalfOnLeft,
                            )
                        }
                    }
                }

                // Tap zone hints (mirrors the original triggerTapZoneOverlay: when the content is
                // ready or the tap zone mode changes, a help overlay shows for ~0.4s and then hides
                // itself; it does not follow the chrome visibility)
                var tapHintsVisible by remember { mutableStateOf(false) }
                LaunchedEffect(settings.showTapZoneHints, settings.tapZoneMode, settings.tapZoneInversion) {
                    if (settings.showTapZoneHints && !settings.tapZoneMode.isDisabled) {
                        delay(100)
                        tapHintsVisible = true
                        delay(400)
                        tapHintsVisible = false
                    } else {
                        tapHintsVisible = false
                    }
                }
                if (tapHintsVisible) {
                    TapZoneHints(
                        mode = settings.tapZoneMode,
                        isRtl = directionNow == ReadingDirection.Rtl,
                        inversion = settings.tapZoneInversion,
                    )
                }

                // Page number (shown while the chrome is hidden, avoiding duplication with the bottom bar)
                if (settings.showPageNumber && !chromeVisible && pageCount > 0) {
                    Text(
                        text = "$page / $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = pageContentColor.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp),
                    )
                }

                // Reading progress bar (optional, while the chrome is hidden)
                if (settings.showProgressBar && !chromeVisible && pageCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(pageContentColor.copy(alpha = 0.15f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((page.toFloat() / pageCount).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(pageContentColor.copy(alpha = 0.85f)),
                        )
                    }
                }
            }
        }

        // Top bar
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Swallow stray taps across the whole row (mirrors iOS .contentShape(Rectangle()))
                    // so they cannot fall through to the tap-to-turn gestures
                    .pointerInput(Unit) { detectTapGestures { } }
                    .background(
                        if (settings.controlsGradient) {
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent),
                            )
                        } else {
                            Brush.verticalGradient(listOf(ChromeScrim, ChromeScrim))
                        },
                    )
                    // Avoid the status bar; ignoringVisibility keeps the layout stable while system
                    // bars show/hide
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.reader_exit_reading),
                        tint = ChromeText,
                    )
                }
                if (incognito) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = stringResource(R.string.reader_incognito),
                        tint = ChromeText.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (incognito) 8.dp else 0.dp)
                        // Mirrors the iOS top-bar title button: tapping the title opens the book detail sheet
                        .clickable { showDetailSheet = true },
                ) {
                    // Mirrors the iOS readerChapterTitle: oneshots show the book title and hide the
                    // volume-number subtitle
                    Text(
                        if (book.oneshot) book.title else book.seriesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = ChromeText,
                    )
                    if (!book.oneshot) {
                        Text(
                            book.number,
                            style = MaterialTheme.typography.bodySmall,
                            color = ChromeText.copy(alpha = 0.7f),
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more), tint = ChromeText)
                    }
                    ReaderOptionsMenu(
                        expanded = menuOpen,
                        direction = directionNow,
                        pageLayout = pageLayoutNow,
                        splitWideMode = splitWideNow,
                        rotationDegrees = rotationDegrees,
                        soloAction = soloAction,
                        soloCoverPage = soloCoverNow,
                        onDirectionChange = { direction = it },
                        onPageLayoutChange = { pageLayout = it },
                        onToggleSoloCover = { soloCoverPage = !soloCoverNow },
                        onSplitWideModeChange = { splitWideMode = it },
                        onRotationChange = { rotationDegrees = it },
                        onOpenSettings = { showSettings = true },
                        onSharePage = {
                            viewModel.pageUrl(page)?.let { url -> sharePageUrl(context, url) }
                        },
                        onOpenPageJump = { showPageJump = true },

                        onDismiss = { menuOpen = false },
                    )
                }
            }
        }

        // Bottom bar: page capsule + capsule progress bar (mirrors iOS DivinaControlsOverlayView bottomOverlayContent)
        AnimatedVisibility(
            visible = chromeVisible && uiState.status == ReaderPagesStatus.Ready,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Swallow stray taps so they cannot fall through to the tap-to-turn gestures
                    .pointerInput(Unit) { detectTapGestures { } }
                    .background(
                        if (settings.controlsGradient) {
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                            )
                        } else {
                            Brush.verticalGradient(listOf(ChromeScrim, ChromeScrim))
                        },
                    )
                    // Avoid the navigation bar; ignoringVisibility keeps the layout stable while
                    // system bars show/hide
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    PageJumpCapsule(page = page, pageCount = pageCount, onClick = { showPageJump = true })
                }
                // Capsule progress bar (mirrors iOS ReadingProgressBar: dim track, white fill, reversed in RTL)
                val fillFraction = if (pageCount > 0) (page.toFloat() / pageCount).coerceIn(0f, 1f) else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 12.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(ChromeText.copy(alpha = 0.3f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fillFraction)
                            .align(if (directionNow == ReadingDirection.Rtl) Alignment.CenterEnd else Alignment.CenterStart)
                            .background(ChromeText),
                    )
                }
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            direction = directionNow,
            onUpdate = settingsViewModel::update,
            onDismiss = { showSettings = false },
        )
    }
    if (showPageJump) {
        ReaderPageJumpSheet(
            currentPage = page,
            pageCount = pageCount,
            imageLoader = imageLoader,
            isRtl = directionNow == ReadingDirection.Rtl,
            pageThumbnailUrl = viewModel::pageThumbnailUrl,
            onJump = {
                page = it.coerceIn(1, pageCount)
                showPageJump = false
            },
            onShare = { p ->
                viewModel.pageUrl(p)?.let { url -> sharePageUrl(context, url) }
            },
            onDismiss = { showPageJump = false },
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
    contextMenuPage?.let { current ->
        val isSolo = current.number in soloPages
        val canSolo = allowDualPairs && !current.isLandscape && !isSolo
        ReaderPageMenuSheet(
            page = current,
            pageUrl = viewModel.pageUrl(current.number),
            soloAction = if (isSolo || canSolo) {
                SoloMenuAction(
                    isSolo = isSolo,
                    displayPage = current.number,
                    onToggle = { viewModel.toggleSoloPage(current.number) },
                )
            } else {
                null
            },
            onDismiss = { contextMenuPage = null },
        )
    }
}

/** Resolves the session direction (mirrors the original loadBook resolution: forced default → series direction → global default). */
private fun resolveReadingDirection(
    seriesDirection: String?,
    forceDefault: Boolean,
    defaultDirection: ReadingDirection,
): ReadingDirection {
    if (forceDefault) return defaultDirection
    val series = seriesDirection?.uppercase()?.let {
        when (it) {
            "LEFT_TO_RIGHT" -> ReadingDirection.Ltr
            "RIGHT_TO_LEFT" -> ReadingDirection.Rtl
            "VERTICAL" -> ReadingDirection.Vertical
            "WEBTOON" -> ReadingDirection.Webtoon
            else -> null
        }
    }
    return series ?: defaultDirection
}

/** Whether the network is Wi-Fi (or Ethernet): used by the "preload on Wi-Fi only" gate (mirrors the original wifiOnlyPreload). */
private fun isWifiConnected(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
}

/** Shares the current page link (mirrors the original menu share action). */
private fun sharePageUrl(context: android.content.Context, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, url)
    }
    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.reader_share_page)))
}

/** Webtoon: continuous vertical scroll without snapping; page width and tap-scroll height come from settings. */
@Composable
private fun WebtoonReader(
    items: List<ReaderViewItem>,
    pageWidthPercentage: Float,
    imageLoader: ImageLoader,
    settings: DivinaSettings,
    rotationDegrees: Int,
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    pageUrl: (Int) -> String?,
) {
    val widthFraction = (pageWidthPercentage / 100f).coerceIn(0.5f, 1f)
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        items(items.size) { index ->
            val item = items[index]
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                WebtoonItem(
                    item = item,
                    imageLoader = imageLoader,
                    settings = settings,
                    rotationDegrees = rotationDegrees,
                    contentColor = settings.background.contentColor(
                        androidx.compose.foundation.isSystemInDarkTheme(),
                    ),
                    pageUrl = pageUrl,
                    modifier = Modifier.fillMaxWidth(widthFraction),
                )
            }
        }
    }
}

/** A webtoon cell (same structure as the paged ViewerPage, but height is content-driven). */
@Composable
private fun WebtoonItem(
    item: ReaderViewItem,
    imageLoader: ImageLoader,
    settings: DivinaSettings,
    rotationDegrees: Int,
    contentColor: Color,
    pageUrl: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is ReaderViewItem.Single -> PageImage(
            page = item.page,
            url = pageUrl(item.page.number),
            imageLoader = imageLoader,
            settings = settings,
            rotationDegrees = rotationDegrees,
            contentColor = contentColor,
            modifier = modifier,
        )

        // Dual-page pair (mirrors the original dual cell: no spacing; the two pages align
        // trailing/leading toward the gutter)
        is ReaderViewItem.Dual -> Row(modifier = modifier) {
            PageImage(
                page = item.first,
                url = pageUrl(item.first.number),
                imageLoader = imageLoader,
                settings = settings,
                rotationDegrees = rotationDegrees,
                contentColor = contentColor,
                modifier = Modifier.weight(1f),
                alignment = Alignment.CenterEnd,
            )
            PageImage(
                page = item.second,
                url = pageUrl(item.second.number),
                imageLoader = imageLoader,
                settings = settings,
                rotationDegrees = rotationDegrees,
                contentColor = contentColor,
                modifier = Modifier.weight(1f),
                alignment = Alignment.CenterStart,
            )
        }

        is ReaderViewItem.Half -> PageImage(
            page = item.page,
            url = pageUrl(item.page.number),
            imageLoader = imageLoader,
            settings = settings,
            rotationDegrees = rotationDegrees,
            contentColor = contentColor,
            modifier = modifier,
            half = if (item.secondHalf) 1 else 0,
        )
    }
}

/** A single display cell: single page / dual pair / spread half. */
@Composable
private fun ViewerPage(
    item: ReaderViewItem,
    imageLoader: ImageLoader,
    settings: DivinaSettings,
    rotationDegrees: Int,
    contentColor: Color,
    pageUrl: (Int) -> String?,
    firstHalfOnLeft: Boolean = true,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (item) {
            is ReaderViewItem.Single -> PageImage(
                page = item.page,
                url = pageUrl(item.page.number),
                imageLoader = imageLoader,
                settings = settings,
                rotationDegrees = rotationDegrees,
                contentColor = contentColor,
            )

            // Dual-page pair (mirrors the original: no spacing; the two pages align toward the gutter)
            is ReaderViewItem.Dual -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageImage(
                    page = item.first,
                    url = pageUrl(item.first.number),
                    imageLoader = imageLoader,
                    settings = settings,
                    rotationDegrees = rotationDegrees,
                    contentColor = contentColor,
                    modifier = Modifier.weight(1f),
                    alignment = Alignment.CenterEnd,
                )
                PageImage(
                    page = item.second,
                    url = pageUrl(item.second.number),
                    imageLoader = imageLoader,
                    settings = settings,
                    rotationDegrees = rotationDegrees,
                    contentColor = contentColor,
                    modifier = Modifier.weight(1f),
                    alignment = Alignment.CenterStart,
                )
            }

            is ReaderViewItem.Half -> PageImage(
                page = item.page,
                url = pageUrl(item.page.number),
                imageLoader = imageLoader,
                settings = settings,
                rotationDegrees = rotationDegrees,
                contentColor = contentColor,
                half = if (item.secondHalf) 1 else 0,
                firstHalfOnLeft = firstHalfOnLeft,
            )
        }
    }
}

/**
 * Page image: ContentScale.Fit; background/shadow/rotation/cropping come from settings.
 * Shows a page-number placeholder while loading; failures can be retried.
 */
@Composable
private fun PageImage(
    page: ReaderPage,
    url: String?,
    imageLoader: ImageLoader,
    settings: DivinaSettings,
    rotationDegrees: Int,
    contentColor: Color,
    modifier: Modifier = Modifier,
    half: Int? = null,
    /** In half mode: the first half is the left side of the spread unless the reading direction is right-to-left. */
    firstHalfOnLeft: Boolean = true,
    /** Content alignment inside the cell (toward the gutter in dual pairs; mirrors the original trailing/leading). */
    alignment: Alignment = Alignment.Center,
) {
    // Mirrors the iOS page layout, which sizes the page from the loaded image instead of the
    // server metadata (NativePageItem_iOS.swift): use the decoded size once the request has
    // finished, keep the metadata ratio while loading (a no-op when both agree).
    var imageState by remember(page.number, half) { mutableStateOf<AsyncImagePainter.State?>(null) }
    val decodedRatio = (imageState as? AsyncImagePainter.State.Success)
        ?.painter
        ?.intrinsicSize
        ?.takeIf { it.width > 0f && it.height > 0f }
        ?.let { it.width / it.height }
    val metadataRatio = if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height.toFloat() else 0f
    val fullRatio = decodedRatio?.takeIf { it.isFinite() && it > 0f }
        ?: metadataRatio.takeIf { it.isFinite() && it > 0f }
        ?: 0.7f

    val rotationModifier = Modifier.graphicsLayer {
        if (rotationDegrees != 0) {
            rotationZ = rotationDegrees.toFloat()
            val base = if (fullRatio > 1f) 1f / fullRatio else fullRatio
            val scale = when (rotationDegrees % 180) {
                0 -> 1f
                else -> base.coerceIn(0.35f, 1f)
            }
            scaleX = scale
            scaleY = scale
        }
    }
    // Mirrors the iOS page shadow (black 0.25 / radius 2pt / offset y2): a light elevation, not a deep shadow
    val shadowModifier = if (settings.showPageShadow) {
        Modifier.shadow(elevation = 2.dp, shape = RoundedCornerShape(2.dp))
    } else {
        Modifier
    }
    val context = LocalContext.current
    val crop = settings.borderCrop
    // Mirrors the original ReaderBackground.appliesImageMultiplyBlend: a sepia background multiplies
    // into the page image
    val blendFilter = if (settings.background.appliesImageMultiplyBlend) {
        ColorFilter.tint(ReaderBackground.SepiaPaper, BlendMode.Multiply)
    } else {
        null
    }
    val request = remember(context, url, crop) {
        url?.let {
            ImageRequest.Builder(context).data(it).apply {
                if (crop != BorderCropMode.Disabled) {
                    transformations(BorderCropTransformation(crop))
                }
            }.build()
        }
    }

    // Display ratio: in half mode it is half of the full-page ratio (mirrors the original half-page crop)
    val displayRatio = if (half != null) fullRatio / 2f else fullRatio
    // Which side of the spread this cell shows (mirrors isLeftSplitHalf: the first half is the left
    // half of the page unless the reading direction runs right-to-left)
    val halfAnchorLeft = when (half) {
        null -> true
        0 -> firstHalfOnLeft
        else -> !firstHalfOnLeft
    }

    // The page container stays transparent (mirrors the original backgroundColor=.clear: the reader
    // background shows through while loading); the shadow is applied only after the image loads
    // (mirrors the original showPageShadow && image != nil)
    val pageLoaded = imageState is AsyncImagePainter.State.Success

    if (url == null || request == null) {
        PagePlaceholder(
            label = stringResource(R.string.common_page_number, page.number),
            ratio = displayRatio,
            modifier = modifier.then(rotationModifier),
            half = half,
            contentColor = contentColor,
        )
        return
    }

    // Fit the ratio inside the container (mirrors iOS scaleAspectFit: hugs the width in portrait,
    // the height in landscape, showing the whole page uncropped)
    val cellModifier = Modifier
        .fillMaxSize()
        .then(if (pageLoaded) shadowModifier else Modifier)
        // In half mode the cell is a crop of the spread: the clip belongs on the cell, outside the
        // (wider than the cell) page image it cuts apart
        .then(if (half != null) Modifier.clipToBounds() else Modifier)
        // A half cell rotates as a whole, so a 180° rotation keeps showing the same side of the page
        // instead of mirroring the crop
        .then(if (half != null) rotationModifier else Modifier)

    FitRatioBox(ratio = displayRatio, modifier = modifier, contentAlignment = alignment) {
        Box(modifier = cellModifier) {
            if (half != null) {
                // One half of the spread, cropped exactly like the original cropImageForSplitMode: the
                // cell keeps half the spread's ratio, the crop scale fits the page to the cell height
                // (twice the cell width) and the alignment chooses which side stays inside the clip
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = stringResource(R.string.common_page_number, page.number),
                    imageLoader = imageLoader,
                    alignment = if (halfAnchorLeft) Alignment.CenterStart else Alignment.CenterEnd,
                    contentScale = ContentScale.Crop,
                    colorFilter = blendFilter,
                    onState = { imageState = it },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    PageImageStates(pageNumber = page.number, contentColor = contentColor)
                }
            } else {
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = stringResource(R.string.common_page_number, page.number),
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Fit,
                    colorFilter = blendFilter,
                    onState = { imageState = it },
                    modifier = Modifier.fillMaxSize().then(rotationModifier),
                ) {
                    PageImageStates(pageNumber = page.number, contentColor = contentColor)
                }
            }
        }
    }
}

/**
 * Puts the largest box that keeps [ratio] into the available space (mirrors the fitting semantics
 * of iOS scaleAspectFit): hugs the height when the container is wider, the width when it is taller;
 * with unconstrained height (webtoon list) the height is derived from the width.
 */
@Composable
private fun FitRatioBox(
    ratio: Float,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier,
        contentAlignment = contentAlignment,
    ) {
        val safe = if (ratio.isFinite() && ratio > 0f) ratio else 0.7f
        val sizeModifier = if (
            constraints.maxHeight == androidx.compose.ui.unit.Constraints.Infinity ||
            constraints.maxWidth.toFloat() / constraints.maxHeight.toFloat() < safe
        ) {
            Modifier.fillMaxWidth().aspectRatio(safe)
        } else {
            Modifier.fillMaxHeight().aspectRatio(safe)
        }
        Box(modifier = sizeModifier, content = content)
    }
}

/** Loading/error/success states of SubcomposeAsyncImage (the coil3 state is a StateFlow and must be collected). */
@Composable
private fun coil3.compose.SubcomposeAsyncImageScope.PageImageStates(pageNumber: Int, contentColor: Color) {
    val state by painter.state.collectAsState()
    when (state) {
        is AsyncImagePainter.State.Loading -> PageLoadingIndicator(pageNumber = pageNumber, contentColor = contentColor)
        is AsyncImagePainter.State.Error -> PageErrorIndicator(
            pageNumber = pageNumber,
            contentColor = contentColor,
            onRetry = { painter.restart() },
        )

        else -> SubcomposeAsyncImageContent()
    }
}

/** Mirrors the iOS NativePageItem loading indicator: in-page spinner + page-number placeholder. */
@Composable
private fun PageLoadingIndicator(pageNumber: Int, contentColor: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = contentColor.copy(alpha = 0.45f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.common_page_number, pageNumber),
                style = MaterialTheme.typography.titleSmall,
                color = contentColor.copy(alpha = 0.45f),
            )
        }
    }
}

/** Load failure placeholder with a retry action. */
@Composable
private fun PageErrorIndicator(pageNumber: Int, contentColor: Color, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.reader_page_load_failed, pageNumber),
                style = MaterialTheme.typography.titleSmall,
                color = contentColor.copy(alpha = 0.45f),
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.common_retry), color = contentColor)
            }
        }
    }
}

@Composable
private fun PagePlaceholder(
    label: String,
    ratio: Float,
    modifier: Modifier = Modifier,
    half: Int? = null,
    contentColor: Color = Color.White,
) {
    val safeRatio = if (ratio.isFinite() && ratio > 0f) ratio else 0.7f
    FitRatioBox(ratio = safeRatio, modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PageFill)
                .border(1.dp, PageLine),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = label, style = MaterialTheme.typography.titleSmall, color = contentColor.copy(alpha = 0.45f))
                if (half != null) {
                    Text(
                        text = if (half == 0) {
                            stringResource(R.string.reader_page_first_half)
                        } else {
                            stringResource(R.string.reader_page_second_half)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.35f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** Tap-zone hints: the faint divider lines and the labels of the three main zones. */
@Composable
private fun TapZoneHints(mode: TapZoneMode, isRtl: Boolean, inversion: TapZoneInversion) {
    Box(modifier = Modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.18f)
        // Horizontal thirds divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(line)
                .graphicsLayer { translationY = size.height * 0f },
        )
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.reader_tap_zone_hint_previous),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.35f),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.reader_tap_zone_hint_menu),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.35f),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.reader_tap_zone_hint_next),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.35f),
                )
            }
        }
    }
}

/**
 * Long-press image menu (aligned with the original Image Context Menu: share, plus showing a single
 * page on its own / cancelling that in dual-page display).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPageMenuSheet(
    page: ReaderPage,
    pageUrl: String?,
    soloAction: SoloMenuAction?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pageLabel = stringResource(R.string.common_page_number, page.number)
    val shareLabel = stringResource(R.string.reader_share_page)
    AppModalDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                pageLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        pageUrl?.let { url ->
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, shareLabel))
                        }
                        onDismiss()
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shareLabel, style = MaterialTheme.typography.bodyLarge)
            }
            if (soloAction != null) {
                val soloLabel = if (soloAction.isSolo) {
                    stringResource(R.string.reader_cancel_solo)
                } else {
                    stringResource(R.string.reader_show_page_solo, soloAction.displayPage)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            soloAction.onToggle()
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        soloLabel,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * Current Java heap usage as `used/total/max` MB, for the reader's diagnostic log trail: a process that
 * dies from memory pressure right after a page turn shows an almost saturated heap in its last line.
 */
private fun heapFootprint(): String {
    val runtime = Runtime.getRuntime()
    val mb = 1024L * 1024L
    val used = (runtime.totalMemory() - runtime.freeMemory()) / mb
    val total = runtime.totalMemory() / mb
    val max = runtime.maxMemory() / mb
    return "heap=${used}/${total}/${max}MB"
}
