package com.kmworks.kmreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.SampleListDetailData
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

/** 设置子页路由（对齐 SettingsView 的各分组入口）。 */
private enum class SettingsPage {
    Appearance, Browse, Dashboard, ReaderDefaults, Stats,
    CustomFonts, Cache, Network, Sse, Logs, Account, About, Licenses,
}

/**
 * 应用根（设计稿）：NavigationSuiteScaffold 自动完成
 * 手机=底部导航栏 / 平板=侧边导航栏 的容器翻译；
 * 详情、设置、阅读器作为全屏层叠在标签骨架之上（叠加顺序 = 视觉层级）。
 */
@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun AppRoot() {
    var showLogin by remember { mutableStateOf(false) }
    if (showLogin) {
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
    var reading by remember { mutableStateOf<Book?>(null) }
    // 详情页元数据 chip 跳转的目标筛选（出版社/作者/分类/标签/知名实体），落在书库浏览页
    var browseFilter by remember { mutableStateOf<String?>(null) }

    val openSeries: (Series) -> Unit = { detail = it }
    val openBook: (Book) -> Unit = { bookDetail = it }
    val openReader: (Book) -> Unit = { reading = it }

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
                )

                AppTab.Library -> LibraryScreen(
                    activeFilter = browseFilter,
                    onClearFilter = { browseFilter = null },
                    onOpenSeries = openSeries,
                    onOpenBook = openBook,
                )

                AppTab.Lists -> ListsScreen(
                    onOpenCollection = { collectionDetailId = it },
                    onOpenReadList = { readListDetailId = it },
                    onOpenSmartList = { smartListDetailId = it },
                )

                AppTab.Offline -> OfflineScreen()

                AppTab.Browse -> BrowseScreen(onOpenSeries = openSeries)
            }
        }

        // 全屏叠加层：详情 → 列表详情 → 分区全页 → 设置 → 阅读器（后者视觉层级最高）

        detail?.let { series ->
            SeriesDetailScreen(
                series = series,
                onBack = { detail = null },
                onRead = openReader,
                onBrowseFilter = { filter ->
                    browseFilter = filter
                    detail = null
                    tab = AppTab.Library
                },
                onOpenBook = openBook,
            )
        }

        bookDetail?.let { book ->
            BookDetailScreen(
                book = book,
                seriesTitle = book.seriesTitle,
                onBack = { bookDetail = null },
                onRead = openReader,
                onOpenSeries = {
                    bookDetail = null
                    detail = SampleData.seriesById(book.id / 1000)
                },
            )
        }

        collectionDetailId?.let { id ->
            SampleListDetailData.collectionById(id)?.let { collection ->
                CollectionDetailScreen(
                    collection = collection,
                    onBack = { collectionDetailId = null },
                    onOpenSeries = {
                        collectionDetailId = null
                        detail = it
                    },
                )
            }
        }

        readListDetailId?.let { id ->
            SampleListDetailData.readListById(id)?.let { readList ->
                ReadListDetailScreen(
                    readList = readList,
                    onBack = { readListDetailId = null },
                    onRead = {
                        readListDetailId = null
                        bookDetail = it
                    },
                )
            }
        }

        smartListDetailId?.let { id ->
            SampleListDetailData.smartListById(id)?.let { smartList ->
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

        // 阅读器按格式路由：CBZ/CBR → Divina（分页/条漫），EPUB → EPUB，PDF → PDF
        reading?.let { book ->
            when (SampleListDetailData.bookMetaOf(book).format) {
                "EPUB" -> EpubReaderScreen(book = book, onBack = { reading = null })
                "PDF" -> PdfReaderScreen(book = book, onBack = { reading = null })
                else -> ReaderScreen(book = book, onBack = { reading = null })
            }
        }
    }
}
