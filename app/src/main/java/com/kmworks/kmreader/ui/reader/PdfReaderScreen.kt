package com.kmworks.kmreader.ui.reader

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
    onVisitSeries: (Book) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onBrowseFilter: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // PDF preferences are persisted globally (mirroring the original pdf* @AppStorage);
    // the reader and the defaults page share the same values
    val pdfSettings by settingsViewModel.pdfSettings.collectAsStateWithLifecycle()
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var searchIndex by remember { mutableIntStateOf(0) }
    var pdfViewRef by remember { mutableStateOf<PdfView?>(null) }
    // Initial-page application marker, keyed to the loaded document
    val initialApply = remember { InitialApply() }
    // Do not report progress before the restored page is applied (otherwise the 2s
    // throttle / exit flush would report page 1 first and cross the gating threshold)
    var initialPageApplied by remember { mutableStateOf(false) }

    LaunchedEffect(book.id, incognito) {
        viewModel.incognito = incognito
        viewModel.load(book)
    }

    val pageCount =
        when {
            state.ready && state.pageCount > 0 -> state.pageCount
            else -> book.pageCount.coerceAtLeast(1)
        }

    fun jumpTo(page: Int) {
        val target = page.coerceIn(1, pageCount)
        currentPage = target
        pdfViewRef?.scrollToPage(target - 1)
    }

    val backgroundColor =
        when (normalizePdfBackground(pdfSettings.background)) {
            PDF_BACKGROUND_WHITE -> Color.White
            PDF_BACKGROUND_GRAY -> Color(0xFF3A3A3C)
            PDF_BACKGROUND_BLACK -> Color.Black
            else -> if (isSystemInDarkTheme()) Color.Black else Color.White
        }
    val scrim =
        if (pdfSettings.controlsScrim) Color.Black.copy(alpha = 0.78f) else Color.Transparent

    // Progress reporting (real session only; incognito = Peek, no reporting)
    // Mirrors the original: throttle reports by 2s and flush the last one on exit
    LaunchedEffect(currentPage, state.ready, incognito, initialPageApplied) {
        if (state.ready && !incognito && initialPageApplied) {
            delay(2_000)
            viewModel.reportProgress(currentPage)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (initialPageApplied) viewModel.reportProgress(currentPage) }
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
                    CircularProgressIndicator(color = Color.White)
                    val progress = state.downloadProgress
                    Text(
                        if (progress != null) {
                            stringResource(R.string.pdf_downloading_progress, (progress * 100).toInt())
                        } else {
                            stringResource(R.string.pdf_downloading)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
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
                        color = Color.White,
                    )
                    Button(onClick = { viewModel.retry() }) { Text(stringResource(R.string.common_retry)) }
                }
            }

            state.ready && state.document != null -> {
                AndroidView(
                    factory = { ctx ->
                        PdfView(ctx).also { view ->
                            view.setOnScrollChangeListener { _, _, _, _, _ ->
                                if (state.document != null) {
                                    currentPage = (view.firstVisiblePage + 1).coerceIn(1, pageCount)
                                }
                            }
                            pdfViewRef = view
                        }
                    },
                    update = { view ->
                        if (view.pdfDocument !== state.document) {
                            view.pdfDocument = state.document
                        }
                        val wantedRows =
                            if (normalizePdfPagePresentation(pdfSettings.pagePresentation) ==
                                PDF_PRESENTATION_DUAL_CONTINUOUS
                            ) {
                                2
                            } else {
                                1
                            }
                        if (view.pagesPerRow != wantedRows) view.pagesPerRow = wantedRows
                        view.setBackgroundColor(backgroundColor.toArgb())
                        val doc = state.document
                        if (doc != null && initialApply.appliedFor !== doc) {
                            initialApply.appliedFor = doc
                            view.scrollToPage(state.initialPage - 1)
                            currentPage = state.initialPage
                            initialPageApplied = true
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
                        color = Color.White,
                    )
                    TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Color.White) }
                }
            }
        }

        // Mini progress bar while chrome is hidden (mirrors showProgressBarWhileReading)
        if (!chromeVisible && pdfSettings.showProgressWhileReading) {
            LinearProgressIndicator(
                progress = { currentPage / pageCount.toFloat() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = Color.White.copy(alpha = 0.8f),
                trackColor = Color.White.copy(alpha = 0.2f),
            )
        }

        // Top bar (mirrors PdfControlsOverlayView top bar)
        AnimatedVisibility(
            visible = chromeVisible,
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
                    scrim = scrim,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Swallow stray taps across the whole row so they cannot fall through
                        // to the PdfView below and toggle the chrome
                        .pointerInput(Unit) { detectTapGestures { } }
                        .background(scrim)
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
                        }
                    }
                }
            }
        }

        // Bottom bar: slider + page capsule
        AnimatedVisibility(
            visible = chromeVisible && !searchVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Swallow stray taps so they cannot fall through to the PdfView below
                    .pointerInput(Unit) { detectTapGestures { } }
                    .background(scrim)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Slider(
                    value = currentPage.toFloat(),
                    onValueChange = { jumpTo(it.toInt()) },
                    valueRange = 1f..pageCount.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                PageJumpCapsule(
                    page = currentPage,
                    pageCount = pageCount,
                    onClick = { showPageJump = true },
                )
            }
        }
    }

    if (showSettings) {
        PdfPreferencesSheet(
            settings = pdfSettings,
            onSettingsChange = { new -> settingsViewModel.updatePdf { new } },
            onDismiss = { showSettings = false },
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
    scrim: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Swallow stray taps across the whole row so they cannot fall through to the PdfView below
            .pointerInput(Unit) { detectTapGestures { } }
            .background(scrim)
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

