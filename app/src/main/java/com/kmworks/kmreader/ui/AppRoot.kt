package com.kmworks.kmreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.CollectionDetail
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.ReadListDetail
import com.kmworks.kmreader.data.SampleListDetailData
import com.kmworks.kmreader.data.SmartListDetail
import com.kmworks.kmreader.data.SmartListTarget
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.browse.BrowseScreen
import com.kmworks.kmreader.ui.components.AppTab
import com.kmworks.kmreader.ui.dashboard.DashboardScreen
import com.kmworks.kmreader.ui.dashboard.DashboardSectionDetailScreen
import com.kmworks.kmreader.ui.dashboard.SettingsDashboardScreen
import com.kmworks.kmreader.ui.detail.BookDetailScreen
import com.kmworks.kmreader.ui.detail.SeriesDetailScreen
import com.kmworks.kmreader.ui.library.LibraryScreen
import com.kmworks.kmreader.ui.lists.CollectionDetailScreen
import com.kmworks.kmreader.ui.lists.ListsScreen
import com.kmworks.kmreader.ui.lists.ReadListDetailScreen
import com.kmworks.kmreader.ui.lists.SmartListDetailScreen
import com.kmworks.kmreader.ui.login.LoginScreen
import com.kmworks.kmreader.ui.offline.OfflineScreen
import com.kmworks.kmreader.ui.reader.CustomFontsScreen
import com.kmworks.kmreader.ui.reader.EpubReaderScreen
import com.kmworks.kmreader.ui.reader.PdfReaderScreen
import com.kmworks.kmreader.ui.reader.ReaderScreen
import com.kmworks.kmreader.ui.settings.AboutScreen
import com.kmworks.kmreader.ui.settings.AccountSettingsScreen
import com.kmworks.kmreader.ui.settings.AppearanceSettingsScreen
import com.kmworks.kmreader.ui.settings.BrowseSettingsScreen
import com.kmworks.kmreader.ui.settings.CacheSettingsScreen
import com.kmworks.kmreader.ui.settings.LogsScreen
import com.kmworks.kmreader.ui.settings.NetworkSettingsScreen
import com.kmworks.kmreader.ui.settings.OpenSourceLicensesScreen
import com.kmworks.kmreader.ui.settings.ReaderDefaultsScreen
import com.kmworks.kmreader.ui.settings.SettingsScreen
import com.kmworks.kmreader.ui.settings.SseSettingsScreen
import com.kmworks.kmreader.ui.stats.ReadingStatsScreen
import kotlinx.coroutines.launch

/** 设置子页路由（对齐 SettingsView 的各分组入口）。 */
private enum class SettingsPage {
    Appearance, Browse, Dashboard, ReaderDefaults, Stats,
    CustomFonts, Cache, Network, Sse, Logs, Account, About, Licenses,
}

/** 阅读器目标：format 为 null 表示正在按服务器 mediaType 解析（解析完成前显示加载态）。 */
private data class ReaderTarget(
    val book: Book,
    val format: String?,
    val incognito: Boolean,
)

/**
 * 应用根（设计稿）：NavigationSuiteScaffold 自动完成
 * 手机=底部导航栏 / 平板=侧边导航栏 的容器翻译；
 * 详情、设置、阅读器作为全屏层叠在标签骨架之上（叠加顺序 = 视觉层级）。
 */
@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun AppRoot(
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    // 会话门控：未配置实例时只渲染登录页
    val hasSession by rootViewModel.hasSession.collectAsStateWithLifecycle()
    if (!hasSession) {
        LoginScreen()
        return
    }

    var tab by remember { mutableStateOf(AppTab.Home) }
    var detail by remember { mutableStateOf<Series?>(null) }
    var bookDetail by remember { mutableStateOf<Book?>(null) }
    var collectionDetailId by remember { mutableStateOf<Int?>(null) }
    var readListDetailId by remember { mutableStateOf<Int?>(null) }
    var smartListDetailId by remember { mutableStateOf<Int?>(null) }
    var dashboardSection by remember { mutableStateOf<DashboardSection<out Any>?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    var readerTarget by remember { mutableStateOf<ReaderTarget?>(null) }
    // 详情页元数据 chip 跳转的目标筛选（出版社/作者/分类/标签/知名实体），落在书库浏览页
    var browseFilter by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val openSeries: (Series) -> Unit = { detail = it }
    val openBook: (Book) -> Unit = { bookDetail = it }
    // 书籍上下文菜单「访问系列」：按注册表反查所属系列（空壳 Series，详情 VM 加载真实数据）
    val visitSeriesOfBook: (Book) -> Unit = { book ->
        scope.launch {
            rootViewModel.seriesStableIdForBook(book.id)?.let { seriesId ->
                detail = Series(
                    id = seriesId,
                    title = "",
                    author = "",
                    hue = 0f,
                    bookCount = 0,
                    unreadCount = 0,
                    progress = null,
                )
            }
        }
    }
    val openReader: (Book, Boolean) -> Unit = { book, incognito ->
        readerTarget = ReaderTarget(book = book, format = null, incognito = incognito)
        scope.launch {
            val format = rootViewModel.resolveBookFormat(book.id) ?: "CBZ"
            readerTarget = readerTarget
                ?.takeIf { it.book.id == book.id && it.format == null }
                ?.copy(format = format)
        }
    }

    // 返回手势：按视觉层级逆序逐层收起叠加层；无叠加层时交还系统（退出）
    val hasOverlay = readerTarget != null || settingsPage != null || showSettings ||
        dashboardSection != null || smartListDetailId != null || readListDetailId != null ||
        collectionDetailId != null || bookDetail != null || detail != null
    BackHandler(enabled = hasOverlay) {
        when {
            readerTarget != null -> readerTarget = null
            settingsPage != null -> settingsPage = null
            showSettings -> showSettings = false
            dashboardSection != null -> dashboardSection = null
            smartListDetailId != null -> smartListDetailId = null
            readListDetailId != null -> readListDetailId = null
            collectionDetailId != null -> collectionDetailId = null
            bookDetail != null -> bookDetail = null
            detail != null -> detail = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                AppTab.entries.forEach { item ->
                    item(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            },
        ) {
            when (tab) {
                AppTab.Home -> DashboardScreen(
                    onOpenSeries = openSeries,
                    onOpenSettings = { showSettings = true },
                    onOpenBook = openBook,
                    onOpenSectionDetail = { dashboardSection = it },
                    onOpenContinuation = { readListDetailId = it.readListId },
                    onVisitSeries = visitSeriesOfBook,
                )

                AppTab.Library -> LibraryScreen(
                    activeFilter = browseFilter,
                    onClearFilter = { browseFilter = null },
                    onOpenSeries = openSeries,
                    onOpenBook = openBook,
                    onVisitSeries = visitSeriesOfBook,
                )

                AppTab.Lists -> ListsScreen(
                    onOpenCollection = { collectionDetailId = it },
                    onOpenReadList = { readListDetailId = it },
                    onOpenSmartList = { smartListDetailId = it },
                )

                AppTab.Offline -> OfflineScreen()

                AppTab.Browse -> BrowseScreen(
                    onOpenSeries = openSeries,
                    onOpenBook = openBook,
                    onOpenReadList = { readListDetailId = it },
                    onOpenCollection = { collectionDetailId = it },
                )
            }
        }

        // 全屏叠加层：详情 → 列表详情 → 分区全页 → 设置 → 阅读器（后者视觉层级最高）

        detail?.let { series ->
            SeriesDetailScreen(
                series = series,
                onBack = { detail = null },
                onRead = { book -> openReader(book, false) },
                onBrowseFilter = { filter ->
                    browseFilter = filter
                    detail = null
                    tab = AppTab.Library
                },
                onOpenBook = openBook,
                onOpenCollection = { collectionDetailId = it },
            )
        }

        bookDetail?.let { book ->
            BookDetailScreen(
                book = book,
                seriesTitle = book.seriesTitle,
                onBack = { bookDetail = null },
                onRead = { openReader(book, false) },
                onReadIncognito = { openReader(book, true) },
                onOpenReadList = {
                    // 阅读列表详情叠加在书籍详情之上，返回回到书籍详情
                    readListDetailId = it
                },
                onBrowseFilter = { filter ->
                    // 与系列详情一致：作者/标签 chip 跳到书库浏览页并携带筛选
                    browseFilter = filter
                    bookDetail = null
                    tab = AppTab.Library
                },
                onOpenSeries = {
                    scope.launch {
                        rootViewModel.seriesStableIdForBook(book.id)?.let { seriesId ->
                            bookDetail = null
                            detail = Series(
                                id = seriesId,
                                title = "",
                                author = "",
                                hue = 0f,
                                bookCount = 0,
                                unreadCount = 0,
                                progress = null,
                            )
                        }
                    }
                },
            )
        }

        collectionDetailId?.let { id ->
            // 样本查不到时构造空壳，由 ViewModel 按 DetailIdRegistry 反查加载真实数据
            val collection = SampleListDetailData.collectionById(id)
                ?: CollectionDetail(id = id, name = "", summary = null, ordered = false, seriesIds = emptyList())
            CollectionDetailScreen(
                collection = collection,
                onBack = { collectionDetailId = null },
                onOpenSeries = {
                    collectionDetailId = null
                    detail = it
                },
            )
        }

        readListDetailId?.let { id ->
            val readList = SampleListDetailData.readListById(id)
                ?: ReadListDetail(id = id, name = "", summary = null, ordered = true, bookIds = emptyList())
            ReadListDetailScreen(
                readList = readList,
                onBack = { readListDetailId = null },
                onRead = {
                    readListDetailId = null
                    bookDetail = it
                },
                onOpenSeries = { seriesStableId ->
                    readListDetailId = null
                    detail = Series(
                        id = seriesStableId,
                        title = "",
                        author = "",
                        hue = 0f,
                        bookCount = 0,
                        unreadCount = 0,
                        progress = null,
                    )
                },
            )
        }

        smartListDetailId?.let { id ->
            // 真实智能列表的 stableId 不在样本里：构造空壳，由 ViewModel 按 DetailIdRegistry 反查加载
            val smartList = SampleListDetailData.smartListById(id)
                ?: SmartListDetail(
                    id = id,
                    name = "",
                    summary = null,
                    target = SmartListTarget.Book,
                    modifiedLabel = "",
                    bookIds = emptyList(),
                    seriesIds = emptyList(),
                )
            SmartListDetailScreen(
                smartList = smartList,
                onBack = { smartListDetailId = null },
                onOpenBook = {
                    smartListDetailId = null
                    bookDetail = it
                },
                onOpenSeries = {
                    smartListDetailId = null
                    detail = it
                },
            )
        }

        dashboardSection?.let { section ->
            DashboardSectionDetailScreen(
                section = section,
                onBack = { dashboardSection = null },
                onOpenSeries = {
                    dashboardSection = null
                    detail = it
                },
                onOpenBook = {
                    dashboardSection = null
                    bookDetail = it
                },
            )
        }

        if (showSettings) {
            when (settingsPage) {
                null -> SettingsScreen(
                    onBack = { showSettings = false },
                    onOpenAppearance = { settingsPage = SettingsPage.Appearance },
                    onOpenBrowse = { settingsPage = SettingsPage.Browse },
                    onOpenDashboard = { settingsPage = SettingsPage.Dashboard },
                    onOpenReaderDefaults = { settingsPage = SettingsPage.ReaderDefaults },
                    onOpenStats = { settingsPage = SettingsPage.Stats },
                    onOpenCustomFonts = { settingsPage = SettingsPage.CustomFonts },
                    onOpenCache = { settingsPage = SettingsPage.Cache },
                    onOpenNetwork = { settingsPage = SettingsPage.Network },
                    onOpenSse = { settingsPage = SettingsPage.Sse },
                    onOpenLogs = { settingsPage = SettingsPage.Logs },
                    onOpenAccount = { settingsPage = SettingsPage.Account },
                    onOpenAbout = { settingsPage = SettingsPage.About },
                    onOpenLicenses = { settingsPage = SettingsPage.Licenses },
                )

                SettingsPage.Appearance -> AppearanceSettingsScreen(onBack = { settingsPage = null })
                SettingsPage.Browse -> BrowseSettingsScreen(onBack = { settingsPage = null })
                SettingsPage.Dashboard -> SettingsDashboardScreen(onBack = { settingsPage = null })
                SettingsPage.ReaderDefaults -> ReaderDefaultsScreen(onBack = { settingsPage = null })
                SettingsPage.Stats -> ReadingStatsScreen(onBack = { settingsPage = null })
                SettingsPage.CustomFonts -> CustomFontsScreen(onBack = { settingsPage = null })
                SettingsPage.Cache -> CacheSettingsScreen(onBack = { settingsPage = null })
                SettingsPage.Network -> NetworkSettingsScreen(onBack = { settingsPage = null })
                SettingsPage.Sse -> SseSettingsScreen(onBack = { settingsPage = null })
                SettingsPage.Logs -> LogsScreen(onBack = { settingsPage = null })
                SettingsPage.Account -> AccountSettingsScreen(onBack = { settingsPage = null })
                SettingsPage.About -> AboutScreen(onBack = { settingsPage = null })
                SettingsPage.Licenses -> OpenSourceLicensesScreen(onBack = { settingsPage = null })
            }
        }

        // 阅读器按真实格式路由（服务器 mediaType）：CBZ/CBR → Divina，EPUB → EPUB，PDF → PDF
        readerTarget?.let { target ->
            when (target.format) {
                null -> Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                "EPUB" -> EpubReaderScreen(
                    book = target.book,
                    onBack = { readerTarget = null },
                    incognito = target.incognito,
                )

                "PDF" -> PdfReaderScreen(
                    book = target.book,
                    onBack = { readerTarget = null },
                    incognito = target.incognito,
                )

                else -> ReaderScreen(
                    book = target.book,
                    onBack = { readerTarget = null },
                    incognito = target.incognito,
                )
            }
        }
    }
}
