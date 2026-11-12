package com.kmworks.kmreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.ReaderViewItem
import com.kmworks.kmreader.data.ReaderViewItemGenerator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ScrimColor = Color.Black.copy(alpha = 0.78f)
private val PageFill = Color(0xFF1C1C1F)
private val PageLine = Color.White.copy(alpha = 0.12f)

/**
 * 漫画阅读器（设计稿）。
 *
 * 页面单元、宽图判定与切割规则对齐原作
 * KMReader/Features/Reader/Models/Divina/ReaderViewItemGenerator.swift：
 * 宽图（横版）在「拆分宽页」开启时整幅展示或切成前后半，双页模式下单独占一个 spread。
 *
 * 手势对齐原作 DIVINA 的 UIKit 手势栈：
 * - 横向滑动翻页（Compose Pager 的 drag + velocity fling，等价 UIPanGestureRecognizer 翻页）
 * - 点按左/右三分之一翻页，中间呼出/收起 chrome（等价 TapZoneMode 默认分区）
 * - 双击缩放、双指捏合缩放与平移（等价 UITapGestureRecognizer 双击 + UIPinchGestureRecognizer）
 */
@Composable
fun ReaderScreen(book: Book, onBack: () -> Unit) {
    var chromeVisible by remember { mutableStateOf(true) }
    var page by remember {
        mutableIntStateOf(((book.progress ?: 0f) * book.pageCount).roundToInt().coerceAtLeast(1))
    }

    var menuOpen by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(ReadingDirection.Rtl) }
    var dualPage by remember { mutableStateOf(false) }
    var splitWideMode by remember { mutableStateOf(SplitWidePageMode.Auto) }
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }

    // 缩放：双击在 1x / 2.2x 之间切换，捏合手势连续缩放
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoomed by remember { mutableStateOf(false) }

    val readerPages = remember(book) { ReaderViewItemGenerator.pagesFor(book) }
    // 条漫/竖排不支持双页与拆分宽页（对齐原作的门控）
    val effectiveDualPage = dualPage && direction != ReadingDirection.Vertical && direction != ReadingDirection.Webtoon
    val items = remember(readerPages, effectiveDualPage, splitWideMode) {
        ReaderViewItemGenerator.generate(
            pages = readerPages,
            dualPage = effectiveDualPage,
            splitWidePages = splitWideMode.isEnabled,
            keepsSplitSpreadsWhole = splitWideMode.keepsWholeSpread,
        )
    }
    val pagerState = rememberPagerState(
        initialPage = items.indexOfFirst { it.pages.contains(page) }.coerceAtLeast(0),
    ) { items.size }
    val scope = rememberCoroutineScope()

    // Pager → 页码
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.currentPage }.collect { index ->
            items.getOrNull(index)?.pages?.firstOrNull()?.let { page = it }
        }
    }
    // 页码（滑杆 / 跳页 / 目录 / 缩略图）→ Pager
    LaunchedEffect(page, items) {
        val target = items.indexOfFirst { it.pages.contains(page) }
        if (target >= 0 && target != pagerState.currentPage) pagerState.animateScrollToPage(target)
    }
    // 切换双页/拆分宽页后，落到当前页所在单元
    LaunchedEffect(effectiveDualPage, splitWideMode) {
        val target = items.indexOfFirst { it.pages.contains(page) }
        if (target >= 0) pagerState.scrollToPage(target)
    }

    val animatedZoom by animateFloatAsState(if (zoomed) 2.2f else zoom, label = "readerZoom")

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val isVertical = direction == ReadingDirection.Vertical || direction == ReadingDirection.Webtoon

        val gestureModifier = Modifier.pointerInput(items, pagerState.currentPage, zoomed) {
            detectTapGestures(
                onTap = { offset ->
                    val third = size.width / 3f
                    when {
                        offset.x < third -> scope.launch {
                            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                        }
                        offset.x > size.width - third -> scope.launch {
                            pagerState.animateScrollToPage(
                                (pagerState.currentPage + 1).coerceAtMost(items.lastIndex),
                            )
                        }
                        else -> chromeVisible = !chromeVisible
                    }
                },
                onDoubleTap = {
                    zoomed = !zoomed
                    if (!zoomed) pan = Offset.Zero
                },
            )
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

        if (isVertical) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().then(zoomModifier).then(gestureModifier),
                userScrollEnabled = !zoomed,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { index ->
                ViewerPage(item = items[index])
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().then(zoomModifier).then(gestureModifier),
                userScrollEnabled = !zoomed,
                reverseLayout = direction == ReadingDirection.Rtl,
                contentPadding = PaddingValues(horizontal = 4.dp),
                pageSpacing = 8.dp,
            ) { index ->
                ViewerPage(item = items[index])
            }
        }

        // 顶栏 chrome
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScrimColor)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "退出阅读",
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        book.seriesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Text(
                        book.number,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Color.White)
                    }
                    ReaderOptionsMenu(
                        expanded = menuOpen,
                        direction = direction,
                        dualPage = dualPage,
                        splitWideMode = splitWideMode,
                        rotationDegrees = rotationDegrees,
                        onDirectionChange = { direction = it },
                        onDualPageChange = { dualPage = it },
                        onSplitWideModeChange = { splitWideMode = it },
                        onRotationChange = { rotationDegrees = it },
                        onOpenSettings = { showSettings = true },
                        onOpenToc = { showToc = true },
                        onOpenPageJump = { showPageJump = true },
                        onDismiss = { menuOpen = false },
                    )
                }
            }
        }

        // 底栏 chrome：缩略图条 + 滑杆 + 页码胶囊
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScrimColor),
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items.size) { index ->
                        val item = items[index]
                        val isCurrent = index == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(54.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isCurrent) PageFill else Color(0xFF2A2A2E))
                                .then(
                                    if (isCurrent) {
                                        Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = item.pages.joinToString("·"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
                Slider(
                    value = page.toFloat(),
                    onValueChange = { page = it.roundToInt().coerceIn(1, book.pageCount) },
                    valueRange = 1f..book.pageCount.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                // 页码胶囊：点开 PageJumpSheetView（对应原作底栏中央按钮）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    PageJumpCapsule(
                        page = page,
                        pageCount = book.pageCount,
                        onClick = { showPageJump = true },
                    )
                }
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(onDismiss = { showSettings = false })
    }
    if (showToc) {
        ReaderTocSheet(
            currentPage = page,
            onJump = {
                page = it.coerceIn(1, book.pageCount)
                showToc = false
            },
            onDismiss = { showToc = false },
        )
    }
    if (showPageJump) {
        ReaderPageJumpSheet(
            currentPage = page,
            pageCount = book.pageCount,
            onJump = {
                page = it.coerceIn(1, book.pageCount)
                showPageJump = false
            },
            onDismiss = { showPageJump = false },
        )
    }
}

/** 单个展示单元的占位渲染：单页 / 双页合幅 / 跨页整幅 / 跨页半边。 */
@Composable
private fun ViewerPage(item: ReaderViewItem) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (item) {
            is ReaderViewItem.Single -> PagePlaceholder(
                label = "第 ${item.page.number} 页",
                ratio = item.page.width.toFloat() / item.page.height.toFloat(),
            )

            is ReaderViewItem.Dual -> Row(
                modifier = Modifier.fillMaxWidth(0.96f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PagePlaceholder(
                    label = "第 ${item.first.number} 页",
                    ratio = item.first.width.toFloat() / item.first.height.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                PagePlaceholder(
                    label = "第 ${item.second.number} 页",
                    ratio = item.second.width.toFloat() / item.second.height.toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }

            is ReaderViewItem.Spread -> PagePlaceholder(
                label = "跨页 · 第 ${item.page.number} 页（宽图整幅）",
                ratio = item.page.width.toFloat() / item.page.height.toFloat(),
                wide = true,
            )

            is ReaderViewItem.Half -> PagePlaceholder(
                label = "跨页 · 第 ${item.page.number} 页（${if (item.secondHalf) "右半" else "左半"}）",
                ratio = (item.page.width / 2f) / item.page.height.toFloat(),
                half = if (item.secondHalf) 1 else 0,
            )
        }
    }
}

@Composable
private fun PagePlaceholder(
    label: String,
    ratio: Float,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    half: Int? = null,
) {
    val safeRatio = if (ratio.isFinite() && ratio > 0f) ratio else 0.7f
    Box(
        modifier = modifier
            .fillMaxWidth(if (wide) 1f else 0.92f)
            .aspectRatio(safeRatio)
            .clip(RoundedCornerShape(2.dp))
            .background(PageFill)
            .border(1.dp, PageLine, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF6E6E74),
            )
            if (wide) {
                Text(
                    text = "← 横向拖动可平移整幅 →",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4A4A50),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (half != null) {
                Text(
                    text = if (half == 0) "← 前半" else "后半 →",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4A4A50),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
