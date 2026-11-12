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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.core.image.BorderCropTransformation
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.ReaderPage
import com.kmworks.kmreader.data.ReaderViewItem
import com.kmworks.kmreader.data.ReaderViewItemGenerator
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val ChromeScrim = Color.Black.copy(alpha = 0.82f)
private val PageFill = Color(0xFF1C1C1F)
private val PageLine = Color.White.copy(alpha = 0.12f)
private val ChromeText = Color.White

/**
 * 漫画阅读器（DIVINA）：真实页面管线（ReaderViewModel → BookPagesApi），
 * 设置面板里的每一项都会真正作用到画面上（背景/页码/阴影/翻页动画/点按区域/双击/长按/预加载/裁边）。
 *
 * 阅读方向与页面单元对齐原作：
 * - ltr / rtl：横向分页，宽图（横版）在「拆分宽页」开启时整幅展示或切前后半，双页模式独占 spread
 * - vertical：纵向分页；webtoon：连续纵向滚动（无吸附），页宽与点按滚动高度可调
 *
 * 手势：单指点按分区（TapZoneHelper 六种布局 + 左右镜像）、双击缩放、双指捏合缩放与平移、
 * 长按图片菜单；全部可在设置面板调整。
 */
@Composable
fun ReaderScreen(
    book: Book,
    onBack: () -> Unit,
    incognito: Boolean = false,
    viewModel: ReaderViewModel = hiltViewModel(),
    settingsViewModel: DivinaSettingsViewModel = hiltViewModel(),
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val defaultDirection by settingsViewModel.defaultDirection.collectAsStateWithLifecycle()
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val readerBackground = settings.background.color(darkTheme)
    val pageContentColor = settings.background.contentColor(darkTheme)

    var chromeVisible by remember { mutableStateOf(true) }
    var page by remember {
        mutableIntStateOf(((book.progress ?: 0f) * book.pageCount).roundToInt().coerceAtLeast(1))
    }
    var menuOpen by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(defaultDirection) }
    var directionTouched by remember { mutableStateOf(false) }
    var dualPage by remember { mutableStateOf(false) }
    var splitWideMode by remember { mutableStateOf(SplitWidePageMode.Auto) }
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var contextMenuPage by remember { mutableStateOf<ReaderPage?>(null) }
    var infoPage by remember { mutableStateOf<ReaderPage?>(null) }

    // 缩放：双击在 1x / 设置倍率之间切换，捏合连续缩放
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoomed = zoom > 1.01f

    LaunchedEffect(defaultDirection) {
        if (!directionTouched) direction = defaultDirection
    }

    // 阅读时保持屏幕常亮（对齐原作阅读器行为）
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ImageLoaderEntryPoint::class.java,
        )
    }
    val sessionLoader by entryPoint.sessionManager().imageLoader.collectAsState()
    val imageLoader = sessionLoader ?: remember { SingletonImageLoader.get(context) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(book, incognito) {
        viewModel.incognito = incognito
        viewModel.load(book)
    }
    val readerPages = uiState.pages
    val pageCount = readerPages.size

    val isWebtoon = direction == ReadingDirection.Webtoon
    val isVertical = direction == ReadingDirection.Vertical
    val isPaged = !isWebtoon && !isVertical

    // 条漫/竖排不支持双页与拆分宽页（对齐原作门控）
    val effectiveDualPage = dualPage && isPaged
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
    val webtoonState = rememberLazyListState(
        initialFirstVisibleItemIndex = (page - 1).coerceIn(0, max(0, readerPages.lastIndex)),
    )
    val scope = rememberCoroutineScope()

    // Pager → 页码（分页/纵向模式）
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            items.getOrNull(index)?.pages?.firstOrNull()?.let { page = it }
        }
    }
    // 条漫滚动 → 页码
    LaunchedEffect(webtoonState, isWebtoon) {
        if (!isWebtoon) return@LaunchedEffect
        snapshotFlow { webtoonState.firstVisibleItemIndex }.collect { index ->
            if (index + 1 != page) page = index + 1
        }
    }
    // 页码（滑杆/跳页/目录/缩略图）→ 视图
    LaunchedEffect(page, items, isWebtoon) {
        if (isWebtoon) {
            val target = (page - 1).coerceIn(0, max(0, readerPages.lastIndex))
            if (target != webtoonState.firstVisibleItemIndex) webtoonState.scrollToItem(target)
            return@LaunchedEffect
        }
        val target = items.indexOfFirst { it.pages.contains(page) }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    // 切换双页/拆分宽页/方向后落到当前页所在单元
    LaunchedEffect(effectiveDualPage, splitWideMode, direction) {
        if (!isPaged) return@LaunchedEffect
        val target = items.indexOfFirst { it.pages.contains(page) }
        if (target >= 0) pagerState.scrollToPage(target)
    }
    // 预加载窗口 + 进度上报（分页/纵向）
    LaunchedEffect(pagerState, items, uiState.bookDtoId, imageLoader, settings.preloadProfile) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            if (uiState.bookDtoId == null) return@collect
            val item = items.getOrNull(index) ?: return@collect
            viewModel.reportProgress(item.pages.max())
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
    // 条漫进度上报
    LaunchedEffect(webtoonState, uiState.bookDtoId, isWebtoon) {
        if (!isWebtoon) return@LaunchedEffect
        snapshotFlow { webtoonState.firstVisibleItemIndex }.collect { index ->
            if (uiState.bookDtoId != null) viewModel.reportProgress(index + 1)
        }
    }

    val targetStep = if (isWebtoon) {
        // 条漫：点按滚动高度百分比（对齐 webtoonTapScrollPercentage）
        1
    } else {
        1
    }

    // 单点/双击/长按/捏合手势（在同一个 pointerInput 内并行，避免与 Pager 拖动互抢）
    fun stepPage(target: Int) {
        val clamped = target.coerceIn(0, max(0, items.lastIndex))
        scope.launch {
            if (settings.animateTapTurns) pagerState.animateScrollToPage(clamped)
            else pagerState.scrollToPage(clamped)
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
        direction,
        items.size,
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
                        isRtl = direction == ReadingDirection.Rtl,
                    )
                    when (action) {
                        TapZoneAction.Previous -> scope.launch {
                            if (isWebtoon) {
                                webtoonState.animateScrollBy(-viewSize.height * settings.webtoonTapScrollPercentage / 100f)
                            } else if (isVertical) {
                                stepPage(pagerState.currentPage - 1)
                            } else {
                                stepPage(pagerState.currentPage - targetStep)
                            }
                        }
                        TapZoneAction.Next -> scope.launch {
                            if (isWebtoon) {
                                webtoonState.animateScrollBy(viewSize.height * settings.webtoonTapScrollPercentage / 100f)
                            } else if (isVertical) {
                                stepPage(pagerState.currentPage + 1)
                            } else {
                                stepPage(pagerState.currentPage + targetStep)
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
                        contextMenuPage = item?.pages?.firstOrNull()?.let { number ->
                            readerPages.firstOrNull { it.number == number }
                        }
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
                    "页面列表加载失败",
                    style = MaterialTheme.typography.titleSmall,
                    color = pageContentColor.copy(alpha = 0.85f),
                )
                TextButton(onClick = { viewModel.retry(book) }) {
                    Text("重试", color = pageContentColor)
                }
            }

            ReaderPagesStatus.Ready -> {
                when {
                    isWebtoon -> WebtoonReader(
                        pages = readerPages,
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
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) { index ->
                        ViewerPage(
                            item = items[index],
                            imageLoader = imageLoader,
                            settings = settings,
                            rotationDegrees = rotationDegrees,
                            contentColor = pageContentColor,
                            pageUrl = viewModel::pageUrl,
                        )
                    }

                    else -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().then(zoomModifier).then(gestureModifier),
                        userScrollEnabled = !zoomed,
                        reverseLayout = direction == ReadingDirection.Rtl,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        pageSpacing = if (settings.pageTransition == PageTransitionStyle.Cover) 0.dp else 8.dp,
                    ) { index ->
                        // 覆盖式翻页：后续页固定居中，当前页从上方滑走（对齐 Cover 过渡）
                        val cover = settings.pageTransition == PageTransitionStyle.Cover
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
                                item = items[index],
                                imageLoader = imageLoader,
                                settings = settings,
                                rotationDegrees = rotationDegrees,
                                contentColor = pageContentColor,
                                pageUrl = viewModel::pageUrl,
                            )
                            if (cover) {
                                // 页面间的阴影让覆盖效果有层次
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(1.dp)
                                        .background(Color.Black.copy(alpha = 0.25f)),
                                )
                            }
                        }
                    }
                }

                // 点按区域提示（对齐原作的 showTapZoneHints）
                if (settings.showTapZoneHints && chromeVisible && !settings.tapZoneMode.isDisabled) {
                    TapZoneHints(
                        mode = settings.tapZoneMode,
                        isRtl = direction == ReadingDirection.Rtl,
                        inversion = settings.tapZoneInversion,
                    )
                }

                // 页码（无控件时显示，避免与底栏重复）
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

                // 阅读进度条（无控件时、可选）
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

        // 顶栏
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (settings.controlsGradient) {
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent),
                            )
                        } else {
                            Brush.verticalGradient(listOf(ChromeScrim, ChromeScrim))
                        },
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "退出阅读",
                        tint = ChromeText,
                    )
                }
                if (incognito) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = "隐私阅读",
                        tint = ChromeText.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (incognito) 8.dp else 0.dp),
                ) {
                    Text(book.seriesTitle, style = MaterialTheme.typography.titleSmall, color = ChromeText)
                    Text(
                        book.number,
                        style = MaterialTheme.typography.bodySmall,
                        color = ChromeText.copy(alpha = 0.7f),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = ChromeText)
                    }
                    ReaderOptionsMenu(
                        expanded = menuOpen,
                        direction = direction,
                        dualPage = dualPage,
                        splitWideMode = splitWideMode,
                        rotationDegrees = rotationDegrees,
                        onDirectionChange = {
                            directionTouched = true
                            direction = it
                            settingsViewModel.setDefaultDirection(it)
                        },
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

        // 底栏：缩略图条 + 滑杆 + 页码胶囊
        AnimatedVisibility(
            visible = chromeVisible && uiState.status == ReaderPagesStatus.Ready,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (settings.controlsGradient) {
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                            )
                        } else {
                            Brush.verticalGradient(listOf(ChromeScrim, ChromeScrim))
                        },
                    ),
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
                                        Modifier.border(2.dp, ChromeText, RoundedCornerShape(4.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable {
                                    if (isWebtoon) {
                                        page = item.pages.first()
                                    } else {
                                        scope.launch { pagerState.scrollToPage(index) }
                                    }
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
                                    color = ChromeText.copy(alpha = 0.55f),
                                )
                            }
                        }
                    }
                }
                Slider(
                    value = page.toFloat(),
                    onValueChange = { page = it.roundToInt().coerceIn(1, pageCount) },
                    valueRange = 1f..pageCount.coerceAtLeast(1).toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = ChromeText,
                        activeTrackColor = ChromeText,
                        inactiveTrackColor = ChromeText.copy(alpha = 0.3f),
                    ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    PageJumpCapsule(page = page, pageCount = pageCount, onClick = { showPageJump = true })
                }
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            direction = direction,
            onUpdate = settingsViewModel::update,
            onDismiss = { showSettings = false },
        )
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
    contextMenuPage?.let { current ->
        ReaderPageMenuSheet(
            page = current,
            pageUrl = viewModel.pageUrl(current.number),
            onOpenInfo = {
                contextMenuPage = null
                infoPage = current
            },
            onDismiss = { contextMenuPage = null },
        )
    }
    infoPage?.let { current ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { infoPage = null },
            confirmButton = {
                TextButton(onClick = { infoPage = null }) { Text("关闭") }
            },
            title = { Text("第 ${current.number} 页") },
            text = {
                Text(
                    "尺寸 ${current.width} × ${current.height}\n" +
                        if (current.width == 0 || current.height == 0) "服务器未提供页面尺寸" else "宽高比 %.2f".format(
                            current.width.toFloat() / current.height.toFloat(),
                        ),
                )
            },
        )
    }
}

/** 条漫：连续纵向滚动，无吸附；页宽与点按滚动高度来自设置。 */
@Composable
private fun WebtoonReader(
    pages: List<ReaderPage>,
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
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(pages.size) { index ->
            val page = pages[index]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                PageImage(
                    page = page,
                    url = pageUrl(page.number),
                    imageLoader = imageLoader,
                    settings = settings,
                    rotationDegrees = rotationDegrees,
                    contentColor = settings.background.contentColor(
                        androidx.compose.foundation.isSystemInDarkTheme(),
                    ),
                    modifier = Modifier.fillMaxWidth(widthFraction),
                )
            }
        }
    }
}

/** 单个展示单元：单页 / 双页合幅 / 跨页整幅 / 跨页半边。 */
@Composable
private fun ViewerPage(
    item: ReaderViewItem,
    imageLoader: ImageLoader,
    settings: DivinaSettings,
    rotationDegrees: Int,
    contentColor: Color,
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
                settings = settings,
                rotationDegrees = rotationDegrees,
                contentColor = contentColor,
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
                    settings = settings,
                    rotationDegrees = rotationDegrees,
                    contentColor = contentColor,
                    modifier = Modifier.weight(1f),
                )
                PageImage(
                    page = item.second,
                    url = pageUrl(item.second.number),
                    imageLoader = imageLoader,
                    settings = settings,
                    rotationDegrees = rotationDegrees,
                    contentColor = contentColor,
                    modifier = Modifier.weight(1f),
                )
            }

            is ReaderViewItem.Spread -> PageImage(
                page = item.page,
                url = pageUrl(item.page.number),
                imageLoader = imageLoader,
                settings = settings,
                rotationDegrees = rotationDegrees,
                contentColor = contentColor,
                wide = true,
            )

            is ReaderViewItem.Half -> PageImage(
                page = item.page,
                url = pageUrl(item.page.number),
                imageLoader = imageLoader,
                settings = settings,
                rotationDegrees = rotationDegrees,
                contentColor = contentColor,
                half = if (item.secondHalf) 1 else 0,
            )
        }
    }
}

/**
 * 页面图片：ContentScale.Fit；背景/阴影/旋转/裁边来自设置。
 * 加载中显示页码占位，失败可重试。
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
    wide: Boolean = false,
    half: Int? = null,
) {
    val rawRatio = if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height.toFloat() else 0.7f
    val fullRatio = if (rawRatio.isFinite() && rawRatio > 0f) rawRatio else 0.7f

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
    val shadowModifier = if (settings.showPageShadow) {
        Modifier.shadow(elevation = 6.dp, shape = RoundedCornerShape(2.dp))
    } else {
        Modifier
    }
    val context = LocalContext.current
    val crop = settings.borderCrop
    val request = remember(context, url, crop) {
        url?.let {
            ImageRequest.Builder(context).data(it).apply {
                if (crop != BorderCropMode.Disabled) {
                    transformations(BorderCropTransformation(crop))
                }
            }.build()
        }
    }

    if (url == null || request == null) {
        PagePlaceholder(
            label = "第 ${page.number} 页",
            ratio = fullRatio,
            modifier = modifier.then(rotationModifier),
            wide = wide,
            half = half,
            contentColor = contentColor,
        )
        return
    }

    if (half != null) {
        Box(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .aspectRatio(fullRatio / 2f)
                .clip(RoundedCornerShape(2.dp))
                .then(shadowModifier)
                .background(PageFill),
        ) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = "第 ${page.number} 页",
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(fullRatio)
                    .align(if (half == 1) Alignment.CenterEnd else Alignment.CenterStart)
                    .then(rotationModifier),
            ) {
                PageImageStates(pageNumber = page.number, contentColor = contentColor)
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth(if (wide) 1f else 0.92f)
            .aspectRatio(fullRatio)
            .clip(RoundedCornerShape(2.dp))
            .then(shadowModifier)
            .background(PageFill),
    ) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = "第 ${page.number} 页",
            imageLoader = imageLoader,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().then(rotationModifier),
        ) {
            PageImageStates(pageNumber = page.number, contentColor = contentColor)
        }
    }
}

/** SubcomposeAsyncImage 的加载/失败/成功三态（coil3 state 是 StateFlow，需 collect）。 */
@Composable
private fun coil3.compose.SubcomposeAsyncImageScope.PageImageStates(pageNumber: Int, contentColor: Color) {
    val state by painter.state.collectAsState()
    when (state) {
        is AsyncImagePainter.State.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "第 $pageNumber 页",
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor.copy(alpha = 0.45f),
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
                        color = contentColor.copy(alpha = 0.45f),
                    )
                    TextButton(onClick = { retryPainter.restart() }) {
                        Text("重试", color = contentColor)
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
    contentColor: Color = Color.White,
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
            Text(text = label, style = MaterialTheme.typography.titleSmall, color = contentColor.copy(alpha = 0.45f))
            if (half != null) {
                Text(
                    text = if (half == 0) "前半页" else "后半页",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.35f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 点按区域提示：淡色分区线与三个主分区的说明。 */
@Composable
private fun TapZoneHints(mode: TapZoneMode, isRtl: Boolean, inversion: TapZoneInversion) {
    Box(modifier = Modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.18f)
        // 横向三分线
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
                    "上一页",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.35f),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    "菜单",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.35f),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    "下一页",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.35f),
                )
            }
        }
    }
}

/** 长按图片菜单：分享页面 / 查看图片信息（对齐原作 Image Context Menu 的入口）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPageMenuSheet(
    page: ReaderPage,
    pageUrl: String?,
    onOpenInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                "第 ${page.number} 页",
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
                            context.startActivity(android.content.Intent.createChooser(intent, "分享页面"))
                        }
                        onDismiss()
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("分享页面", style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenInfo() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("图片信息", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
