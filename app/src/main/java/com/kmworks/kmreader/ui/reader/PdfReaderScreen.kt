package com.kmworks.kmreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import com.kmworks.kmreader.data.Book
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PDF reader (mirrors PdfReaderView / PdfControlsOverlayView) backed by androidx.pdf PdfView:
 * native rendering, text selection / copy, zoom and search.
 */
@Composable
fun PdfReaderScreen(
    book: Book,
    onBack: () -> Unit,
    incognito: Boolean = false,
    viewModel: PdfReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pdfSettings by remember { mutableStateOf(PdfReaderSettings()) }
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var searchIndex by remember { mutableIntStateOf(0) }
    var pdfViewRef by remember { mutableStateOf<PdfView?>(null) }
    // Initial-page application marker, keyed to the loaded document
    val initialApply = remember { InitialApply() }

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
        when (pdfSettings.background) {
            "白" -> Color.White
            "灰" -> Color(0xFF3A3A3C)
            else -> if (isSystemInDarkTheme()) Color.Black else Color.White
        }
    val scrim =
        if (pdfSettings.controlsScrim) Color.Black.copy(alpha = 0.78f) else Color.Transparent

    // Progress reporting (real session only; incognito = Peek, no reporting)
    LaunchedEffect(currentPage, state.ready, incognito) {
        if (state.ready && !incognito) viewModel.reportProgress(currentPage)
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
                        if (progress != null) "下载中 ${(progress * 100).toInt()}%" else "下载中…",
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
                        "PDF 加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    Button(onClick = { viewModel.retry() }) { Text("重试") }
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
                        val wantedRows = if (pdfSettings.pagePresentation == "双页连续") 2 else 1
                        if (view.pagesPerRow != wantedRows) view.pagesPerRow = wantedRows
                        view.setBackgroundColor(backgroundColor.toArgb())
                        val doc = state.document
                        if (doc != null && initialApply.appliedFor !== doc) {
                            initialApply.appliedFor = doc
                            view.scrollToPage(state.initialPage - 1)
                            currentPage = state.initialPage
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
                        "需要连接服务器才能阅读",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    TextButton(onClick = onBack) { Text("返回", color = Color.White) }
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
                        .background(scrim)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    // 隐私阅读指示（对齐原作顶栏标题前 eye.slash）
                    if (incognito) {
                        Icon(
                            Icons.Filled.VisibilityOff,
                            contentDescription = "隐私阅读",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
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
                            "第 ${book.number} 册 · PDF",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多",
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
                                        Text("连续滚动", modifier = Modifier.weight(1f))
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
                                    pdfSettings =
                                        pdfSettings.copy(
                                            continuousScroll = !pdfSettings.continuousScroll,
                                        )
                                    menuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("搜索", modifier = Modifier.weight(1f))
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
                                text = { Text("阅读器设置") },
                                onClick = {
                                    menuExpanded = false
                                    showSettings = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("跳页") },
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
                    .background(scrim)
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
            onSettingsChange = { pdfSettings = it },
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
}

/** Tracks whether the initial page has been applied for the current document. */
private class InitialApply {
    var appliedFor: PdfDocument? = null
}

/** Search chrome row (query input + prev/next navigation). */
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
            .background(scrim)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "关闭搜索", tint = Color.White)
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("搜索文档内容") },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (matchCount > 0) "${matchIndex + 1}/$matchCount" else "0/0",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(onClick = onPrev, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一处", tint = Color.White)
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下一处", tint = Color.White)
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

