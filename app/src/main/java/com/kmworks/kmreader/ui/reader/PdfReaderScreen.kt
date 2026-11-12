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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.data.Book

/**
 * PDF reader (mirrors PdfReaderView / PdfControlsOverlayView)：
 * 整本下载到本地缓存 → PdfRenderer 逐页渲染；页码/缩放/chrome 与 DIVINA 一致。
 * 无会话回退演示占位骨架。
 */
@Composable
fun PdfReaderScreen(
    book: Book,
    onBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(book.id) { viewModel.load(book) }

    val pageCount = if (state.ready) state.pageCount else book.pageCount.coerceAtLeast(1)
    var currentPage by rememberSaveable(book.id) { mutableIntStateOf(1) }
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var pdfSettings by remember { mutableStateOf(PdfReaderSettings()) }

    // 初始页：真实数据就绪后应用一次
    var initialApplied by rememberSaveable(book.id) { mutableStateOf(false) }
    LaunchedEffect(state.ready) {
        if (state.ready && !initialApplied) {
            initialApplied = true
            currentPage = state.initialPage.coerceIn(1, pageCount)
        }
    }

    // 阅读进度上报（真实会话；末页标记完成）
    LaunchedEffect(currentPage, state.ready) {
        if (state.ready) viewModel.reportProgress(currentPage)
    }

    // 阅读背景：白/灰/黑/跟随系统（本 app 深色单色系，跟随系统=黑）
    val darkTheme = isSystemInDarkTheme()
    val backgroundColor = when (pdfSettings.background) {
        "白" -> Color.White
        "灰" -> Color(0xFF6E6E73)
        "黑" -> Color.Black
        else -> if (darkTheme) Color.Black else Color.White
    }
    val onBackground = if (backgroundColor == Color.White) Color.Black else Color.White
    val scrimAlpha = if (pdfSettings.controlsScrim) 0.78f else 0f

    // 缩放：双击切换（对齐 ReaderScreen），捏合仅在放大态
    var zoomed by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val animatedZoom by animateFloatAsState(if (zoomed) zoom.coerceAtLeast(2.2f) else 1f, label = "pdfZoom")

    val pagerState = rememberPagerState(initialPage = 0) { pageCount }
    // 页码同步：pager → currentPage
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { currentPage = it + 1 }
    }
    // 初始页应用后滚动 pager 到对应页
    LaunchedEffect(state.ready, initialApplied) {
        if (state.ready && initialApplied && !pdfSettings.continuousScroll &&
            pdfSettings.pagePresentation != "单页连续" && pdfSettings.pagePresentation != "双页连续"
        ) {
            pagerState.scrollToPage((currentPage - 1).coerceIn(0, pageCount - 1))
        }
    }

    // 渲染目标宽：屏宽像素（封顶防大图 OOM）
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val renderWidthPx = remember(screenWidthDp) {
        (screenWidthDp * density.density).toInt().coerceIn(720, 1600)
    }

    // 连续模式（含双页连续）
    val isContinuous = pdfSettings.continuousScroll ||
        pdfSettings.pagePresentation == "单页连续" ||
        pdfSettings.pagePresentation == "双页连续"
    val isDualPage = pdfSettings.pagePresentation == "双页连续"
    // 双页行：封面独立成页时第 1 页单独一行
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
                        text = state.downloadProgress?.let { "下载中 ${(it * 100).toInt()}%" }
                            ?: "正在下载 PDF…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBackground.copy(alpha = 0.7f),
                    )
                }
            }
            state.hasSession && state.error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = state.error ?: "加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBackground.copy(alpha = 0.8f),
                    )
                    Button(onClick = { viewModel.retry(book) }) {
                        Text("重试")
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
                                    PdfPage(
                                        page = page,
                                        ready = state.ready,
                                        renderWidthPx = renderWidthPx / row.size,
                                        viewModel = viewModel,
                                    )
                                }
                            }
                            // 双页连续：奇数行补半宽占位（单页列不补）
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
                        PdfPage(
                            page = index + 1,
                            ready = state.ready,
                            renderWidthPx = renderWidthPx,
                            viewModel = viewModel,
                        )
                    }
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
                color = onBackground.copy(alpha = 0.8f),
                trackColor = onBackground.copy(alpha = 0.2f),
            )
        }

        // Top bar (mirrors PdfControlsOverlayView top bar)
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
                        contentDescription = "返回",
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
                                pdfSettings = pdfSettings.copy(continuousScroll = !pdfSettings.continuousScroll)
                                menuExpanded = false
                            },
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

    // 跳页/滑杆结果同步到容器（pager/列表）
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
            onSettingsChange = { pdfSettings = it },
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

/** 单页内容：真实渲染 Bitmap（渲染中/演示态显示占位）。 */
@Composable
private fun PdfPage(
    page: Int,
    ready: Boolean,
    renderWidthPx: Int,
    viewModel: PdfReaderViewModel,
) {
    if (!ready) {
        PdfPagePlaceholder(page = page)
        return
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, page, renderWidthPx) {
        value = viewModel.renderPage(page, renderWidthPx)
    }
    val rendered = bitmap
    if (rendered == null) {
        PdfPagePlaceholder(page = page)
    } else {
        Image(
            bitmap = rendered.asImageBitmap(),
            contentDescription = "第 $page 页",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Gray document page placeholder（渲染中/演示态）。 */
@Composable
private fun PdfPagePlaceholder(page: Int) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(0.72f)
            .background(Color(0xFF2A2A2C), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "PDF 第 $page 页",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}
