package com.kmworks.kmreader.ui.reader

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import coil3.ImageLoader
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.pdf.PdfPageOrder
import com.kmworks.kmreader.core.pdf.PdfPageOrderDocument
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.ui.components.KmDropdownMenu
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PDF reader (mirrors PdfReaderView / PdfControlsOverlayView) backed by androidx.pdf PdfView:
 * native rendering, text selection / copy, zoom and search.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfReaderScreen(
    book: Book,
    onBack: () -> Unit,
    incognito: Boolean = false,
    viewModel: PdfReaderViewModel = hiltViewModel(),
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
    // PDF preferences are persisted globally (mirroring the original pdf* @AppStorage);
    // the reader and the defaults page share the same values
    val pdfSettings by settingsViewModel.pdfSettings.collectAsStateWithLifecycle()
    // Reading direction: series metadata, overridable by the forced default (same precedence as the
    // DIVINA and EPUB readers). It drives the page-order decoration below (spread mirroring), the
    // discrete-turn gestures, the progress bar and the page-jump strip.
    val readerSettings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val defaultDirection by settingsViewModel.defaultDirection.collectAsStateWithLifecycle()
    val resolvedDirection = resolveReadingDirection(
        seriesDirection = readingDirectionFromSeriesToken(state.seriesDirection),
        forceDefault = readerSettings.forceDefaultDirection,
        defaultDirection = defaultDirection,
    ).forPdfReader()
    // Menu quick pickers (mirror iOS PdfControlsOverlayView's picker rows): session-only
    // overrides on top of the resolved chain / persisted preferences — never written back,
    // so reopening the book re-resolves from settings and series metadata.
    var directionOverride by remember { mutableStateOf<ReadingDirection?>(null) }
    var presentationOverride by remember { mutableStateOf<String?>(null) }
    val readingDirection = directionOverride ?: resolvedDirection
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showDirectionPicker by remember { mutableStateOf(false) }
    var showPresentationPicker by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }
    var showSearchSheet by remember { mutableStateOf(false) }
    // A modal sheet dims the reader window, so the chrome scrim must not stay painted behind it
    // (it would show through the dim as a band above/below the sheet). chromeVisible itself is
    // left untouched: dismissing the sheet brings the chrome back, and the hidden-chrome progress
    // bar keeps its "chrome is up" reading. The overflow menu is not a sheet — its items close it
    // before opening one.
    val sheetOpen = showSettings || showPageJump || showToc || showDetailSheet ||
        showDirectionPicker || showPresentationPicker || showSearchSheet
    val chromeShown = chromeVisible && !sheetOpen
    var pdfViewRef by remember { mutableStateOf<PdfView?>(null) }
    // Initial-page application marker, keyed to the loaded document
    val initialApply = remember { InitialApply() }
    // Do not report progress before the restored page is applied (otherwise the 2s
    // throttle / exit flush would report page 1 first and cross the gating threshold)
    var initialPageApplied by remember { mutableStateOf(false) }

    LaunchedEffect(book.id, incognito, startAtEnd) {
        viewModel.incognito = incognito
        viewModel.load(book, readListStableId, startAtEnd)
    }

    val pageCount =
        when {
            state.ready && state.pageCount > 0 -> state.pageCount
            else -> book.pageCount.coerceAtLeast(1)
        }

    // Page presentation resolved against the live orientation (the activity keeps
    // configChanges, so rotation recomposes instead of recreating). auto = dualContinuous
    // landscape / singlePaged portrait, mirroring iOS PdfPagePresentation.resolved(for:).
    val configuration = LocalConfiguration.current
    val resolvedPresentation = resolvePdfPresentation(
        presentationOverride ?: pdfSettings.pagePresentation,
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
    val pagesPerRow = if (resolvedPresentation == PDF_PRESENTATION_DUAL_CONTINUOUS) 2 else 1
    // Discrete page-turn matrix (P2): the scroll layout decides between paged swipes and a
    // continuous scroll. continuousScroll is the single source of truth — the presentation
    // chips write it alongside pagePresentation (PdfPreferencesSheet), so a chip and the
    // switch can no longer disagree about the same layout.
    val discretePages = !pdfSettings.continuousScroll

    val backgroundColor =
        when (normalizePdfBackground(pdfSettings.background)) {
            PDF_BACKGROUND_WHITE -> Color.White
            PDF_BACKGROUND_GRAY -> Color(0xFF3A3A3C)
            PDF_BACKGROUND_BLACK -> Color.Black
            else -> if (isSystemInDarkTheme()) Color.Black else Color.White
        }
    // backgroundColor is one of White / 0xFF3A3A3C / Black (system maps to Black or White),
    // so a white check is enough to know the page is light
    val onBackgroundColor = if (backgroundColor == Color.White) Color.Black else Color.White

    // Page-order decoration (P1b/P3): RTL mirrors each spread row (iOS displaysRTL) and the
    // solo cover inserts a virtual blank position in two-page layouts (iOS displaysAsBook).
    // Everything user-facing keeps physical page numbers — the capsule, TOC, search, jump
    // sheet and progress — only the positions handed to PdfView are remapped. The virtual
    // blank is filled with the reader background so it blends in instead of showing white.
    val rtl = readingDirection == ReadingDirection.Rtl
    val pageOrder =
        remember(state.pageCount, pagesPerRow, rtl, pdfSettings.soloCoverPage, backgroundColor) {
            PdfPageOrder(state.pageCount, pagesPerRow, rtl, pdfSettings.soloCoverPage, backgroundColor.toArgb())
        }
    val displayDocument =
        remember(state.document, pageOrder) {
            state.document?.let { doc ->
                if (pageOrder.isIdentity) doc else PdfPageOrderDocument(doc, pageOrder)
            }
        }

    fun jumpTo(page: Int) {
        val target = page.coerceIn(1, pageCount)
        currentPage = target
        pdfViewRef?.scrollToPage(pageOrder.physicalToPosition(target - 1))
    }

    // Volume keys turn pages instead of changing the volume. The PdfView takes the window focus on
    // touch, so the press is offered at the Activity level (MainActivity.dispatchKeyEvent) and
    // consumed there; continuous scrolling has no discrete page to turn, so paging stays off.
    VolumeKeyPagingEffect(
        enabled = readerSettings.volumeButtonPageTurnEnabled && !sheetOpen && discretePages,
        rtl = rtl,
    ) { page ->
        val step = if (page == VolumeKeyPage.Next) pagesPerRow else -pagesPerRow
        jumpTo(currentPage + step)
    }

    // Coil loader that renders jump-sheet thumbnails straight from the physical document (the
    // decorated wrapper has virtual slots that are not real pages); rebuilt when the book changes.
    val context = LocalContext.current
    val thumbImageLoader =
        remember(state.document, context) {
            state.document?.let { doc ->
                ImageLoader.Builder(context)
                    .components { add(PdfPageThumbFetcher.Factory(doc)) }
                    .build()
            }
        }

    // Chrome styling mirrors the comic reader (ReaderScreen): a gradient scrim when the
    // toggle is on and a solid scrim otherwise — never fully transparent.
    // Gradient mode keeps the original always-black scrim with white content (guaranteed
    // contrast over any page); solid mode uses the adaptive scrim tinted with the page
    // background, with the contrasting foreground color for icons and text.
    val chromeScrim = backgroundColor.copy(alpha = 0.9f)
    // Ease-out multi-stop fades: a raw two-stop linear ramp fades too steeply inside the
    // short band and shows a visible edge; the bars also get extra transparent padding
    // (bottom on the top bar, top on the bottom bar) to lengthen the fade area
    val topChromeBrush = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.85f),
        0.45f to Color.Black.copy(alpha = 0.5f),
        0.75f to Color.Black.copy(alpha = 0.18f),
        1f to Color.Transparent,
    )
    val bottomChromeBrush = Brush.verticalGradient(
        0f to Color.Transparent,
        0.25f to Color.Black.copy(alpha = 0.18f),
        0.55f to Color.Black.copy(alpha = 0.5f),
        1f to Color.Black.copy(alpha = 0.9f),
    )
    // Solid-mode chrome text follows the background; gradient mode stays white (see above)
    val chromeText = if (pdfSettings.controlsScrim) Color.White else onBackgroundColor
    // Mid-tone between background and content: a free-floating track in the scrim colour
    // would vanish on pages matching the reader background (mirrors ReaderScreen)
    val chromeTrack = lerp(backgroundColor, onBackgroundColor, 0.35f)

    // Progress reporting (real session only; incognito = Peek, no reporting)
    // Mirrors the original: throttle reports by 2s and flush the last one on exit
    LaunchedEffect(currentPage, state.ready, incognito, initialPageApplied) {
        if (state.ready && !incognito && initialPageApplied) {
            delay(2_000)
            viewModel.reportProgress(currentPage)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (initialPageApplied) viewModel.flushProgress(currentPage) }
    }
    // Flush when the app goes to the background (iOS PdfReaderView flushes on scenePhase
    // → background; ON_STOP is the closest Android equivalent)
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPage by rememberUpdatedState(currentPage)
    val latestPageApplied by rememberUpdatedState(initialPageApplied)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && latestPageApplied) {
                viewModel.flushProgress(latestPage)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Immersive mode: hide system bars while the chrome is hidden, restore them when the
    // chrome appears (mirrors iOS PdfReaderView .statusBarHidden)
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

    // Shared by all three readers (one switch under Reader Defaults, mirrored in the panels).
    // With "draw under cutout" off the document is letterboxed out of the notch / punch-hole band;
    // on it fills the screen like the DIVINA pages do.
    val contentInset = readerCutoutPadding(readerSettings.drawUnderCutout)

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        when {
            state.loading -> {
                // No PdfView exists yet to receive taps; toggle the chrome from here
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { chromeVisible = !chromeVisible } },
                )
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = onBackgroundColor)
                    val progress = state.downloadProgress
                    Text(
                        when {
                            // The file is local; opening/parsing the document (iOS "Preparing PDF…")
                            state.preparing -> stringResource(R.string.pdf_preparing)
                            progress != null ->
                                stringResource(R.string.pdf_downloading_progress, (progress * 100).toInt())
                            else -> stringResource(R.string.pdf_downloading)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = onBackgroundColor.copy(alpha = 0.65f),
                    )
                }
            }

            state.error -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.pdf_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBackgroundColor,
                    )
                    Button(onClick = { viewModel.retry() }) { Text(stringResource(R.string.common_retry)) }
                }
            }

            state.ready && state.document != null -> {
                AndroidView(
                    factory = { ctx ->
                        DiscretePdfContainer(ctx).also { container ->
                            pdfViewRef = container.pdfView
                        }
                    },
                    update = { container ->
                        val view = container.pdfView
                        // Swapping the decorated document resets the view (verified in the
                        // pdf-viewer sources: the setter never closes the old document), so
                        // the scroll position is re-applied below on every re-wrap.
                        val documentChanged = view.pdfDocument !== displayDocument
                        if (documentChanged) {
                            view.pdfDocument = displayDocument
                        }
                        if (view.pagesPerRow != pagesPerRow) view.pagesPerRow = pagesPerRow
                        container.discreteMode = discretePages
                        // iOS PDFView displayDirection: LTR/RTL turn pages horizontally
                        // (RTL flips the swipe semantics), Vertical turns vertically.
                        container.axis =
                            if (readingDirection == ReadingDirection.Vertical) {
                                DiscretePdfContainer.Axis.Vertical
                            } else {
                                DiscretePdfContainer.Axis.Horizontal
                            }
                        container.rtl = rtl
                        // latestPage, not currentPage: the callback runs outside composition,
                        // so a plain `currentPage` read here would freeze the value captured
                        // by the last update pass (always page 1) and every swipe would turn
                        // to the same page again.
                        container.onNextPage = { jumpTo(latestPage + pagesPerRow) }
                        container.onPrevPage = { jumpTo(latestPage - pagesPerRow) }
                        view.setBackgroundColor(backgroundColor.toArgb())
                        // androidx.pdf never reports its position the way a scrolled view does:
                        // scrollX/scrollY stay put, and firstVisiblePage (plus the viewport
                        // callback's own argument) reads 0 for every viewport laid out here.
                        // The page indicator the view paints is accurate, so continuous mode
                        // reads progress back from it. Paged mode does not read anything back:
                        // there a turn is already authoritative, and a partially visible row
                        // would otherwise resolve back to the row the turn just left.
                        container.onViewportPageChanged = {
                            if (!discretePages && state.document != null) {
                                view.firstVisibleDisplayPage()?.let { display ->
                                    val physical =
                                        pageOrder.positionToPhysical(display - 1).coerceAtLeast(0)
                                    currentPage = (physical + 1).coerceIn(1, pageCount)
                                }
                            }
                        }
                        if (documentChanged && displayDocument != null) {
                            // First open lands on the restored progress page; later re-wraps
                            // (rotation, direction or presentation changes) keep the current
                            // physical page. Applied last so the pagesPerRow setter's own
                            // scroll restore cannot override it.
                            val target =
                                if (initialApply.appliedFor !== state.document) {
                                    initialApply.appliedFor = state.document
                                    currentPage = state.initialPage
                                    initialPageApplied = true
                                    state.initialPage
                                } else {
                                    currentPage
                                }
                            view.scrollToPage(pageOrder.physicalToPosition(target - 1))
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(contentInset)
                            .unconsumedTap { chromeVisible = !chromeVisible },
                )
            }

            else -> {
                // No session / unmapped book: error state, no demo placeholder
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.reader_connection_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBackgroundColor,
                    )
                    TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = onBackgroundColor) }
                }
            }
        }

        // Mini progress bar while chrome is hidden (mirrors showProgressBarWhileReading):
        // the comic reader's 2dp bar, on a mid-tone track so it stays visible on pages of
        // either colour
        if (!chromeVisible && pdfSettings.showProgressWhileReading) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(chromeTrack),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((currentPage.toFloat() / pageCount).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(onBackgroundColor.copy(alpha = 0.85f)),
                )
            }
        }

        // Top bar (mirrors PdfControlsOverlayView top bar)
        AnimatedVisibility(
            visible = chromeShown,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
                // Shared top-bar content pieces: gradient mode lays them out as one full-width
                // band; solid mode splits them into a left adaptive-length capsule (back +
                // title) and a right capsule (menu)
                val topBarBack: @Composable () -> Unit = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = chromeText,
                        )
                    }
                    // Private-reading indicator (mirrors the original top-bar eye.slash before the title)
                    if (incognito) {
                        Icon(
                            Icons.Filled.VisibilityOff,
                            contentDescription = stringResource(R.string.reader_incognito),
                            tint = chromeText.copy(alpha = 0.85f),
                            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                        )
                    }
                }
                val topBarTitle: @Composable (Modifier) -> Unit = { titleModifier ->
                    Column(
                        modifier = titleModifier
                            // Mirrors the iOS top-bar title button: tapping the title opens the book detail sheet
                            .clickable { showDetailSheet = true },
                    ) {
                        // Mirrors iOS: oneshots show the book title and hide the volume label
                        Text(
                            if (book.oneshot) book.title else book.seriesTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = chromeText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (book.oneshot) {
                                "PDF"
                            } else {
                                stringResource(R.string.reader_volume_pdf, book.number)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = chromeText.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val topBarMenu: @Composable () -> Unit = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.common_more),
                                tint = chromeText,
                            )
                        }
                        KmDropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            // The book-detail entry lives in the top-bar title (mirrors iOS);
                            // it is not repeated in this menu
                            // Quick pickers (mirror the iOS "Current Reading Options" section):
                            // session-only, never written back to preferences.
                            MenuValueItem(
                                label = stringResource(R.string.reader_menu_direction),
                                value = stringResource(readingDirection.labelRes),
                                onClick = {
                                    menuExpanded = false
                                    showDirectionPicker = true
                                },
                            )
                            MenuValueItem(
                                label = stringResource(R.string.pdf_page_presentation),
                                value = stringResource(
                                    pdfPagePresentationLabelRes(
                                        presentationOverride ?: pdfSettings.pagePresentation,
                                    ),
                                ),
                                onClick = {
                                    menuExpanded = false
                                    showPresentationPicker = true
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.pdf_continuous_scroll), modifier = Modifier.weight(1f))
                                        if (pdfSettings.continuousScroll) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    settingsViewModel.updatePdf {
                                        it.copy(continuousScroll = !it.continuousScroll)
                                    }
                                    menuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.common_search), modifier = Modifier.weight(1f))
                                        Icon(
                                            Icons.Filled.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    menuExpanded = false
                                    showSearchSheet = true
                                },
                                enabled = state.document != null,
                            )
                            // TOC entry point is hidden when the file has no outline or the
                            // outline failed to parse (encrypted/malformed → empty list).
                            if (state.toc.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(stringResource(R.string.pdf_toc), modifier = Modifier.weight(1f))
                                            Icon(
                                                Icons.AutoMirrored.Filled.List,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showToc = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_settings_title)) },
                                onClick = {
                                    menuExpanded = false
                                    showSettings = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_jump_to_page)) },
                                onClick = {
                                    menuExpanded = false
                                    showPageJump = true
                                },
                            )
                            // Neighbouring books: the read list order, else the series (mirrors the
                            // Divina menu; another reader takes over through onOpenBook). Both sides
                            // resume on their own progress, never on the end page.
                            state.adjacent.previous?.let { previous ->
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reader_menu_previous_book)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.flushProgress(currentPage)
                                        onOpenBook(previous, false)
                                    },
                                )
                            }
                            state.adjacent.next?.let { next ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reader_menu_next_book)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.flushProgress(currentPage)
                                        onOpenBook(next, false)
                                    },
                                )
                            }
                        }
                    }
                }
                if (pdfSettings.controlsScrim) {
                    // Gradient mode: full-width band that fades into the page, extending under
                    // the status bar (background is applied before the insets padding)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Swallow stray taps across the whole band so they cannot fall
                            // through to the PdfView below and toggle the chrome
                            .pointerInput(Unit) { detectTapGestures { } }
                            .background(topChromeBrush)
                            // ignoringVisibility: layout must not jump when system bars show/hide
                            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                            // Extra transparent strip at the bottom so the fade has room to
                            // dissolve into the page instead of ending at the buttons
                            .padding(bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        topBarBack()
                        topBarTitle(Modifier.weight(1f))
                        topBarMenu()
                    }
                } else {
                    // Solid mode: left adaptive-length capsule (back + title) and right capsule
                    // (menu); the gap between the capsules stays clear so taps still reach the
                    // PdfView below
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // ignoringVisibility: layout must not jump when system bars show/hide
                            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        // Push the menu capsule to the trailing edge while the title capsule
                        // keeps its adaptive, content-hugging length
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                // Hug the content, but shrink (title ellipsizes) when space is tight
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(24.dp))
                                .background(chromeScrim)
                                // Faint edge: keeps the capsule visible on pages matching the
                                // reader background colour
                                .border(1.dp, chromeText.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                                // Swallow stray taps inside the capsule so they cannot fall
                                // through to the PdfView below
                                .pointerInput(Unit) { detectTapGestures { } }
                                // Asymmetric padding: the back IconButton already contributes
                                // its own internal padding on the left, so the trailing side
                                // gets the matching 16dp for a visually equal margin
                                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            topBarBack()
                            topBarTitle(Modifier)
                        }
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(chromeScrim)
                                .border(1.dp, chromeText.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                                .pointerInput(Unit) { detectTapGestures { } },
                        ) {
                            topBarMenu()
                        }
                    }
                }
        }

        // Bottom bar: page capsule + capsule progress bar (mirrors the comic reader and
        // iOS DivinaControlsOverlayView bottomOverlayContent — no interactive slider there)
        AnimatedVisibility(
            visible = chromeShown,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (pdfSettings.controlsScrim) {
                            // Gradient mode: full-width band that fades upward into the page,
                            // extending under the navigation bar
                            Modifier
                                // Swallow stray taps across the whole band so they cannot fall
                                // through to the PdfView below
                                .pointerInput(Unit) { detectTapGestures { } }
                                .background(bottomChromeBrush)
                                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                                // Extra transparent strip at the top so the fade has room to
                                // dissolve into the page
                                .padding(top = 14.dp)
                        } else {
                            // Solid mode: no wrapping card — the page-jump capsule and the
                            // progress bar float on their own, so taps between them reach the
                            // PdfView below
                            Modifier
                                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    PageJumpCapsule(
                        page = currentPage,
                        pageCount = pageCount,
                        onClick = { showPageJump = true },
                        contentColor = chromeText,
                        // On the gradient band a translucent content-tinted capsule reads well;
                        // floating free it needs the scrim itself as its container
                        containerColor = if (pdfSettings.controlsScrim) chromeText.copy(alpha = 0.14f) else chromeScrim,
                    )
                }
                // Capsule progress bar (mirrors the comic reader / iOS ReadingProgressBar:
                // dim track, white fill, reversed in RTL)
                val fillFraction =
                    if (pageCount > 0) {
                        (currentPage.toFloat() / pageCount).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 12.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (pdfSettings.controlsScrim) chromeText.copy(alpha = 0.3f) else chromeTrack),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fillFraction)
                            .align(
                                if (readingDirection == ReadingDirection.Rtl) {
                                    Alignment.CenterEnd
                                } else {
                                    Alignment.CenterStart
                                }
                            )
                            .background(chromeText),
                    )
                }
            }
        }
    }

    if (showSettings) {
        PdfPreferencesSheet(
            settings = pdfSettings,
            readingDirection = readingDirection,
            onSettingsChange = { new -> settingsViewModel.updatePdf { new } },
            common = readerSettings,
            onCommonUpdate = { transform -> settingsViewModel.update(transform) },
            onDismiss = { showSettings = false },
        )
    }
    if (showToc && state.toc.isNotEmpty()) {
        PdfTocSheet(
            entries = state.toc,
            currentPage = currentPage,
            onDismiss = { showToc = false },
            onJumpToPage = { page -> jumpTo(page) },
        )
    }
    if (showSearchSheet) {
        PdfSearchSheet(
            search = viewModel::searchWithSnippets,
            onSelectResult = { result -> jumpTo(result.pageNumber) },
            onDismiss = { showSearchSheet = false },
        )
    }
    if (showPageJump) {
        ReaderPageJumpSheet(
            currentPage = currentPage,
            pageCount = pageCount,
            // Real page thumbnails via the document's own render pipeline (Coil-fetched,
            // keyed by the physical page number so RTL/cover reordering cannot mislabel them)
            imageLoader = thumbImageLoader,
            pageThumbnailModel = { PdfPageThumb(it - 1) },
            onJump = {
                jumpTo(it)
                showPageJump = false
            },
            onDismiss = { showPageJump = false },
            // Right-to-left series: the preview strip starts at the right (mirrors the iOS
            // PageJumpSheetView layoutDirection), so the strip reads in the book's own page order.
            isRtl = readingDirection == ReadingDirection.Rtl,
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

    // Quick-picker dialogs (mirror the iOS menu picker submenus): single-choice list with a
    // checkmark on the current value; picking is session-only (see the overrides above).
    if (showDirectionPicker) {
        val directions = listOf(ReadingDirection.Ltr, ReadingDirection.Rtl, ReadingDirection.Vertical)
        val labels = directions.associateWith { stringResource(it.labelRes) }
        OptionPickerDialog(
            title = stringResource(R.string.reader_menu_direction),
            options = labels.values.toList(),
            selected = labels[readingDirection].orEmpty(),
            onSelect = { label ->
                directionOverride = labels.entries.firstOrNull { it.value == label }?.key
                showDirectionPicker = false
            },
            onDismiss = { showDirectionPicker = false },
        )
    }
    if (showPresentationPicker) {
        val labels = PdfPagePresentationOptions.associateWith {
            stringResource(pdfPagePresentationLabelRes(it))
        }
        val current = normalizePdfPagePresentation(presentationOverride ?: pdfSettings.pagePresentation)
        OptionPickerDialog(
            title = stringResource(R.string.pdf_page_presentation),
            options = labels.values.toList(),
            selected = labels[current].orEmpty(),
            onSelect = { label ->
                presentationOverride = labels.entries.firstOrNull { it.value == label }?.key
                showPresentationPicker = false
            },
            onDismiss = { showPresentationPicker = false },
        )
    }
}

/** Tracks whether the initial page has been applied for the current document. */
private class InitialApply {
    var appliedFor: PdfDocument? = null
}

/**
 * First page (1-based display position) the view's own page indicator names: "3-6 / 189" -> 3,
 * "12 / 188" -> 12.
 *
 * `PdfView.firstVisiblePage` and the `OnViewportChangedListener` argument both report 0 for
 * every viewport this reader lays out, while the indicator the view paints follows the real
 * scroll position, so this is the only source of position left. Returns null when the view has
 * no usable indicator yet (before the first layout).
 */
private fun PdfView.firstVisibleDisplayPage(): Int? =
    currentPageIndicatorLabel.substringBefore('-').substringBefore(' ').trim().toIntOrNull()

/** Detects short taps without consuming events (PdfView keeps selection/gestures). */
private fun Modifier.unconsumedTap(onTap: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            val startPos = down.position
            var moved = false
            val up =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        if (
                            (change.position - startPos).getDistance() >
                                viewConfiguration.touchSlop
                        ) {
                            moved = true
                        }
                        if (!change.pressed) return@withTimeoutOrNull change
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
            if (up != null && !moved) onTap()
        }
    }


