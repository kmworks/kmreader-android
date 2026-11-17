package com.kmworks.kmreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.CollectionDetail
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.ReadListDetail
import com.kmworks.kmreader.data.SmartListDetail
import com.kmworks.kmreader.data.SmartListTarget
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.browse.BrowseScreen
import com.kmworks.kmreader.ui.components.AppTab
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
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
import com.kmworks.kmreader.ui.settings.ServerListScreen
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

/** Settings sub-page routes (mirrors the section entries of SettingsView). */
private enum class SettingsPage {
    Appearance, Browse, Dashboard, ReaderDefaults, Stats,
    CustomFonts, Cache, Network, Sse, Logs, Account, About, Licenses,
}

/** Reader target: a null format means the server mediaType is still being resolved (a loading state is shown until it resolves). */
private data class ReaderTarget(
    val book: Book,
    val format: String?,
    val incognito: Boolean,
)

/**
 * App root (design spec): phone = standard bottom navigation bar / tablet (>=840dp) = bottom-center
 * floating capsule navigation bar; detail, settings and reader stack as full-screen layers above the
 * tab skeleton (stacking order = visual hierarchy).
 */
@Composable
fun AppRoot(
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    // Session gating: render only the login page while no instance is configured
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
    // Detail-page metadata chip targets (publisher/author/genre/tag/known entity), landing on the library browse tab
    var browseFilter by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    // Oneshot series click -> single-book detail (mirrors iOS NavDestination.oneshotDetail); fall back to regular series detail when the lookup fails
    val openSeries: (Series) -> Unit = { series ->
        if (series.oneshot) {
            scope.launch {
                val book = rootViewModel.oneshotBookForSeries(series.id)
                if (book != null) bookDetail = book else detail = series
            }
        } else {
            detail = series
        }
    }
    val openBook: (Book) -> Unit = { bookDetail = it }
    // Book context menu "Visit Series": resolve the owning series from the registry (an empty-shell Series; the detail VM loads the real data)
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

    // Back gesture: collapse overlays in reverse visual order; hand back to the system (exit) when none remain
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Tablet (minimum width >=600dp, equivalent to sw600dp and orientation-independent): bottom-center floating capsule nav bar;
        // phones keep the standard bottom navigation. (Custom design requested by the user; iOS iPad uses a top sidebarAdaptable.)
        val config = LocalConfiguration.current
        val floatingNav = minOf(config.screenWidthDp, config.screenHeightDp) >= 600
        CompositionLocalProvider(LocalNavOverlaySpace provides if (floatingNav) 96.dp else 0.dp) {
        Scaffold(
            // Sub-pages bring their own TopAppBar for status-bar insets, so no safe-area insets are injected here (otherwise the top would be padded twice)
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!floatingNav) {
                    NavigationBar {
                        AppTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                                label = { Text(stringResource(item.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
            when (tab) {
                AppTab.Home -> DashboardScreen(
                    onOpenSeries = openSeries,
                    onOpenSettings = { showSettings = true },
                    onOpenBook = openBook,
                    onOpenSectionDetail = { dashboardSection = it },
                    onOpenContinuation = { readListDetailId = it.readListId },
                    onVisitSeries = visitSeriesOfBook,
                    onReadBook = openReader,
                )

                AppTab.Library -> LibraryScreen(
                    activeFilter = browseFilter,
                    onClearFilter = { browseFilter = null },
                    onOpenSeries = openSeries,
                    onOpenBook = openBook,
                    onVisitSeries = visitSeriesOfBook,
                    onReadBook = openReader,
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
                    onReadBook = openReader,
                )
            }
            }
        }
        }

        // Tablet: overlay floating navigation (content scrolls under the translucent bar; list bottom space is reserved by LocalNavOverlaySpace)
        if (floatingNav) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                FloatingNavBar(selected = tab, onSelect = { tab = it })
            }
        }

        // Full-screen overlays: detail -> list detail -> section full page -> settings -> reader (the last has the highest visual layer)

        detail?.let { series ->
            // Isolate Screen-level remember state per entry key (browse options/multi-select/sheet toggles do not leak into the next entry)
            key(series.id) {
            SeriesDetailScreen(
                series = series,
                onBack = { detail = null },
                onRead = { book -> openReader(book, false) },
                onReadIncognito = { book -> openReader(book, true) },
                            onDeleted = { detail = null },
                onBrowseFilter = { filter ->
                    browseFilter = filter
                    detail = null
                    tab = AppTab.Library
                },
                onOpenBook = openBook,
                onOpenCollection = { collectionDetailId = it },
            )
            }
        }

        bookDetail?.let { book ->
            key(book.id) {
            BookDetailScreen(
                book = book,
                // Oneshot detail shape (mirrors OneShotDetailContentView): no series row, no volume number
                seriesTitle = if (book.oneshot) null else book.seriesTitle,
                onBack = { bookDetail = null },
                onRead = { openReader(book, false) },
                onReadIncognito = { openReader(book, true) },
                onOpenReadList = {
                    // Read list detail stacks above book detail; back returns to book detail
                    readListDetailId = it
                },
                onBrowseFilter = { filter ->
                    // Same as series detail: author/tag chips jump to the library browse tab with the filter
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
        }

        collectionDetailId?.let { id ->
            // Fall back to an empty shell when the sample lookup misses; the ViewModel resolves the real data via DetailIdRegistry
            val collection = CollectionDetail(id = id, name = "", summary = null, ordered = false, seriesIds = emptyList())
            key(id) {
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
            val readList = ReadListDetail(id = id, name = "", summary = null, ordered = true, bookIds = emptyList())
            key(id) {
            ReadListDetailScreen(
                readList = readList,
                onBack = { readListDetailId = null },
                // Mirrors the original app: tapping a book reads it directly (details go through the "View Details" menu item)
                onRead = { openReader(it, false) },
                onReadIncognito = { openReader(it, true) },
                onOpenBook = {
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
        }

        smartListDetailId?.let { id ->
            // The stableId of a real smart list is not in the samples: build an empty shell and let the ViewModel resolve it via DetailIdRegistry
            val smartList = SmartListDetail(
                    id = id,
                    name = "",
                    summary = null,
                    target = SmartListTarget.Book,
                    modifiedLabel = "",
                    bookIds = emptyList(),
                    seriesIds = emptyList(),
                )
            key(id) {
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
                onRead = { openReader(it, false) },
                onReadIncognito = { openReader(it, true) },
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
                onReadBook = { book, incognito -> openReader(book, incognito) },
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
                SettingsPage.ReaderDefaults -> ReaderDefaultsScreen(
                    onBack = { settingsPage = null },
                    onOpenCustomFonts = { settingsPage = SettingsPage.CustomFonts },
                )
                SettingsPage.Stats -> ReadingStatsScreen(onBack = { settingsPage = null })
                SettingsPage.CustomFonts -> CustomFontsScreen(onBack = { settingsPage = null })
                SettingsPage.Cache -> CacheSettingsScreen(onBack = { settingsPage = null })
                SettingsPage.Network -> NetworkSettingsScreen(onBack = { settingsPage = null })
                SettingsPage.Sse -> SseSettingsScreen(onBack = { settingsPage = null })
                SettingsPage.Logs -> LogsScreen(onBack = { settingsPage = null })
                SettingsPage.Account -> ServerListScreen(onBack = { settingsPage = null })
                SettingsPage.About -> AboutScreen(onBack = { settingsPage = null })
                SettingsPage.Licenses -> OpenSourceLicensesScreen(onBack = { settingsPage = null })
            }
        }

        // Route the reader by real format (server mediaType): CBZ/CBR -> Divina, EPUB -> EPUB, PDF -> PDF
        readerTarget?.let { target ->
            when (target.format) {
                null -> Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                // Cross-page navigation actions from the in-reader detail sheet: close the reader and open the target page
                // (iOS can push in place inside the reader's NavigationStack; the Android reader is a top-layer overlay)
                "EPUB" -> EpubReaderScreen(
                    book = target.book,
                    onBack = { readerTarget = null },
                    incognito = target.incognito,
                    onVisitSeries = {
                        readerTarget = null
                        visitSeriesOfBook(it)
                    },
                    onOpenReadList = {
                        readerTarget = null
                        readListDetailId = it
                    },
                    onBrowseFilter = { filter ->
                        readerTarget = null
                        browseFilter = filter
                        tab = AppTab.Library
                    },
                )

                "PDF" -> PdfReaderScreen(
                    book = target.book,
                    onBack = { readerTarget = null },
                    incognito = target.incognito,
                    onVisitSeries = {
                        readerTarget = null
                        visitSeriesOfBook(it)
                    },
                    onOpenReadList = {
                        readerTarget = null
                        readListDetailId = it
                    },
                    onBrowseFilter = { filter ->
                        readerTarget = null
                        browseFilter = filter
                        tab = AppTab.Library
                    },
                )

                else -> ReaderScreen(
                    book = target.book,
                    onBack = { readerTarget = null },
                    incognito = target.incognito,
                    onVisitSeries = {
                        readerTarget = null
                        visitSeriesOfBook(it)
                    },
                    onOpenReadList = {
                        readerTarget = null
                        readListDetailId = it
                    },
                    onBrowseFilter = { filter ->
                        readerTarget = null
                        browseFilter = filter
                        tab = AppTab.Library
                    },
                )
            }
        }
    }
}

/**
 * Tablet bottom floating capsule nav bar (referencing mainstream tablet apps: compact item height, a
 * capsule indicator behind the selected icon, a translucent floating container). Unlike the standard
 * NavigationBar: item height is ~60dp instead of 80dp and the width is capped at 480dp.
 */
@Composable
private fun FloatingNavBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        modifier = Modifier.widthIn(max = 400.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { item ->
                val isSelected = selected == item
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "navItemColor",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(item) }
                        .padding(vertical = 2.dp),
                ) {
                    // Selected state: capsule indicator behind the icon (M3 active indicator)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = stringResource(item.labelRes),
                            tint = contentColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = stringResource(item.labelRes),
                        fontSize = 10.sp,
                        color = contentColor,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}
