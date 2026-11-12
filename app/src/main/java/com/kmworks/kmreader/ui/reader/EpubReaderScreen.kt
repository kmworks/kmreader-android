package com.kmworks.kmreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.epub.EpubChapter
import com.kmworks.kmreader.core.epub.EpubJs
import com.kmworks.kmreader.core.epub.EpubJsBridge
import com.kmworks.kmreader.core.epub.EpubRepository
import com.kmworks.kmreader.core.epub.EpubResource
import com.kmworks.kmreader.core.epub.EpubThemeConfig
import com.kmworks.kmreader.core.epub.EpubWeb
import com.kmworks.kmreader.core.epub.EpubWebViewClient
import com.kmworks.kmreader.core.epub.createEpubWebView
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.SampleSystemData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** EPUB reader UI state. */
data class EpubUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val bookDtoId: String? = null,
    val chapters: List<EpubChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val totalProgression: Float = 0f,
    val scrollToFraction: Float? = null,
)

/**
 * EPUB reader state (mirrors EpubReaderView 的 WebPub manifest 路线)：
 * manifest→章节，positions→章节权重，progression GET 恢复 / PUT 节流上报。
 */
@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val epubRepository: EpubRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EpubUiState(hasSession = sessionManager.clients.value != null),
    )
    val uiState: StateFlow<EpubUiState> = _uiState.asStateFlow()

    private var bookDtoId: String? = null
    private var loadedBookId: Int = -1
    private var weightsByHref: Map<String, Float> = emptyMap()
    private var equalWeight: Float = 1f
    private var currentChapterFraction: Float = 0f
    private var reportJob: Job? = null

    private val deviceId: String = "kmreader-android"
    private val deviceName: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    /** 加载 manifest/positions/已存进度。 */
    fun load(book: Book) {
        if (loadedBookId == book.id) return
        loadedBookId = book.id
        if (sessionManager.clients.value == null) return
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val dtoId = DetailIdRegistry.bookDtoId(book.id)
            if (dtoId == null) {
                _uiState.value = _uiState.value.copy(loading = false, error = "无法定位书籍")
                return@launch
            }
            bookDtoId = dtoId
            val order = epubRepository.manifest(dtoId)?.readingOrder.orEmpty()
                .filter { !it.href.isNullOrBlank() }
            if (order.isEmpty()) {
                _uiState.value = _uiState.value.copy(loading = false, error = "无法加载目录")
                return@launch
            }
            val chapters = order.mapIndexed { index, link ->
                EpubChapter(
                    index = index,
                    href = link.href.orEmpty(),
                    title = link.title?.takeIf { it.isNotBlank() } ?: "第 ${index + 1} 章",
                )
            }
            equalWeight = 1f / chapters.size
            val positions = epubRepository.positions(dtoId)
            weightsByHref = positions
                ?.takeIf { it.total > 0 }
                ?.positions
                ?.groupingBy { it.href }
                ?.eachCount()
                ?.mapValues { it.value.toFloat() / positions.total }
                .orEmpty()

            // Restore saved progression (mirrors decodeRemoteWebPubProgression fallback to start)
            val saved = epubRepository.savedProgression(dtoId)
            var startIndex = 0
            var startFraction: Float? = null
            if (saved != null) {
                val index = chapters.indexOfFirst { it.href == saved.locator.href }
                if (index >= 0) {
                    startIndex = index
                    startFraction = saved.locator.locations?.progression
                }
            }
            currentChapterFraction = startFraction ?: 0f
            _uiState.value = _uiState.value.copy(
                loading = false,
                bookDtoId = dtoId,
                chapters = chapters,
                currentChapterIndex = startIndex,
                scrollToFraction = startFraction,
                totalProgression = totalFor(chapters, startIndex, startFraction ?: 0f),
            )
        }
    }

    /** JS 桥滚动量测回调：更新章节内比例与全书比例并节流上报（对齐原作 updateThrottleInterval=2s）。 */
    fun onMetrics(chapterFraction: Float) {
        val state = _uiState.value
        if (state.chapters.isEmpty()) return
        currentChapterFraction = chapterFraction.coerceIn(0f, 1f)
        _uiState.value = state.copy(
            totalProgression = totalFor(state.chapters, state.currentChapterIndex, currentChapterFraction),
        )
        scheduleReport()
    }

    /** 章节跳转（目录）。 */
    fun goToChapter(index: Int) {
        val state = _uiState.value
        if (index !in state.chapters.indices) return
        flushReport()
        selectChapter(index, null)
    }

    /** 全书比例 seek（底栏滑杆）：按章节权重反解章节与章内比例。 */
    fun seekTo(totalFraction: Float) {
        val state = _uiState.value
        if (state.chapters.isEmpty()) return
        flushReport()
        val target = totalFraction.coerceIn(0f, 1f)
        var acc = 0f
        state.chapters.forEachIndexed { index, chapter ->
            val weight = weightOf(chapter)
            if (target <= acc + weight || index == state.chapters.lastIndex) {
                val fraction = if (weight > 0f) {
                    ((target - acc) / weight).coerceIn(0f, 1f)
                } else {
                    0f
                }
                selectChapter(index, fraction)
                return
            }
            acc += weight
        }
    }

    /** 消费一次性的滚动恢复指令。 */
    fun consumeScroll() {
        if (_uiState.value.scrollToFraction != null) {
            _uiState.value = _uiState.value.copy(scrollToFraction = null)
        }
    }

    /** 立即上报当前进度（退出/切章前调用）。 */
    fun flushReport() {
        reportJob?.cancel()
        reportJob = null
        doReport()
    }

    /** 资源拦截转发（WebViewClient 线程同步调用）。 */
    fun fetchResource(href: String): EpubResource? {
        val dtoId = bookDtoId ?: return null
        val base = epubRepository.baseUrl ?: return null
        val url = EpubWeb.resourceRequestUrl(base, dtoId, href)
        val resource = epubRepository.fetchResourceBlocking(url) ?: return null
        return if (resource.mimeType == "application/octet-stream") {
            EpubResource(resource.data, EpubWeb.mediaTypeFor(href), resource.encoding)
        } else {
            resource
        }
    }

    private fun selectChapter(index: Int, scrollFraction: Float?) {
        currentChapterFraction = scrollFraction ?: 0f
        _uiState.value = _uiState.value.copy(
            currentChapterIndex = index,
            scrollToFraction = scrollFraction,
            totalProgression = totalFor(_uiState.value.chapters, index, currentChapterFraction),
        )
    }

    private fun scheduleReport() {
        if (reportJob?.isActive == true) return
        reportJob = viewModelScope.launch {
            delay(2_000)
            doReport()
        }
    }

    private fun doReport() {
        val state = _uiState.value
        val dtoId = bookDtoId ?: return
        val chapter = state.chapters.getOrNull(state.currentChapterIndex) ?: return
        val fraction = currentChapterFraction
        val total = state.totalProgression
        viewModelScope.launch {
            epubRepository.reportProgression(
                bookDtoId = dtoId,
                href = chapter.href,
                chapterFraction = fraction,
                totalProgression = total,
                deviceId = deviceId,
                deviceName = deviceName,
            )
        }
    }

    private fun weightOf(chapter: EpubChapter): Float =
        weightsByHref[chapter.href] ?: equalWeight

    private fun totalFor(chapters: List<EpubChapter>, index: Int, fraction: Float): Float {
        var acc = 0f
        chapters.take(index).forEach { acc += weightOf(it) }
        return (acc + fraction * (chapters.getOrNull(index)?.let { weightOf(it) } ?: 0f))
            .coerceIn(0f, 1f)
    }
}

/** EPUB reader (mirrors EpubReaderView): WebView WebPub pipeline + DIVINA-style chrome, demo fallback. */
@Composable
fun EpubReaderScreen(
    book: Book,
    onBack: () -> Unit,
    viewModel: EpubReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(book.id) { viewModel.load(book) }
    if (state.hasSession) {
        RealEpubReader(book = book, state = state, viewModel = viewModel, onBack = onBack)
    } else {
        DemoEpubReader(book = book, onBack = onBack)
    }
}

/** Real WebView EPUB body. */
@Composable
private fun RealEpubReader(
    book: Book,
    state: EpubUiState,
    viewModel: EpubReaderViewModel,
    onBack: () -> Unit,
) {
    var epubSettings by remember { mutableStateOf(EpubReaderSettings()) }
    val dark = isSystemInDarkTheme()
    val themeConfig = remember(epubSettings, dark) { mapEpubThemeConfig(epubSettings, dark) }
    var latestConfig by remember { mutableStateOf(themeConfig) }
    SideEffect { latestConfig = themeConfig }

    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var pendingScroll by remember { mutableStateOf<Float?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val bridge = remember {
        EpubJsBridge(
            onMetrics = viewModel::onMetrics,
            onTap = { chromeVisible = !chromeVisible },
        )
    }

    LaunchedEffect(state.scrollToFraction) {
        state.scrollToFraction?.let {
            pendingScroll = it
            viewModel.consumeScroll()
        }
    }
    LaunchedEffect(themeConfig) {
        webView?.evaluateJavascript(
            "window.__kmApplyTheme && window.__kmApplyTheme(${themeConfig.toJs()})",
            null,
        )
    }
    LaunchedEffect(state.bookDtoId, state.currentChapterIndex, webView) {
        val dtoId = state.bookDtoId ?: return@LaunchedEffect
        val chapter = state.chapters.getOrNull(state.currentChapterIndex) ?: return@LaunchedEffect
        webView?.loadUrl(EpubWeb.pageUrl(dtoId, chapter.href))
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.flushReport() }
    }
    DisposableEffect(webView) {
        onDispose { webView?.destroy() }
    }

    val pageBackground = remember(themeConfig) {
        Color(android.graphics.Color.parseColor(themeConfig.background))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground),
    ) {
        AndroidView(
            factory = { context ->
                val client = EpubWebViewClient(
                    resolveResource = viewModel::fetchResource,
                    onPageReady = { view ->
                        view.evaluateJavascript(EpubJs.injectScript(latestConfig), null)
                        pendingScroll?.let { fraction ->
                            view.evaluateJavascript("window.__kmScrollToFraction($fraction)", null)
                            pendingScroll = null
                        }
                    },
                )
                createEpubWebView(context, bridge, client).also { webView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }
        state.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }

        // Top bar (mirrors DIVINA chrome)
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
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
                        state.chapters.getOrNull(state.currentChapterIndex)?.title
                            ?: "第 ${book.number} 册 · EPUB",
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
                    EpubReaderOptionsMenu(
                        expanded = menuExpanded,
                        items = listOf(
                            "阅读器设置" to {
                                menuExpanded = false
                                showSettings = true
                            },
                            "目录" to {
                                menuExpanded = false
                                showToc = true
                            },
                        ),
                        onDismiss = { menuExpanded = false },
                    )
                }
            }
        }

        // Bottom bar: total-progress slider + chapter capsule
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val progress = scrub ?: state.totalProgression
                Slider(
                    value = progress,
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        scrub?.let(viewModel::seekTo)
                        scrub = null
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                ChapterCapsule(
                    text = "第 ${state.currentChapterIndex + 1}/${state.chapters.size} 章 · " +
                        "${(progress * 100).toInt()}%",
                    onClick = { showToc = true },
                )
            }
        }
    }

    if (showSettings) {
        EpubSettingsSheet(
            settings = epubSettings,
            onSettingsChange = { epubSettings = it },
            customFonts = SampleSystemData.customFonts,
            onOpenCustomFonts = { /* wiring point: open CustomFontsScreen */ },
            onDismiss = { showSettings = false },
        )
    }
    if (showToc) {
        EpubChapterSheet(
            chapters = state.chapters,
            currentIndex = state.currentChapterIndex,
            onJump = {
                viewModel.goToChapter(it)
                showToc = false
            },
            onDismiss = { showToc = false },
        )
    }
}

/** Demo fallback skeleton (no session). */
@Composable
private fun DemoEpubReader(book: Book, onBack: () -> Unit) {
    val pageCount = book.pageCount.coerceAtLeast(1)
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var epubSettings by remember { mutableStateOf(EpubReaderSettings()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { chromeVisible = !chromeVisible },
    ) {
        // Text page placeholder
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "EPUB 正文占位",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                "第 $currentPage 屏 · ${epubSettings.theme} · ${epubSettings.fontSizeSp.toInt()} sp",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // Top bar (mirrors DIVINA chrome)
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
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
                        "第 ${book.number} 册 · EPUB",
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
                    EpubReaderOptionsMenu(
                        expanded = menuExpanded,
                        items = listOf(
                            "阅读器设置" to {
                                menuExpanded = false
                                showSettings = true
                            },
                            "目录" to {
                                menuExpanded = false
                                showToc = true
                            },
                            "跳页" to {
                                menuExpanded = false
                                showPageJump = true
                            },
                        ),
                        onDismiss = { menuExpanded = false },
                    )
                }
            }
        }

        // Bottom bar: progress slider + page capsule (mirrors DIVINA chrome)
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
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

    if (showSettings) {
        EpubSettingsSheet(
            settings = epubSettings,
            onSettingsChange = { epubSettings = it },
            customFonts = SampleSystemData.customFonts,
            onOpenCustomFonts = { /* wiring point: open CustomFontsScreen */ },
            onDismiss = { showSettings = false },
        )
    }
    if (showToc) {
        ReaderTocSheet(
            currentPage = currentPage,
            onJump = {
                currentPage = it.coerceIn(1, pageCount)
                showToc = false
            },
            onDismiss = { showToc = false },
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

/** Overflow menu for the EPUB chrome. */
@Composable
private fun EpubReaderOptionsMenu(
    expanded: Boolean,
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        items.forEach { (label, action) ->
            DropdownMenuItem(text = { Text(label) }, onClick = action)
        }
    }
}

/** Chapter progress capsule (mirrors PageJumpCapsule styling). */
@Composable
private fun ChapterCapsule(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Manifest chapter sheet (mirrors EpubTocSheetView). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpubChapterSheet(
    chapters: List<EpubChapter>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "目录",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyColumn {
            items(chapters.size) { index ->
                val chapter = chapters[index]
                val current = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(index) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                    )
                    Text(
                        chapter.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (current) FontWeight.Bold else null,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** EpubReaderSettings → WebView 注入配置（主题灰阶对齐全自定主题，字体下载为留口）。 */
private fun mapEpubThemeConfig(settings: EpubReaderSettings, dark: Boolean): EpubThemeConfig {
    val (background, foreground) = when (settings.theme) {
        "安静" -> "#FAFAFA" to "#1E1E1E"
        "米黄" -> "#F0F0F0" to "#2A2A2A"
        "墨绿" -> "#ECECEC" to "#202020"
        else -> if (dark) "#000000" to "#E0E0E0" else "#FFFFFF" to "#1A1A1A"
    }
    val fontFamily = when (settings.typeface) {
        "出版方默认" -> ""
        "系统默认" -> "serif"
        // wiring point: custom font @font-face injection（字体下载留口，按名回退）
        else -> "${settings.typeface}, serif"
    }
    return EpubThemeConfig(
        background = background,
        foreground = foreground,
        fontSizePx = settings.fontSizeSp.toInt().coerceIn(12, 32),
        fontFamily = fontFamily,
        lineHeight = settings.lineHeight,
        marginHPx = (settings.margins * 24f).toInt().coerceIn(8, 64),
        publisherStyles = settings.publisherStyles,
    )
}
