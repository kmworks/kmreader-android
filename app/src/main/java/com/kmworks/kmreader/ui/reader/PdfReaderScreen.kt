package com.kmworks.kmreader.ui.reader

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.pdf.PdfPageOrder
import com.kmworks.kmreader.core.pdf.PdfPageOrderDocument
import com.kmworks.kmreader.data.Book
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
    val readingDirection = resolveReadingDirection(
        seriesDirection = readingDirectionFromSeriesToken(state.seriesDirection),
        forceDefault = readerSettings.forceDefaultDirection,
        defaultDirection = defaultDirection,
    ).forPdfReader()
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }
    var searchVisible by remember { mutableStateOf(false) }
    // A modal sheet dims the reader window, so the chrome scrim must not stay painted behind it
    // (it would show through the dim as a band above/below the sheet). chromeVisible itself is
    // left untouched: dismissing the sheet brings the chrome back, and the hidden-chrome progress
    // bar keeps its "chrome is up" reading. The overflow menu is not a sheet — its items close it
    // before opening one.
    val sheetOpen = showSettings || showPageJump || showToc || showDetailSheet
    val chromeShown = chromeVisible && !sheetOpen
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var searchIndex by remember { mutableIntStateOf(0) }
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
        pdfSettings.pagePresentation,
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
    val pagesPerRow = if (resolvedPresentation == PDF_PRESENTATION_DUAL_CONTINUOUS) 2 else 1
    // Discrete page-turn matrix (P2): singlePaged is always discrete; the other layouts
    // become discrete when continuous scroll is off.
    val discretePages = resolvedPresentation == PDF_PRESENTATION_SINGLE || !pdfSettings.continuousScroll

    // Page-order decoration (P1b/P3): RTL mirrors each spread row (iOS displaysRTL) and the
    // solo cover inserts a virtual blank position in two-page layouts (iOS displaysAsBook).
    // Everything user-facing keeps physical page numbers — the capsule, TOC, search, jump
    // sheet and progress — only the positions handed to PdfView are remapped.
    val rtl = readingDirection == ReadingDirection.Rtl
    val pageOrder =
        remember(state.pageCount, pagesPerRow, rtl, pdfSettings.soloCoverPage) {
            PdfPageOrder(state.pageCount, pagesPerRow, rtl, pdfSettings.soloCoverPage)
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

    val backgroundColor =
        when (normalizePdfBackground(pdfSettings.background)) {
            PDF_BACKGROUND_WHITE -> Color.White
            PDF_BACKGROUND_GRAY -> Color(0xFF3A3A3C)
            PDF_BACKGROUND_BLACK -> Color.Black
            else -> if (isSystemInDarkTheme()) Color.Black else Color.White
        }
    // Chrome styling mirrors the comic reader (ReaderScreen): a gradient scrim when the
    // toggle is on and a solid scrim otherwise — never fully transparent, so the white
    // icons stay readable on any page background. The foreground of elements that sit
    // directly on the page (loading state, hidden-chrome progress bar) adapts to it.
    val chromeScrim = Color.Black.copy(alpha = 0.82f)
    val topChromeBrush =
        if (pdfSettings.controlsScrim) {
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))
        } else {
            Brush.verticalGradient(listOf(chromeScrim, chromeScrim))
        }
    val bottomChromeBrush =
        if (pdfSettings.controlsScrim) {
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))
        } else {
            Brush.verticalGradient(listOf(chromeScrim, chromeScrim))
        }
    // backgroundColor is one of White / 0xFF3A3A3C / Black (system maps to Black or White),
    // so a white check is enough to know the page is light
    val onBackgroundColor = if (backgroundColor == Color.White) Color.Black else Color.White

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

    // Debounced full-text search
    LaunchedEffect(searchQuery, state.document) {
        val query = searchQuery.trim()
        if (query.isEmpty() || state.document == null) {
            searchMatches = emptyList()
            searchIndex = 0
            return@LaunchedEffect
        }
        delay(400)
        val matches = viewModel.search(query)
        searchMatches = matches
        searchIndex = 0
        if (matches.isNotEmpty()) jumpTo(matches[0])
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        when {
            state.loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = onBackgroundColor)
                    val progress = state.downloadProgress
                    Text(
                        if (progress != null) {
                            stringResource(R.string.pdf_downloading_progress, (progress * 100).toInt())
                        } else {
                            stringResource(R.string.pdf_downloading)
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
                        container.rtl = rtl
                        container.onNextPage = { jumpTo(currentPage + pagesPerRow) }
                        container.onPrevPage = { jumpTo(currentPage - pagesPerRow) }
                        view.setBackgroundColor(backgroundColor.toArgb())
                        // The view speaks display positions; page numbers stay physical
                        view.setOnScrollChangeListener { _, _, _, _, _ ->
                            if (state.document != null) {
                                val physical =
                                    pageOrder.positionToPhysical(view.firstVisiblePage)
                                        .coerceAtLeast(0)
                                currentPage = (physical + 1).coerceIn(1, pageCount)
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
        // the comic reader's 2dp bar, tinted against the page background
        if (!chromeVisible && pdfSettings.showProgressWhileReading) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(onBackgroundColor.copy(alpha = 0.15f)),
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
            if (searchVisible) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    matchIndex = searchIndex,
                    matchCount = searchMatches.size,
                    onPrev = {
                        if (searchMatches.isNotEmpty()) {
                            searchIndex =
                                (searchIndex - 1 + searchMatches.size) % searchMatches.size
                            jumpTo(searchMatches[searchIndex])
                        }
                    },
                    onNext = {
                        if (searchMatches.isNotEmpty()) {
                            searchIndex = (searchIndex + 1) % searchMatches.size
                            jumpTo(searchMatches[searchIndex])
                        }
                    },
                    onClose = {
                        searchVisible = false
                        searchQuery = ""
                    },
                    scrimBrush = topChromeBrush,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Swallow stray taps across the whole row so they cannot fall through
                        // to the PdfView below and toggle the chrome
                        .pointerInput(Unit) { detectTapGestures { } }
                        .background(topChromeBrush)
                        // ignoringVisibility: layout must not jump when system bars show/hide
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = Color.White,
                        )
                    }
                    // Private-reading indicator (mirrors the original top-bar eye.slash before the title)
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
                            // Mirrors the iOS top-bar title button: tapping the title opens the book detail sheet
                            .clickable { showDetailSheet = true },
                    ) {
                        // Mirrors iOS: oneshots show the book title and hide the volume label
                        Text(
                            if (book.oneshot) book.title else book.seriesTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                        )
                        Text(
                            if (book.oneshot) {
                                "PDF"
                            } else {
                                stringResource(R.string.reader_volume_pdf, book.number)
                            },
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
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            // The book-detail entry lives in the top-bar title (mirrors iOS);
                            // it is not repeated in this menu
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
                                    searchVisible = true
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
            }
        }

        // Bottom bar: page capsule + capsule progress bar (mirrors the comic reader and
        // iOS DivinaControlsOverlayView bottomOverlayContent — no interactive slider there)
        AnimatedVisibility(
            visible = chromeShown && !searchVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Swallow stray taps so they cannot fall through to the PdfView below
                    .pointerInput(Unit) { detectTapGestures { } }
                    .background(bottomChromeBrush)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
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
                        .background(Color.White.copy(alpha = 0.3f)),
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
                            .background(Color.White),
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
    if (showPageJump) {
        ReaderPageJumpSheet(
            currentPage = currentPage,
            pageCount = pageCount,
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
}

/** Tracks whether the initial page has been applied for the current document. */
private class InitialApply {
    var appliedFor: PdfDocument? = null
}

/** Search chrome row (query input + prev/next navigation). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchIndex: Int,
    matchCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    scrimBrush: Brush,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Swallow stray taps across the whole row so they cannot fall through to the PdfView below
            .pointerInput(Unit) { detectTapGestures { } }
            .background(scrimBrush)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.reader_close_search), tint = Color.White)
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.reader_search_document)) },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (matchCount > 0) "${matchIndex + 1}/$matchCount" else "0/0",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(onClick = onPrev, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.reader_previous_match), tint = Color.White)
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.reader_next_match), tint = Color.White)
        }
    }
}

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

