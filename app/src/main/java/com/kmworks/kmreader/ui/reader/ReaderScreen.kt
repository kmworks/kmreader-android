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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.ReaderPage
import com.kmworks.kmreader.data.ReaderViewItem
import com.kmworks.kmreader.data.ReaderViewItemGenerator
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ScrimColor = Color.Black.copy(alpha = 0.78f)
private val PageFill = Color(0xFF1C1C1F)
private val PageLine = Color.White.copy(alpha = 0.12f)

/**
 * 漫画阅读器（DIVINA）：真实页面管线（ReaderViewModel → BookPagesApi），
 * 无会话/样例书回退 ReaderViewItemGenerator 假数据。
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
fun ReaderScreen(
    book: Book,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
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

    // 会话图片加载器（与 CoverArt 同一取法）
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ImageLoaderEntryPoint::class.java,
        )
    }
    val sessionLoader by entryPoint.sessionManager().imageLoader.collectAsState()
    val imageLoader = sessionLoader ?: remember { SingletonImageLoader.get(context) }

    // 真实页面管线；无会话/样例书回退假数据
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(book) { viewModel.load(book) }
    val fallbackPages = remember(book) { ReaderViewItemGenerator.pagesFor(book) }
    val readerPages = uiState.pages.ifEmpty { fallbackPages }
    val pageCount = readerPages.size

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
    // 预加载：当前展示单元前后各 2 个单元的页面；settled 后上报阅读进度
    LaunchedEffect(pagerState, items, uiState.bookDtoId, imageLoader) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            if (uiState.bookDtoId == null) return@collect
            val item = items.getOrNull(index) ?: return@collect
            viewModel.reportProgress(item.pages.max())
            for (i in (index - 2)..(index + 2)) {
                items.getOrNull(i)?.pages?.forEach { number ->
                    viewModel.pageUrl(number)?.let { url ->
                        imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                    }
                }
            }
        }
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
                ViewerPage(
                    item = items[index],
                    imageLoader = imageLoader,
                    pageUrl = viewModel::pageUrl,
                )
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
                ViewerPage(
                    item = items[index],
                    imageLoader = imageLoader,
                    pageUrl = viewModel::pageUrl,
                )
            }
        }

        // 页面列表失败：回退占位数据并给出重试入口
        if (uiState.status == ReaderPagesStatus.Error) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ScrimColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "页面列表加载失败，正在显示占位页",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                TextButton(onClick = { viewModel.retry(book) }) {
                    Text("重试", color = Color.White)
                }
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
                        val thumbUrl = viewModel.pageThumbnailUrl(item.pages.first())
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
                            if (thumbUrl != null) {
                                AsyncImage(
                                    model = thumbUrl,
                                    contentDescription = null,
                                    imageLoader = imageLoader,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    text = item.pages.joinToString("·"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.55f),
                                )
                            }
                        }
                    }
                }
                Slider(
                    value = page.toFloat(),
                    onValueChange = { page = it.roundToInt().coerceIn(1, pageCount) },
                    valueRange = 1f..pageCount.toFloat(),
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
                        pageCount = pageCount,
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
                page = it.coerceIn(1, pageCount)
                showToc = false
            },
            onDismiss = { showToc = false },
        )
    }
    if (showPageJump) {
        ReaderPageJumpSheet(
            currentPage = page,
            pageCount = pageCount,
            onJump = {
                page = it.coerceIn(1, pageCount)
                showPageJump = false
            },
            onDismiss = { showPageJump = false },
        )
    }
}

/** 单个展示单元：单页 / 双页合幅 / 跨页整幅 / 跨页半边；有真实 URL 用 AsyncImage，否则占位。 */
@Composable
private fun ViewerPage(
    item: ReaderViewItem,
    imageLoader: ImageLoader,
    pageUrl: (Int) -> String?,
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
            )

            is ReaderViewItem.Dual -> Row(
                modifier = Modifier.fillMaxWidth(0.96f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PageImage(
                    page = item.first,
                    url = pageUrl(item.first.number),
                    imageLoader = imageLoader,
                    modifier = Modifier.weight(1f),
                )
                PageImage(
                    page = item.second,
                    url = pageUrl(item.second.number),
                    imageLoader = imageLoader,
                    modifier = Modifier.weight(1f),
                )
            }

            is ReaderViewItem.Spread -> PageImage(
                page = item.page,
                url = pageUrl(item.page.number),
                imageLoader = imageLoader,
                wide = true,
            )

            is ReaderViewItem.Half -> PageImage(
                page = item.page,
                url = pageUrl(item.page.number),
                imageLoader = imageLoader,
                half = if (item.secondHalf) 1 else 0,
            )
        }
    }
}

/** 页面图片：ContentScale.Fit 黑底；加载中占位页码，失败可重试；half 用 Box 裁剪左右半幅。 */
@Composable
private fun PageImage(
    page: ReaderPage,
    url: String?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    half: Int? = null,
) {
    val rawRatio = page.width.toFloat() / page.height.toFloat()
    val fullRatio = if (rawRatio.isFinite() && rawRatio > 0f) rawRatio else 0.7f
    if (url == null) {
        PagePlaceholder(
            label = "第 ${page.number} 页",
            ratio = fullRatio,
            modifier = modifier,
            wide = wide,
            half = half,
        )
        return
    }

    if (half != null) {
        // 半页：外层裁成半幅比例，内层按整幅比例对齐左/右边缘
        Box(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .aspectRatio(fullRatio / 2f)
                .clip(RoundedCornerShape(2.dp))
                .background(PageFill),
        ) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = "第 ${page.number} 页",
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(fullRatio)
                    .align(if (half == 1) Alignment.CenterEnd else Alignment.CenterStart),
            ) {
                PageImageStates(pageNumber = page.number)
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth(if (wide) 1f else 0.92f)
            .aspectRatio(fullRatio)
            .clip(RoundedCornerShape(2.dp))
            .background(PageFill),
    ) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = "第 ${page.number} 页",
            imageLoader = imageLoader,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        ) {
            PageImageStates(pageNumber = page.number)
        }
    }
}

/** SubcomposeAsyncImage 的加载/失败/成功三态（coil3 state 是 StateFlow，需 collect）。 */
@Composable
private fun coil3.compose.SubcomposeAsyncImageScope.PageImageStates(pageNumber: Int) {
    val state by painter.state.collectAsState()
    when (state) {
        is AsyncImagePainter.State.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "第 $pageNumber 页",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF6E6E74),
                )
            }
        }

        is AsyncImagePainter.State.Error -> {
            val retryPainter = painter
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "第 $pageNumber 页加载失败",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF6E6E74),
                    )
                    TextButton(onClick = { retryPainter.restart() }) {
                        Text("重试", color = Color.White)
                    }
                }
            }
        }

        else -> SubcomposeAsyncImageContent()
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
