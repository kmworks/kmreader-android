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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private val ChromeScrim = Color.Black.copy(alpha = 0.82f)
private val PageFill = Color(0xFF1C1C1F)
private val PageLine = Color.White.copy(alpha = 0.12f)
private val ChromeText = Color.White

/**
 * 漫画阅读器（DIVINA）：真实页面管线（ReaderViewModel → BookPagesApi），
 * 设置面板里的每一项都会真正作用到画面上（背景/页码/阴影/翻页动画/点按区域/双击/长按/预加载/裁边）。
 *
 * 行为 1:1 对齐原作 DivinaReaderView / ReaderViewModel：
 * - 初始页：完成或隐私阅读从第 0 页起，否则恢复 readProgress.page（VM 提供 initialPage）
 * - 阅读方向：forceDefaultReadingDirection 时用全局默认，否则用系列方向，否则用全局默认；
 *   方向/布局/拆分宽页/独显封面是【会话内】状态，改动不回写默认（对齐原作 @State + reset 语义）
 * - 双页仅横屏（shouldUseDualPage：宽>高 且 layout!=single 且方向非 vertical/webtoon）；
 *   layout==dual 为强制双页（受同样的横屏门控）
 * - 拆分宽页：none 不切 / auto·ltr·rtl 切前后半 / scroll 保留整幅、点按先在半幅停靠点间平移再翻页
 * - ltr / rtl：横向分页；vertical：纵向分页；webtoon：连续纵向滚动（页宽与点按滚动高度可调）
 * - 手势：点按分区（六种布局 + 镜像）、双击缩放（可关）、捏合缩放平移、长按上下文菜单（分享/独显）
 * - 进度：ReaderProgressRecordingGate（阈值 3 页/完成放行/incognito 跳过），退出时 flush
 * - 预加载：ReaderPreloadProfile 窗口 + 0.3s 节流 + 「仅 Wi-Fi 预加载」门控
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
    val wifiOnlyPreload by settingsViewModel.wifiOnlyPreload.collectAsStateWithLifecycle()
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val readerBackground = settings.background.color(darkTheme)
    val pageContentColor = settings.background.contentColor(darkTheme)

    var chromeVisible by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(1) }
    var pageInitialized by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // 会话内阅读偏好（对齐原作 @State 初始值：从默认值取，改动不回写默认）
    var direction by remember { mutableStateOf<ReadingDirection?>(null) }
    var pageLayout by remember { mutableStateOf<PageLayout?>(null) }
    var splitWideMode by remember { mutableStateOf<SplitWidePageMode?>(null) }
    var soloCoverPage by remember { mutableStateOf<Boolean?>(null) }
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var contextMenuPage by remember { mutableStateOf<ReaderPage?>(null) }

    // 缩放：双击在 1x / 设置倍率之间切换，捏合连续缩放
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoomed = zoom > 1.01f

    // 整幅跨页（scroll 模式）的平移停靠点：0f=首缘（ltr 左/rtl 右），1f=尾缘
    var spreadPan by remember { mutableFloatStateOf(0f) }

    // 阅读时保持屏幕常亮（对齐原作 keepScreenAwakeWhileReading，默认 false）
    val view = LocalView.current
    DisposableEffect(view, settings.keepScreenAwake) {
        view.keepScreenOn = settings.keepScreenAwake
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
    // 退出阅读时 flush 未上报的进度（对齐原作 flushProgress）
    DisposableEffect(Unit) {
        onDispose { viewModel.flushProgress(page) }
    }
    val readerPages = uiState.pages
    val soloPages by viewModel.soloPages.collectAsStateWithLifecycle()
    val pageCount = readerPages.size
    val ready = uiState.status == ReaderPagesStatus.Ready

    // 加载完成后：恢复初始页 + 解析会话内阅读偏好（只执行一次）
    LaunchedEffect(ready) {
        if (!ready || pageInitialized) return@LaunchedEffect
        page = uiState.initialPage ?: 1
        direction = resolveReadingDirection(
            seriesDirection = uiState.seriesDirection,
            forceDefault = settings.forceDefaultDirection,
            defaultDirection = defaultDirection,
        )
        pageLayout = settings.defaultPageLayout
        splitWideMode = settings.defaultSplitWideMode
        soloCoverPage = settings.defaultSoloCoverPage
        pageInitialized = true
    }

    val directionNow = direction ?: defaultDirection
    val pageLayoutNow = pageLayout ?: settings.defaultPageLayout
    val splitWideNow = splitWideMode ?: settings.defaultSplitWideMode
    val soloCoverNow = soloCoverPage ?: settings.defaultSoloCoverPage

    val isWebtoon = directionNow == ReadingDirection.Webtoon
    val isVertical = directionNow == ReadingDirection.Vertical
    val isPaged = !isWebtoon && !isVertical

    // 双页门控（对齐原作 shouldUseDualPage：仅横屏；webtoon/vertical 无双页）
    val configuration = LocalConfiguration.current
    val isLandscapeScreen = configuration.screenWidthDp > configuration.screenHeightDp
    val allowDualPairs = isLandscapeScreen && pageLayoutNow != PageLayout.Single && isPaged
    val forceDualPairs = allowDualPairs && pageLayoutNow == PageLayout.Dual
    val noCover = !(soloCoverNow && allowDualPairs)

    val items = remember(
        readerPages,
        allowDualPairs,
        forceDualPairs,
        splitWideNow,
        soloPages,
        noCover,
    ) {
        ReaderViewItemGenerator.generate(
            pages = readerPages,
            allowDualPairs = allowDualPairs,
            forceDualPairs = forceDualPairs,
            splitWidePages = splitWideNow.isEnabled && !isWebtoon,
            keepsSplitSpreadsWhole = splitWideNow.keepsWholeSpread,
            soloPages = soloPages,
            noCover = noCover,
        )
    }
    val pagerState = rememberPagerState(
        initialPage = 0,
    ) { items.size.coerceAtLeast(1) }
    val webtoonState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 首次进入（含恢复进度）：滚动到初始页所在单元
    LaunchedEffect(pageInitialized, items) {
        if (!pageInitialized) return@LaunchedEffect
        if (isWebtoon) {
            val target = items.indexOfFirst { it.pages.contains(page) }.coerceAtLeast(0)
            if (target > 0) webtoonState.scrollToItem(target)
        } else {
            val target = items.indexOfFirst { it.pages.contains(page) }
            if (target > 0) pagerState.scrollToPage(target)
        }
    }

    // Pager → 页码（分页/纵向模式）
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            items.getOrNull(index)?.pages?.firstOrNull()?.let { page = it }
        }
    }
    // 条漫滚动 → 页码
    LaunchedEffect(webtoonState, isWebtoon, items) {
        if (!isWebtoon) return@LaunchedEffect
        snapshotFlow { webtoonState.firstVisibleItemIndex }.collect { index ->
            items.getOrNull(index)?.pages?.firstOrNull()?.let { if (it != page) page = it }
        }
    }
    // 页码（滑杆/跳页/缩略图）→ 视图
    LaunchedEffect(page, items, isWebtoon) {
        if (!pageInitialized) return@LaunchedEffect
        if (isWebtoon) {
            val target = items.indexOfFirst { it.pages.contains(page) }
            if (target >= 0 && target != webtoonState.firstVisibleItemIndex) webtoonState.scrollToItem(target)
            return@LaunchedEffect
        }
        val target = items.indexOfFirst { it.pages.contains(page) }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    // 切换布局/拆分宽页/方向后落到当前页所在单元（对齐 regenerateViewStatePreservingCurrentPosition）
    LaunchedEffect(allowDualPairs, forceDualPairs, splitWideNow, directionNow, soloPages, noCover) {
        if (!pageInitialized || isWebtoon) return@LaunchedEffect
        val target = items.indexOfFirst { it.pages.contains(page) }
        if (target >= 0) pagerState.scrollToPage(target)
        spreadPan = 0f
    }

    // 预加载窗口（对齐 ReaderPageLoadScheduler：0.3s 节流 + preloadProfile 窗口 + 仅 Wi-Fi 门控）
    LaunchedEffect(pagerState, items, uiState.bookDtoId, imageLoader, settings.preloadProfile, wifiOnlyPreload) {
        snapshotFlow { pagerState.settledPage }
            .debounce(300)
            .collect { index ->
                if (uiState.bookDtoId == null) return@collect
                if (wifiOnlyPreload && !isWifiConnected(context)) return@collect
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
    // 进度上报（分页/纵向）
    LaunchedEffect(pagerState, items, uiState.bookDtoId) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            if (uiState.bookDtoId == null) return@collect
            items.getOrNull(index)?.let { viewModel.reportProgress(it.pages.max()) }
        }
    }
    // 条漫：预加载 + 进度上报
    LaunchedEffect(webtoonState, uiState.bookDtoId, isWebtoon, items, imageLoader, settings.preloadProfile, wifiOnlyPreload) {
        if (!isWebtoon) return@LaunchedEffect
        snapshotFlow { webtoonState.firstVisibleItemIndex }
            .debounce(300)
            .collect { index ->
                if (uiState.bookDtoId == null) return@collect
                items.getOrNull(index)?.let { viewModel.reportProgress(it.pages.max()) }
                if (wifiOnlyPreload && !isWifiConnected(context)) return@collect
                val profile = settings.preloadProfile
                for (i in (index - profile.before)..(index + profile.after)) {
                    items.getOrNull(i)?.pages?.forEach { n ->
                        viewModel.pageUrl(n)?.let { url ->
                            imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                        }
                    }
                }
            }
    }

    // 单点/双击/长按/捏合手势（在同一个 pointerInput 内并行，避免与 Pager 拖动互抢）
    fun stepPage(target: Int) {
        val clamped = target.coerceIn(0, max(0, items.lastIndex))
        scope.launch {
            if (settings.animateTapTurns) pagerState.animateScrollToPage(clamped)
            else pagerState.scrollToPage(clamped)
        }
    }
    // 整幅跨页两阶段点按（对齐原作 requestPagedStep 的 wholeSpread 行为：
    // 未到离开缘先平移到离开缘，再点按才翻页）
    fun tapNext() {
        val item = items.getOrNull(pagerState.currentPage)
        if (splitWideNow.keepsWholeSpread && item is ReaderViewItem.Spread) {
            val departureEdge = if (directionNow == ReadingDirection.Rtl) 0f else 1f
            if (spreadPan != departureEdge) {
                spreadPan = departureEdge
                return
            }
        }
        stepPage(pagerState.currentPage + 1)
    }
    fun tapPrevious() {
        val item = items.getOrNull(pagerState.currentPage)
        if (splitWideNow.keepsWholeSpread && item is ReaderViewItem.Spread) {
            val arrivalEdge = if (directionNow == ReadingDirection.Rtl) 1f else 0f
            if (spreadPan != arrivalEdge) {
                spreadPan = arrivalEdge
                return
            }
        }
        stepPage(pagerState.currentPage - 1)
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
        splitWideNow,
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
                        isRtl = directionNow == ReadingDirection.Rtl,
                    )
                    when (action) {
                        TapZoneAction.Previous -> scope.launch {
                            if (isWebtoon) {
                                webtoonState.animateScrollBy(-viewSize.height * settings.webtoonTapScrollPercentage / 100f)
                            } else {
                                tapPrevious()
                            }
                        }
                        TapZoneAction.Next -> scope.launch {
                            if (isWebtoon) {
                                webtoonState.animateScrollBy(viewSize.height * settings.webtoonTapScrollPercentage / 100f)
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

    // 当前页独显动作（对齐原作 ReaderPageSoloActions：双页展示中可独显竖版页，已独显可取消）
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
                        items = items,
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
                        reverseLayout = directionNow == ReadingDirection.Rtl,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        pageSpacing = if (settings.pageTransition == PageTransitionStyle.Cover) 0.dp else 8.dp,
                    ) { index ->
                        // 覆盖式翻页：后续页固定居中，当前页从上方滑走（对齐 Cover 过渡）
                        val cover = settings.pageTransition == PageTransitionStyle.Cover
                        val item = items[index]
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
                            if (splitWideNow.keepsWholeSpread && item is ReaderViewItem.Spread) {
                                WholeSpreadPage(
                                    page = item.page,
                                    url = viewModel.pageUrl(item.page.number),
                                    imageLoader = imageLoader,
                                    settings = settings,
                                    rotationDegrees = rotationDegrees,
                                    contentColor = pageContentColor,
                                    panFraction = if (index == pagerState.currentPage) spreadPan else 0f,
                                    isRtl = directionNow == ReadingDirection.Rtl,
                                )
                            } else {
                                ViewerPage(
                                    item = item,
                                    imageLoader = imageLoader,
                                    settings = settings,
                                    rotationDegrees = rotationDegrees,
                                    contentColor = pageContentColor,
                                    pageUrl = viewModel::pageUrl,
                                )
                            }
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
                        isRtl = directionNow == ReadingDirection.Rtl,
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
                        direction = directionNow,
                        pageLayout = pageLayoutNow,
                        splitWideMode = splitWideNow,
                        rotationDegrees = rotationDegrees,
                        soloAction = soloAction,
                        soloCoverPage = soloCoverNow,
                        onDirectionChange = { direction = it },
                        onPageLayoutChange = { pageLayout = it },
                        onToggleSoloCover = { soloCoverPage = !soloCoverNow },
                        onSplitWideModeChange = { splitWideMode = it },
                        onRotationChange = { rotationDegrees = it },
                        onOpenSettings = { showSettings = true },
                        onSharePage = {
                            viewModel.pageUrl(page)?.let { url -> sharePageUrl(context, url) }
                        },
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
                    value = page.toFloat().coerceIn(1f, pageCount.coerceAtLeast(1).toFloat()),
                    onValueChange = { page = it.roundToInt().coerceIn(1, pageCount) },
                    valueRange = 1f..pageCount.coerceAtLeast(2).toFloat(),
                    enabled = pageCount > 1,
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
            direction = directionNow,
            onUpdate = settingsViewModel::update,
            onDismiss = { showSettings = false },
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
        val isSolo = current.number in soloPages
        val canSolo = allowDualPairs && !current.isLandscape && !isSolo
        ReaderPageMenuSheet(
            page = current,
            pageUrl = viewModel.pageUrl(current.number),
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

/** 解析会话方向（对齐原作 loadBook 的方向解析：强制默认 → 系列方向 → 全局默认）。 */
private fun resolveReadingDirection(
    seriesDirection: String?,
    forceDefault: Boolean,
    defaultDirection: ReadingDirection,
): ReadingDirection {
    if (forceDefault) return defaultDirection
    val series = seriesDirection?.uppercase()?.let {
        when (it) {
            "LEFT_TO_RIGHT" -> ReadingDirection.Ltr
            "RIGHT_TO_LEFT" -> ReadingDirection.Rtl
            "VERTICAL" -> ReadingDirection.Vertical
            "WEBTOON" -> ReadingDirection.Webtoon
            else -> null
        }
    }
    return series ?: defaultDirection
}

/** 是否 Wi-Fi（或以太网）网络：用于「仅 Wi-Fi 预加载」门控（对齐原作 wifiOnlyPreload）。 */
private fun isWifiConnected(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
}

/** 分享当前页链接（对齐原作菜单的分享动作）。 */
private fun sharePageUrl(context: android.content.Context, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, url)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "分享页面"))
}

/** 条漫：连续纵向滚动，无吸附；页宽与点按滚动高度来自设置。 */
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
) {
    val widthFraction = (pageWidthPercentage / 100f).coerceIn(0.5f, 1f)
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items.size) { index ->
            val item = items[index]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                WebtoonItem(
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

/** 条漫单元（与分页 ViewerPage 同构，但高度由内容驱动）。 */
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

        is ReaderViewItem.Dual -> Row(
            modifier = modifier,
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
            modifier = modifier,
        )

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
                modifier = Modifier.fillMaxSize(),
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
 * 整幅跨页（scroll 拆分模式）：高度充满、宽度溢出，在「首缘→尾缘」两个停靠点间平移
 * （对齐原作 keepsSplitSpreadsWhole 的 wholeSpread：两个停靠点，点按先平移再翻页）。
 * panFraction：0f=首缘（ltr 左/rtl 右），1f=尾缘。
 */
@Composable
private fun WholeSpreadPage(
    page: ReaderPage,
    url: String?,
    imageLoader: ImageLoader,
    settings: DivinaSettings,
    rotationDegrees: Int,
    contentColor: Color,
    panFraction: Float,
    isRtl: Boolean,
) {
    val rawRatio = if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height.toFloat() else 1.4f
    val fullRatio = if (rawRatio.isFinite() && rawRatio > 0f) rawRatio else 1.4f
    val context = LocalContext.current
    val crop = settings.borderCrop
    // 对齐原作 ReaderBackground.appliesImageMultiplyBlend：sepia 背景对页面图片做正片叠底
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
    val animatedPan by animateFloatAsState(panFraction, label = "spreadPan")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (request == null) {
            PagePlaceholder(
                label = "第 ${page.number} 页",
                ratio = fullRatio,
                wide = true,
                contentColor = contentColor,
            )
            return@Box
        }
        BoxWithConstraintsCompat {
            val containerW = maxWidth
            val containerH = maxHeight
            // 图片按高度充满，宽度 = 高度 * 宽高比（通常 > 容器宽，左右溢出）
            val overflowPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                (containerH.toPx() * fullRatio - containerW.toPx()).coerceAtLeast(0f)
            }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = "第 ${page.number} 页（跨页）",
                imageLoader = imageLoader,
                contentScale = ContentScale.FillHeight,
                colorFilter = blendFilter,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(fullRatio)
                    .graphicsLayer {
                        // 图片居中时两侧各溢出 overflow/2；ltr：0=左缘(+overflow/2) 1=右缘(-overflow/2)；rtl 相反
                        translationX = if (isRtl) {
                            (animatedPan - 0.5f) * overflowPx
                        } else {
                            (0.5f - animatedPan) * overflowPx
                        }
                        if (rotationDegrees != 0) rotationZ = rotationDegrees.toFloat()
                    },
            ) {
                PageImageStates(pageNumber = page.number, contentColor = contentColor)
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsCompat(content: @Composable androidx.compose.foundation.layout.BoxWithConstraintsScope.() -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
        content = content,
    )
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
    // 对齐原作 ReaderBackground.appliesImageMultiplyBlend：sepia 背景对页面图片做正片叠底
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

    // 显示比例：半页模式为整页比的一半（对齐原作半页裁切）
    val displayRatio = if (half != null) fullRatio / 2f else fullRatio

    if (url == null || request == null) {
        PagePlaceholder(
            label = "第 ${page.number} 页",
            ratio = displayRatio,
            modifier = modifier.then(rotationModifier),
            half = half,
            contentColor = contentColor,
        )
        return
    }

    // 容器内按比例适配（对齐 iOS scaleAspectFit：竖屏贴宽、横屏贴高，完整显示不裁切）
    FitRatioBox(ratio = displayRatio, modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(shadowModifier)
                .background(PageFill),
        ) {
            if (half != null) {
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = "第 ${page.number} 页",
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Fit,
                    colorFilter = blendFilter,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(fullRatio)
                        .align(if (half == 1) Alignment.CenterEnd else Alignment.CenterStart)
                        .then(rotationModifier),
                ) {
                    PageImageStates(pageNumber = page.number, contentColor = contentColor)
                }
            } else {
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = "第 ${page.number} 页",
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Fit,
                    colorFilter = blendFilter,
                    modifier = Modifier.fillMaxSize().then(rotationModifier),
                ) {
                    PageImageStates(pageNumber = page.number, contentColor = contentColor)
                }
            }
        }
    }
}

/**
 * 在可用空间内放入一个保持 [ratio] 的最大盒子（对齐 iOS scaleAspectFit 的适配语义）：
 * 容器更宽时贴高、更高时贴宽；高度无约束（条漫列表）时由宽度推导高度。
 */
@Composable
private fun FitRatioBox(
    ratio: Float,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val safe = if (ratio.isFinite() && ratio > 0f) ratio else 0.7f
        val sizeModifier = if (
            constraints.maxHeight == Constraints.Infinity ||
            constraints.maxWidth.toFloat() / constraints.maxHeight.toFloat() < safe
        ) {
            Modifier.fillMaxWidth().aspectRatio(safe)
        } else {
            Modifier.fillMaxHeight().aspectRatio(safe)
        }
        Box(modifier = sizeModifier, content = content)
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
                        text = if (half == 0) "前半页" else "后半页",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.35f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
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

/** 长按图片菜单（对齐原作 Image Context Menu：分享 + 双页展示中的单页独显/取消独显）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPageMenuSheet(
    page: ReaderPage,
    pageUrl: String?,
    soloAction: SoloMenuAction?,
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
            if (soloAction != null) {
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
                        if (soloAction.isSolo) "取消独显" else "独显第 ${soloAction.displayPage} 页",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
