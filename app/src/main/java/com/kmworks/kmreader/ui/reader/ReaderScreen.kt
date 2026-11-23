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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
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
import com.kmworks.kmreader.core.data.ReaderAdjacentBooks
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.core.image.BorderCropTransformation
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.ReaderPage
import com.kmworks.kmreader.data.ReaderViewItem
import com.kmworks.kmreader.data.ReaderViewItemGenerator
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.abs
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
 * - Reading direction and wide-page splitting: the value remembered in the menu wins — a per-book
 *   exception first, then the series value; otherwise direction falls back to the global default /
 *   series metadata chain (resolveReadingDirection) and splitting to the reader default. Layout /
 *   solo cover / rotation stay SESSION-scoped — changes never write back to the defaults (mirrors
 *   @State + reset semantics)
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
 * - End of book: the last cell is the end page (previous/next book hint, mirrors the original
 *   ReaderViewItem.end); the next book is opened in this same session, keeping the reader options,
 *   or handed to [onOpenBook] when it is read by another reader (EPUB/PDF)
 * - Start of book: symmetric, the first cell is the previous book's end page (the original appends
 *   `.end` after every segment, so the item before a segment's first page is the previous segment's
 *   end page); turning back past it reopens that book on its own progress
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
    /** Hands a book another reader owns (EPUB/PDF) to the route; [startAtEnd] opens it on its last page. */
    onOpenBook: (Book, Boolean) -> Unit = { _, _ -> },
    /** The read list the entry book was opened from (null from everywhere else); the session keeps its order. */
    readListStableId: Int? = null,
    /** Open the entry book on its last page (explicit end-of-book entry); the seam cards resume progress. */
    startAtEnd: Boolean = false,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val defaultDirection by settingsViewModel.defaultDirection.collectAsStateWithLifecycle()
    val wifiOnlyPreload by settingsViewModel.wifiOnlyPreload.collectAsStateWithLifecycle()
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val readerBackground = settings.background.color(darkTheme)
    val pageContentColor = settings.background.contentColor(darkTheme)

    // The book on screen: the route's book, replaced when the end page opens the next one (mirrors the
    // original currentBook, which the session keeps while the reader stays open)
    var presentedBook by remember(book.id) { mutableStateOf(book) }

    // Mirrors the original shouldShowControls (initially false): the chrome stays hidden while
    // loading and reading; only a tap reveals it
    var chromeVisible by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var pageInitialized by remember { mutableStateOf(false) }
    // Which book the current page/layout state was restored for (the end page can open the next one)
    var initializedBookId by remember { mutableIntStateOf(-1) }
    // Progress-reporting gate: do not report until the restored page has scrolled into place
    // (mirrors iOS, where the first observed page is the restored page; otherwise jumping to the
    // restored page would look like a page turn)
    var progressArmed by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Session-scoped reading preferences (mirroring the original @State initial values: seeded from
    // the defaults, never written back). The reading direction is the exception: a direction picked in
    // the menu is remembered for the series (or for one book), so reopening or continuing does not fall
    // back to the series metadata.
    var direction by remember { mutableStateOf<ReadingDirection?>(null) }
    /** The direction remembered for the current book or its series (null = the settings/metadata chain decides). */
    var directionOverride by remember { mutableStateOf<ReadingDirection?>(null) }
    /** True when [directionOverride] came from this book's own exception rather than the series value. */
    var directionIsBookOnly by remember { mutableStateOf(false) }
    /** The wide-page split remembered for the current book or its series (null = the reader defaults decide). */
    var splitWideOverride by remember { mutableStateOf<SplitWidePageMode?>(null) }
    /** True when [splitWideOverride] came from this book's own exception rather than the series value. */
    var splitWideIsBookOnly by remember { mutableStateOf(false) }
    var pageLayout by remember { mutableStateOf<PageLayout?>(null) }
    var splitWideMode by remember { mutableStateOf<SplitWidePageMode?>(null) }
    var soloCoverPage by remember { mutableStateOf<Boolean?>(null) }
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var contextMenuPage by remember { mutableStateOf<ReaderPage?>(null) }

    // A modal sheet dims the whole reader window, so the chrome must not stay painted behind it:
    // its scrim would show through the dim as a band above/below the sheet. chromeVisible itself is
    // left untouched — dismissing the sheet brings the chrome back, and the hidden-chrome page
    // number / progress indicators stay consistent (menuOpen is not a sheet: its popup is anchored
    // to the top bar and closes before any sheet opens).
    val sheetOpen = showSettings || showPageJump || showDetailSheet || contextMenuPage != null
    val chromeShown = chromeVisible && !sheetOpen

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
    LaunchedEffect(presentedBook, incognito, startAtEnd) {
        viewModel.incognito = incognito
        // The next book starts on its own page instead of inheriting the previous book's
        initializedBookId = -1
        // The pager states below are anchored on `page` whenever this is true, so a book switch would
        // otherwise seed the new list with the previous book's page number (and the settled-page watcher
        // would then keep reporting it). Clearing it makes the rebuild anchor on the new book's own
        // initialPage instead; progress reporting is re-armed once that page is actually on screen.
        pageInitialized = false
        progressArmed = false
        // The read list of the entry point only applies to the first load; continuing a list keeps the
        // context the view model already resolved (mirrors resolvedReadListContext living in the session)
        // startAtEnd only applies to the route's entry book: books opened inside this session carry their
        // own resume position (the backward seam passes startAtEnd to openBook instead)
        viewModel.load(
            presentedBook,
            readListStableId,
            startAtEnd = startAtEnd && presentedBook.id == book.id,
        )
    }
    // Flush unreported progress when leaving the reader (mirrors the original flushProgress)
    DisposableEffect(Unit) {
        onDispose { viewModel.flushProgress(page) }
    }
    // Diagnostic trail: every reader open/close goes into the app log with the heap in use, so a death
    // between two entries can be attributed to this screen and a memory blow-up is visible right before
    // the process disappears (the log file survives the crash).
    DisposableEffect(presentedBook.id) {
        AppLog.i("Reader", "open book=${presentedBook.id} incognito=$incognito ${heapFootprint()}")
        onDispose { AppLog.i("Reader", "close book=${presentedBook.id} ${heapFootprint()}") }
    }
    val readerPages = uiState.pages
    val soloPages by viewModel.soloPages.collectAsStateWithLifecycle()
    val pageCount = readerPages.size
    val ready = uiState.status == ReaderPagesStatus.Ready

    // After loading: restore the initial page and resolve session preferences. Runs once per book —
    // the reader options are already set when the end page opens the next book, so they are kept
    // (mirrors preserveReaderOptions of the original openAdjacentBook)
    LaunchedEffect(ready, presentedBook.id, uiState.bookId) {
        if (!ready || uiState.bookId != presentedBook.id) return@LaunchedEffect
        if (initializedBookId == presentedBook.id) return@LaunchedEffect
        page = uiState.initialPage ?: 1
        // A direction remembered for this book (an exception) or for its series wins over the session
        // value and the metadata; the session value only carries over when nothing is remembered, so
        // continuing into the next volume of the same series keeps the series choice and a read list
        // that crosses series still picks up each book's own memory.
        val remembered = settingsViewModel.rememberedDirection(uiState.bookDtoId, presentedBook.seriesDtoId)
        // The picker shows what this book actually remembers; a session value carried over from another
        // book is not a memory, so both are refreshed on every book change
        directionOverride = remembered?.value
        directionIsBookOnly = remembered?.perBook ?: false
        if (remembered != null) {
            direction = remembered.value
        } else if (direction == null) {
            direction = resolveReadingDirection(
                seriesDirection = readingDirectionFromSeriesToken(uiState.seriesDirection),
                forceDefault = settings.forceDefaultDirection,
                defaultDirection = defaultDirection,
            )
        }
        if (pageLayout == null) pageLayout = settings.defaultPageLayout
        // Wide-page split is remembered like the direction (series-wide, with per-book exceptions)
        val rememberedSplit = settingsViewModel.rememberedSplitWideMode(uiState.bookDtoId, presentedBook.seriesDtoId)
        splitWideOverride = rememberedSplit?.value
        splitWideIsBookOnly = rememberedSplit?.perBook ?: false
        if (rememberedSplit != null) {
            splitWideMode = rememberedSplit.value
        } else if (splitWideMode == null) {
            splitWideMode = settings.defaultSplitWideMode
        }
        if (soloCoverPage == null) soloCoverPage = settings.defaultSoloCoverPage
        initializedBookId = presentedBook.id
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

    val pageItems = remember(
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
    // The previous book, once the sibling lookup has resolved: its end page becomes the seam in front of
    // the first page, the mirror image of the end page appended after the last one (the original appends
    // `.end` after every segment, so the item in front of a segment's first page is the previous segment's
    // end page)
    val previousBook = uiState.adjacent.let { if (it.loaded) it.previous else null }
    val items = remember(pageItems, previousBook?.id) {
        if (previousBook != null) {
            listOf(ReaderViewItem.PreviousEnd) + pageItems + ReaderViewItem.End
        } else {
            pageItems + ReaderViewItem.End
        }
    }
    // The resume page has to be the pager's initial page, not a scroll applied after the first frame:
    // the pager state is created while the list is still loading, so opening a book mid-way would
    // paint cell 0 for a frame and only then jump to the restored cell (the first-page flash). Both
    // scroll states are therefore rebuilt when the list becomes ready, so their first layout is
    // already the cell that holds the resume page.
    //
    // They are also rebuilt when the previous book resolves: its end page is prepended, which shifts every
    // index by one, so a kept index would silently show the cell before the current one. Rebuilding around
    // the page on screen (the restored page before the first seek has run) keeps the reader in place.
    val pagerState = key(ready, presentedBook.id, previousBook?.id) {
        val anchor = if (pageInitialized) page else (uiState.initialPage ?: 1)
        val start = if (ready) {
            items.indexOfFirst { it.pages.contains(anchor) }.coerceAtLeast(0)
        } else {
            0
        }
        rememberPagerState(initialPage = start) { items.size.coerceAtLeast(1) }
    }
    val webtoonState = key(ready, presentedBook.id, previousBook?.id) {
        val anchor = if (pageInitialized) page else (uiState.initialPage ?: 1)
        val start = if (ready) {
            items.indexOfFirst { it.pages.contains(anchor) }.coerceAtLeast(0)
        } else {
            0
        }
        rememberLazyListState(initialFirstVisibleItemIndex = start)
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
                        viewModel.pageImageUrl(number)?.let { url ->
                            imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                        }
                    }
                }
            }
    }
    // Warm the first page of the neighbours: the end page and the continuation gesture open one of them
    // straight away, so the image cache must not be cold at that point (the original preloads the next
    // segment for the same reason)
    LaunchedEffect(uiState.adjacent, imageLoader, wifiOnlyPreload) {
        if (wifiOnlyPreload && !isWifiConnected(context)) return@LaunchedEffect
        listOfNotNull(uiState.adjacent.next, uiState.adjacent.previous).forEach { book ->
            viewModel.firstPageUrl(book.id)?.let { url ->
                imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
            }
        }
    }
    // Progress reporting (paged/vertical)
    LaunchedEffect(pagerState, items, uiState.bookDtoId, progressArmed) {
        if (!progressArmed) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { index ->
            if (uiState.bookDtoId == null) return@collect
            // The end-of-book cell holds no pages (it must not be reported as a page)
            items.getOrNull(index)?.pages?.maxOrNull()?.let { viewModel.reportProgress(it) }
        }
    }
    // Webtoon: preloading + progress reporting
    LaunchedEffect(webtoonState, uiState.bookDtoId, isWebtoon, items, imageLoader, settings.preloadProfile, wifiOnlyPreload, progressArmed) {
        if (!isWebtoon || !progressArmed) return@LaunchedEffect
        snapshotFlow { webtoonState.firstVisibleItemIndex }
            .debounce(300)
            .collect { index ->
                if (uiState.bookDtoId == null) return@collect
                items.getOrNull(index)?.pages?.maxOrNull()?.let { viewModel.reportProgress(it) }
                if (wifiOnlyPreload && !isWifiConnected(context)) return@collect
                val profile = settings.preloadProfile
                for (i in (index - profile.before)..(index + profile.after)) {
                    items.getOrNull(i)?.pages?.forEach { n ->
                        viewModel.pageImageUrl(n)?.let { url ->
                            imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                        }
                    }
                }
            }
    }

    // Opens the next book from the end page. Progress is flushed first and the current reader options
    // are kept (mirrors the original openAdjacentBook); a book another reader handles (EPUB/PDF) is
    // handed back to the route so that reader takes over.
    fun openNext(next: Book) {
        viewModel.flushProgress(page)
        if (next.readerFormat != "CBZ") {
            onOpenBook(next, false)
            return
        }
        viewModel.openBook(next)
        presentedBook = next
    }
    // Opens the previous book from its end page (the mirror image of [openNext]). It resumes on that
    // book's own progress rather than its last page: landing on the last page and then leaving would
    // record the book as finished, and the seam card is a way back, not a way to finish it twice.
    fun openPrevious(previous: Book) {
        viewModel.flushProgress(page)
        if (previous.readerFormat != "CBZ") {
            // Another reader takes over and resumes its own progress (startAtEnd stays false)
            onOpenBook(previous, false)
            return
        }
        viewModel.openBook(previous)
        presentedBook = previous
    }
    // First page of the book on screen: the cell after the previous book's end page
    fun goToFirstPage() {
        val target = items.indexOfFirst { it.pages.isNotEmpty() }.coerceAtLeast(0)
        scope.launch {
            if (isWebtoon) webtoonState.animateScrollToItem(target)
            else pagerState.animateScrollToPage(target.coerceIn(0, max(0, items.lastIndex)))
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
    // True while the end-of-book cell owns the screen (the last cell of either pager)
    fun atEndPage(): Boolean {
        val index = if (isWebtoon) webtoonState.firstVisibleItemIndex else pagerState.currentPage
        return items.getOrNull(index.coerceIn(0, max(0, items.lastIndex))) is ReaderViewItem.End
    }
    // The end page has no page after it: turning past it opens the next book (the original pages
    // straight into the next segment, which a single-book pager cannot do)
    fun openNextIfAtEndPage(): Boolean {
        val next = uiState.adjacent.next
        if (!uiState.adjacent.loaded || next == null || !atEndPage()) return false
        openNext(next)
        return true
    }
    // Tap-zone navigation: one page per tap
    fun tapNext() {
        if (openNextIfAtEndPage()) return
        stepPage(pagerState.currentPage + 1)
    }
    // True while the previous book's end page owns the screen (the first cell of either pager)
    fun atPreviousEndPage(): Boolean {
        val index = if (isWebtoon) webtoonState.firstVisibleItemIndex else pagerState.currentPage
        return items.getOrNull(index.coerceIn(0, max(0, items.lastIndex))) is ReaderViewItem.PreviousEnd
    }
    // Nothing precedes the previous book's end page: turning back opens that book (the original pages
    // straight out of the prepended segment, which a single-book pager cannot do)
    fun openPreviousIfAtPreviousEndPage(): Boolean {
        val previous = previousBook ?: return false
        if (!atPreviousEndPage()) return false
        openPrevious(previous)
        return true
    }
    fun tapPrevious() {
        if (openPreviousIfAtPreviousEndPage()) return
        stepPage(pagerState.currentPage - 1)
    }
    // Continuation gesture: the pager only holds this book's cells, so at the seam a drag has nowhere to
    // go — it turns into overscroll, and the part the pager could not consume reaches this connection as
    // nested-scroll leftover. Once that leftover passes a short threshold the adjacent book opens, which
    // is the extra page turn the original gets for free: its pager holds every segment's cells in one flat
    // list, so its last page is never a seam. Backwards the same gesture reopens the previous book on its
    // own progress.
    val continueThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    // One pull per book: the book the gesture continues into gets its own allowance
    val continuePull = remember(presentedBook.id, continueThreshold) {
        ReaderContinuationPull(continueThreshold)
    }
    val continueConnection = remember(
        presentedBook.id,
        isPaged,
        directionNow,
        uiState.adjacent,
        progressArmed,
    ) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // A scroll the reader could take means it is not pressed against a seam after all
                if (available == Offset.Zero) {
                    if (consumed != Offset.Zero) continuePull.reset()
                    return Offset.Zero
                }
                if (!progressArmed) return Offset.Zero
                val axial = if (isPaged) available.x else available.y
                if (axial == 0f) return Offset.Zero
                // Forward means the drag asks for the next page in this reading direction
                val forward = if (isPaged && directionNow == ReadingDirection.Rtl) axial > 0f else axial < 0f
                val next = uiState.adjacent.next
                val previous = uiState.adjacent.previous
                if (forward) {
                    if (next == null) return Offset.Zero
                    if (continuePull.add(abs(axial))) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openNext(next)
                    }
                } else {
                    if (previous == null) return Offset.Zero
                    if (continuePull.add(abs(axial))) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openPrevious(previous)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // The gesture ended without crossing the seam
                continuePull.reset()
                return Velocity.Zero
            }
        }
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
        // The end page opens the next book, so the handler must see the resolved neighbour
        uiState.adjacent,
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
                                // At the top of the scroll the previous book's end page opens that book
                                if (!openPreviousIfAtPreviousEndPage()) {
                                    webtoonState.animateScrollBy(-viewSize.height * settings.webtoonTapScrollPercentage / 100f)
                                }
                            } else {
                                tapPrevious()
                            }
                        }
                        TapZoneAction.Next -> scope.launch {
                            if (isWebtoon) {
                                if (!openNextIfAtEndPage()) {
                                    webtoonState.animateScrollBy(viewSize.height * settings.webtoonTapScrollPercentage / 100f)
                                }
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

    // End-of-book cell: the finished book as "Previous", then the next book or the caught-up state
    // (mirrors the original end page inserted after the last page of a segment)
    val endPage: @Composable (Modifier) -> Unit = { cellModifier ->
        ReaderEndPage(
            book = presentedBook,
            adjacent = uiState.adjacent,
            // A read list page reports list order, not series membership, so the series unread count
            // would read as a list statistic (mirrors the original unreadText suppression)
            unreadCount = if (uiState.readListId == null) uiState.unreadCount else null,
            relationTitle = uiState.readListName ?: presentedBook.seriesTitle,
            isRtl = directionNow == ReadingDirection.Rtl,
            contentColor = pageContentColor,
            onOpenNext = { next -> openNext(next) },
            onRetry = viewModel::retryAdjacent,
            onClose = onBack,
            modifier = cellModifier,
        )
    }
    // End page of the previous book: the same seam seen from the other side, with the previous book as
    // "Previous" and the book on screen as "Next" (mirrors the original, where the end page of a segment
    // is the item right before the next segment's first page)
    val previousEndPage: @Composable (Modifier) -> Unit = { cellModifier ->
        val previous = previousBook
        if (previous != null) {
            ReaderEndPage(
                book = previous,
                adjacent = ReaderAdjacentBooks(next = presentedBook, loaded = true),
                unreadCount = if (uiState.readListId == null) uiState.unreadCount else null,
                relationTitle = uiState.readListName ?: previous.seriesTitle,
                isRtl = directionNow == ReadingDirection.Rtl,
                contentColor = pageContentColor,
                // "Next" is the book already open: the tap goes back to its first page
                onOpenNext = { goToFirstPage() },
                onRetry = viewModel::retryAdjacent,
                onClose = onBack,
                // The card of the finished book reopens it: it resumes on its own progress instead of
                // its last page, so walking back does not record the book as finished
                onOpenPrevious = { openPrevious(previous) },
                modifier = cellModifier,
            )
        }
    }
    // The end page is the last cell: refresh the unread count once it is in view (a completing progress
    // write only moves the series count after the server has stored it). The settled cell is observed
    // rather than read in composition, which would recompose the screen while scrolling. A settled cell
    // also ends a continuation drag, so its accumulated pull starts over.
    LaunchedEffect(pagerState, webtoonState, items, isWebtoon, uiState.bookDtoId) {
        val settled = if (isWebtoon) {
            snapshotFlow { webtoonState.firstVisibleItemIndex }
        } else {
            snapshotFlow { pagerState.settledPage }
        }
        settled.collect { index ->
            continuePull.reset()
            if (items.getOrNull(index) is ReaderViewItem.End) viewModel.refreshUnreadCount()
        }
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
                TextButton(onClick = { viewModel.retry(presentedBook) }) {
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
                        modifier = Modifier.fillMaxSize().then(gestureModifier).nestedScroll(continueConnection),
                        pageUrl = viewModel::pageImageUrl,
                        endPage = endPage,
                        previousEndPage = previousEndPage,
                    )

                    isVertical -> VerticalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(zoomModifier)
                            .then(gestureModifier)
                            .nestedScroll(continueConnection),
                        userScrollEnabled = !zoomed,
                        contentPadding = PaddingValues(0.dp),
                        // Pre-compose adjacent pages (mirroring the offscreen preload of the iOS
                        // native pager): no loading placeholder flash while turning pages
                        beyondViewportPageCount = 1,
                    ) { index ->
                        val item = items[index]
                        when {
                            item is ReaderViewItem.End -> endPage(Modifier.fillMaxSize())
                            item is ReaderViewItem.PreviousEnd -> previousEndPage(Modifier.fillMaxSize())
                            else -> ViewerPage(
                                item = item,
                                imageLoader = imageLoader,
                                settings = settings,
                                rotationDegrees = rotationDegrees,
                                contentColor = pageContentColor,
                                pageUrl = viewModel::pageImageUrl,
                                firstHalfOnLeft = firstHalfOnLeft,
                            )
                        }
                    }

                    else -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(zoomModifier)
                            .then(gestureModifier)
                            .nestedScroll(continueConnection),
                        userScrollEnabled = !zoomed,
                        reverseLayout = directionNow == ReadingDirection.Rtl,
                        contentPadding = PaddingValues(0.dp),
                        // The iOS pager keeps minimumLineSpacing 0 in both transitions, so the two
                        // pages of a swipe touch instead of showing a gap
                        pageSpacing = 0.dp,
                        // Pre-compose adjacent pages (mirroring the offscreen preload of the iOS
                        // native pager): no loading placeholder flash while turning pages
                        beyondViewportPageCount = 1,
                    ) { index ->
                        // A pager can only slide its pages. The Cover transition used to cancel
                        // each page's own layout offset here
                        // (`translationX = -relative * size.width` with
                        // `relative = index - currentPage - currentPageOffsetFraction`), which put
                        // every composed page at the same spot: dragging moved nothing, and the
                        // loading placeholders of the pre-composed neighbours were painted on top
                        // of each other. Keep the stack order (the current page above its
                        // neighbours) and let the pager (and the reading direction) drive the
                        // motion instead.
                        val item = items[index]
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (index == pagerState.currentPage) 1f else 0f),
                        ) {
                            when {
                                item is ReaderViewItem.End -> endPage(Modifier.fillMaxSize())
                                item is ReaderViewItem.PreviousEnd -> previousEndPage(Modifier.fillMaxSize())
                                else -> ViewerPage(
                                    item = item,
                                    imageLoader = imageLoader,
                                    settings = settings,
                                    rotationDegrees = rotationDegrees,
                                    contentColor = pageContentColor,
                                    pageUrl = viewModel::pageImageUrl,
                                    firstHalfOnLeft = firstHalfOnLeft,
                                )
                            }
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
            visible = chromeShown,
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
                        if (presentedBook.oneshot) presentedBook.title else presentedBook.seriesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = ChromeText,
                    )
                    if (!presentedBook.oneshot) {
                        Text(
                            presentedBook.number,
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
                        directionOverride = directionOverride,
                        directionIsBookOnly = directionIsBookOnly,
                        onDirectionBookOnlyChange = { directionIsBookOnly = it },
                        onDirectionChange = { picked ->
                            // Applied for this session and remembered (null = "Follow default": forget the
                            // remembered choice and fall back to the reader defaults / series metadata chain).
                            // A pick writes the whole series unless "only for this book" is on; a series pick
                            // also drops this book's older exception, otherwise it would keep winning.
                            directionOverride = picked
                            direction = picked ?: resolveReadingDirection(
                                seriesDirection = readingDirectionFromSeriesToken(uiState.seriesDirection),
                                forceDefault = settings.forceDefaultDirection,
                                defaultDirection = defaultDirection,
                            )
                            val bookDtoId = uiState.bookDtoId
                            val seriesDtoId = presentedBook.seriesDtoId
                            when {
                                picked == null -> {
                                    // Following the defaults clears both levels for this book's series
                                    bookDtoId?.let { settingsViewModel.setBookDirection(it, null) }
                                    seriesDtoId?.let { settingsViewModel.setSeriesDirection(it, null) }
                                }
                                directionIsBookOnly || seriesDtoId == null -> {
                                    // No series identity to key by, so the book-level memory is all we have
                                    bookDtoId?.let { settingsViewModel.setBookDirection(it, picked) }
                                }
                                else -> {
                                    settingsViewModel.setSeriesDirection(seriesDtoId, picked)
                                    bookDtoId?.let { settingsViewModel.setBookDirection(it, null) }
                                }
                            }
                        },
                        onPageLayoutChange = { pageLayout = it },
                        onToggleSoloCover = { soloCoverPage = !soloCoverNow },
                        splitWideOverride = splitWideOverride,
                        splitWideIsBookOnly = splitWideIsBookOnly,
                        onSplitWideBookOnlyChange = { splitWideIsBookOnly = it },
                        onSplitWideModeChange = { picked ->
                            // Same memory rules as the direction (null = "Follow default": forget both levels)
                            splitWideOverride = picked
                            splitWideMode = picked ?: settings.defaultSplitWideMode
                            val bookDtoId = uiState.bookDtoId
                            val seriesDtoId = presentedBook.seriesDtoId
                            when {
                                picked == null -> {
                                    bookDtoId?.let { settingsViewModel.setBookSplitWideMode(it, null) }
                                    seriesDtoId?.let { settingsViewModel.setSeriesSplitWideMode(it, null) }
                                }
                                splitWideIsBookOnly || seriesDtoId == null -> {
                                    bookDtoId?.let { settingsViewModel.setBookSplitWideMode(it, picked) }
                                }
                                else -> {
                                    settingsViewModel.setSeriesSplitWideMode(seriesDtoId, picked)
                                    bookDtoId?.let { settingsViewModel.setBookSplitWideMode(it, null) }
                                }
                            }
                        },
                        onRotationChange = { rotationDegrees = it },
                        onOpenSettings = { showSettings = true },
                        onSharePage = {
                            viewModel.pageShareUrl(page)?.let { url -> sharePageUrl(context, url) }
                        },
                        onOpenPageJump = { showPageJump = true },
                        onOpenPreviousBook = previousBook?.let { prev -> { openPrevious(prev) } },
                        onOpenNextBook = uiState.adjacent.next?.let { next -> { openNext(next) } },
                        onDismiss = { menuOpen = false },
                    )
                }
            }
        }

        // Bottom bar: page capsule + capsule progress bar (mirrors iOS DivinaControlsOverlayView bottomOverlayContent)
        AnimatedVisibility(
            visible = chromeShown && uiState.status == ReaderPagesStatus.Ready,
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
                viewModel.pageShareUrl(p)?.let { url -> sharePageUrl(context, url) }
            },
            onDismiss = { showPageJump = false },
        )
    }
    if (showDetailSheet) {
        ReaderDetailSheet(
            book = presentedBook,
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
            pageUrl = viewModel.pageShareUrl(current.number),
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
    endPage: @Composable (Modifier) -> Unit,
    previousEndPage: @Composable (Modifier) -> Unit,
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
                when {
                    // The end pages scroll like a page, but size to their content instead of the page width
                    item is ReaderViewItem.End -> Box(modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp)) {
                        endPage(Modifier.fillMaxSize())
                    }

                    item is ReaderViewItem.PreviousEnd -> Box(modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp)) {
                        previousEndPage(Modifier.fillMaxSize())
                    }

                    else -> WebtoonItem(
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

        // The end pages own no pages and are rendered by the caller
        is ReaderViewItem.End -> Unit
        is ReaderViewItem.PreviousEnd -> Unit
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

            // The end pages own no pages and are rendered by the caller
            is ReaderViewItem.End -> Unit
            is ReaderViewItem.PreviousEnd -> Unit
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
    // The box is sized from the decoded page once it is available, like iOS (which fits by the loaded
    // UIImage size): the server page.width/height is only the pre-load guess, so missing or stale
    // metadata cannot leave a band inside an otherwise width-hugging box.
    var imageState by remember(page.number, half) { mutableStateOf<AsyncImagePainter.State?>(null) }
    val decodedRatio = (imageState as? AsyncImagePainter.State.Success)
        ?.painter?.intrinsicSize
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
