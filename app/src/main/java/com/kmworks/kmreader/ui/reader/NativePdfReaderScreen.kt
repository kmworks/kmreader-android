package com.kmworks.kmreader.ui.reader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.Book

/**
 * Native PDF reader (the optional engine behind `USE_NATIVE_PDF_READER`): downloads the whole book
 * and renders page bitmaps with `android.graphics.pdf.PdfRenderer`. Page number, zoom and chrome
 * mirror the DIVINA reader; the androidx.pdf engine remains the default.
 */
@Composable
fun NativePdfReaderScreen(
    book: Book,
    onBack: () -> Unit,
    incognito: Boolean = false,
    startAtEnd: Boolean = false,
    viewModel: NativePdfReaderViewModel = hiltViewModel(),
    settingsViewModel: DivinaSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // PDF preferences are persisted globally, shared with the defaults page and the other engine.
    val pdfSettings by settingsViewModel.pdfSettings.collectAsStateWithLifecycle()
    LaunchedEffect(book.id, incognito, startAtEnd) {
        viewModel.incognito = incognito
        viewModel.load(book, startAtEnd = startAtEnd)
    }

    val pageCount = if (state.ready) state.pageCount else book.pageCount.coerceAtLeast(1)
    var currentPage by rememberSaveable(book.id) { mutableIntStateOf(1) }
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }

    // Initial page: applied once when the real document is ready.
    var initialApplied by rememberSaveable(book.id) { mutableStateOf(false) }
    LaunchedEffect(state.ready) {
        if (state.ready && !initialApplied) {
            initialApplied = true
            currentPage = state.initialPage.coerceIn(1, pageCount)
        }
    }

    // Reading progress (real session only; the last page marks completion).
    LaunchedEffect(currentPage, state.ready) {
        if (state.ready) viewModel.reportProgress(currentPage)
    }
    // Exit flush goes through the recording gate, so a book opened and closed untouched records nothing.
    val latestPage by rememberUpdatedState(currentPage)
    DisposableEffect(Unit) {
        onDispose { viewModel.flushProgress(latestPage) }
    }

    // Reading background: system / white / gray / black (the app's dark theme is black).
    val darkTheme = isSystemInDarkTheme()
    val backgroundColor = when (normalizePdfBackground(pdfSettings.background)) {
        PDF_BACKGROUND_WHITE -> Color.White
        PDF_BACKGROUND_GRAY -> Color(0xFF6E6E73)
        PDF_BACKGROUND_BLACK -> Color.Black
        else -> if (darkTheme) Color.Black else Color.White
    }
    val onBackground = if (backgroundColor == Color.White) Color.Black else Color.White
    val scrimAlpha = if (pdfSettings.controlsScrim) 0.78f else 0f

    // Zoom: double tap toggles; pinch only while zoomed in.
    var zoomed by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val animatedZoom by animateFloatAsState(if (zoomed) zoom.coerceAtLeast(2.2f) else 1f, label = "nativePdfZoom")

    val pagerState = rememberPagerState(initialPage = 0) { pageCount }
    // Page sync: pager -> currentPage
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { currentPage = it + 1 }
    }
    // After the initial page is applied, scroll the pager/list to it.
    LaunchedEffect(state.ready, initialApplied) {
        if (state.ready && initialApplied && !pdfSettings.continuousScroll &&
            normalizePdfPagePresentation(pdfSettings.pagePresentation) != PDF_PRESENTATION_SINGLE_CONTINUOUS &&
            normalizePdfPagePresentation(pdfSettings.pagePresentation) != PDF_PRESENTATION_DUAL_CONTINUOUS
        ) {
            pagerState.scrollToPage((currentPage - 1).coerceIn(0, pageCount - 1))
        }
    }

    // Render width: screen width in pixels (capped to avoid huge bitmaps).
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val renderWidthPx = remember(screenWidthDp) {
        (screenWidthDp * density.density).toInt().coerceIn(720, 1600)
    }

    // Continuous mode (single or two-page rows)
    val presentation = normalizePdfPagePresentation(pdfSettings.pagePresentation)
    val isContinuous = pdfSettings.continuousScroll ||
        presentation == PDF_PRESENTATION_SINGLE_CONTINUOUS ||
        presentation == PDF_PRESENTATION_DUAL_CONTINUOUS
    val isDualPage = presentation == PDF_PRESENTATION_DUAL_CONTINUOUS
    // Two-page rows: the cover gets its own row when it is standalone.
    val pageRows: List<List<Int>> = remember(pageCount, isDualPage, pdfSettings.soloCoverPage) {
        if (!isDualPage) {
            (1..pageCount).map { listOf(it) }
        } else if (pdfSettings.soloCoverPage && pageCount > 1) {
            listOf(listOf(1)) + (2..pageCount).chunked(2)
        } else {
            (1..pageCount).chunked(2)
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, isContinuous) {
        if (isContinuous) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
                currentPage = pageRows.getOrNull(index)?.first() ?: currentPage
            }
        }
    }

    val zoomModifier = Modifier
        .pointerInput(zoomed) {
            if (zoomed) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                    pan += panChange
                }
            }
        }
        .graphicsLayer {
            scaleX = animatedZoom
            scaleY = animatedZoom
            translationX = pan.x
            translationY = pan.y
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .pointerInput(zoomed) {
                detectTapGestures(
                    onTap = { chromeVisible = !chromeVisible },
                    onDoubleTap = {
                        zoomed = !zoomed
                        if (!zoomed) {
                            zoom = 1f
                            pan = Offset.Zero
                        } else {
                            zoom = 2.2f
                        }
                    },
                )
            },
    ) {
        when {
            state.hasSession && state.loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = onBackground)
                    Text(
                        text = state.downloadProgress
                            ?.let { stringResource(R.string.pdf_downloading_progress, (it * 100).toInt()) }
                            ?: stringResource(R.string.pdf_downloading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBackground.copy(alpha = 0.7f),
                    )
                }
            }
            state.hasSession && state.error -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.pdf_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBackground.copy(alpha = 0.8f),
                    )
                    Button(onClick = { viewModel.retry() }) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
            isContinuous -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(zoomModifier),
                    userScrollEnabled = !zoomed,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(pageRows.size, key = { it }) { rowIndex ->
                        val row = pageRows[rowIndex]
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            row.forEach { page ->
                                Box(modifier = Modifier.weight(1f)) {
                                    NativePdfPage(
                                        page = page,
                                        ready = state.ready,
                                        renderWidthPx = renderWidthPx / row.size,
                                        viewModel = viewModel,
                                    )
                                }
                            }
                            // Two-page continuous: pad odd rows with an empty half column.
                            if (isDualPage) {
                                repeat((2 - row.size).coerceAtLeast(0)) {
                                    Box(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(zoomModifier),
                    userScrollEnabled = !zoomed,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    pageSpacing = 8.dp,
                ) { index ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NativePdfPage(
                            page = index + 1,
                            ready = state.ready,
                            renderWidthPx = renderWidthPx,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }

        // Mini progress bar while chrome is hidden
        if (!chromeVisible && pdfSettings.showProgressWhileReading) {
            LinearProgressIndicator(
                progress = { currentPage / pageCount.toFloat() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = onBackground.copy(alpha = 0.8f),
                trackColor = onBackground.copy(alpha = 0.2f),
            )
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
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .statusBarsPadding()
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        book.seriesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(
                        stringResource(R.string.pdf_reader_volume, book.number),
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
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.pdf_continuous_scroll),
                                        modifier = Modifier.weight(1f),
                                    )
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

        // Bottom bar: slider + page capsule
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Slider(
                    value = currentPage.toFloat(),
                    onValueChange = { currentPage = it.toInt().coerceIn(1, pageCount) },
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

    // Jump/slider results scroll the container (pager or list).
    LaunchedEffect(currentPage) {
        if (isContinuous) {
            val rowIndex = pageRows.indexOfFirst { it.contains(currentPage) }
            if (rowIndex >= 0 && listState.firstVisibleItemIndex != rowIndex) {
                listState.scrollToItem(rowIndex)
            }
        } else {
            val target = (currentPage - 1).coerceIn(0, pageCount - 1)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
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
                currentPage = it.coerceIn(1, pageCount)
                showPageJump = false
            },
            onDismiss = { showPageJump = false },
        )
    }
}

/** One page: the rendered bitmap, or the placeholder while it renders. */
@Composable
private fun NativePdfPage(
    page: Int,
    ready: Boolean,
    renderWidthPx: Int,
    viewModel: NativePdfReaderViewModel,
) {
    if (!ready) {
        NativePdfPagePlaceholder(page = page)
        return
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, page, renderWidthPx) {
        value = viewModel.renderPage(page, renderWidthPx)
    }
    val rendered = bitmap
    if (rendered == null) {
        NativePdfPagePlaceholder(page = page)
    } else {
        Image(
            bitmap = rendered.asImageBitmap(),
            contentDescription = stringResource(R.string.pdf_page_content, page),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Gray document page placeholder (while rendering). */
@Composable
private fun NativePdfPagePlaceholder(page: Int) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(0.72f)
            .background(Color(0xFF2A2A2C), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.pdf_page_placeholder, page),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}
