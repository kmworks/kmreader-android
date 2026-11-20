package com.kmworks.kmreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.kmworks.kmreader.ui.server.ServerAdminScreen
import kotlinx.coroutines.launch

/** Settings sub-page routes (mirrors the section entries of SettingsView). */
private enum class SettingsPage {
    Appearance, Browse, Dashboard, ReaderDefaults, Stats,
    CustomFonts, Cache, Network, Sse, Logs, Account, Server, About, Licenses,
}

/** Reader target: a null format means the server mediaType is still being resolved (a loading state is shown until it resolves). */
private data class ReaderTarget(
    val book: Book,
    val format: String?,
    val incognito: Boolean,
)

/**
 * One full-screen overlay pushed above the tab skeleton.
 *
 * [depth] is the visual stacking rank (1 sits directly above the tabs, larger is higher) and [key] is the
 * stable identity of the page: two routes with the same key are the same layer, so the page beneath keeps
 * its composition (scroll position, open sheets) while the page above slides in.
 */
private sealed interface Route {
    val key: String
    val depth: Int
}

/** Stacking rank of every overlay kind (mirrors the order of the old hand-written layers). */
private object LayerDepth {
    const val SeriesDetail = 1
    const val BookDetail = 2
    const val CollectionDetail = 3
    const val ReadListDetail = 4
    const val SmartListDetail = 5
    const val DashboardSection = 6
    const val Settings = 7
    const val SettingsPage = 8
    const val Reader = 9
    const val Max = Reader
}

private data class SeriesDetailRoute(val series: Series) : Route {
    override val key: String get() = "series:${series.id}"
    override val depth: Int get() = LayerDepth.SeriesDetail
}

private data class BookDetailRoute(val book: Book) : Route {
    override val key: String get() = "book:${book.id}"
    override val depth: Int get() = LayerDepth.BookDetail
}

private data class CollectionDetailRoute(val id: Int) : Route {
    override val key: String get() = "collection:$id"
    override val depth: Int get() = LayerDepth.CollectionDetail
}

private data class ReadListDetailRoute(val id: Int) : Route {
    override val key: String get() = "readList:$id"
    override val depth: Int get() = LayerDepth.ReadListDetail
}

private data class SmartListDetailRoute(val id: Int) : Route {
    override val key: String get() = "smartList:$id"
    override val depth: Int get() = LayerDepth.SmartListDetail
}

private data class DashboardSectionRoute(val section: DashboardSection<out Any>) : Route {
    override val key: String get() = "dashboardSection:${section.id}"
    override val depth: Int get() = LayerDepth.DashboardSection
}

private data object SettingsRoute : Route {
    override val key: String get() = "settings"
    override val depth: Int get() = LayerDepth.Settings
}

private data class SettingsPageRoute(val page: SettingsPage) : Route {
    override val key: String get() = "settings:${page.name}"
    override val depth: Int get() = LayerDepth.SettingsPage
}

private data class ReaderRoute(val book: Book, val format: String, val incognito: Boolean) : Route {
    override val key: String get() = "reader:${book.id}:$incognito"
    override val depth: Int get() = LayerDepth.Reader
}

/**
 * Bottom space the floating navigation capsule occupies above the navigation-bar inset: item row (icon +
 * label, ~44dp) plus the capsule and outer paddings. Pages reserve it as list content padding so the last
 * row can scroll clear of the bar; the system bar inset is added on top at the call site.
 */
private val NavOverlaySpace = 76.dp

/**
 * App root (design spec): a single bottom-center floating capsule navigation bar on every form factor
 * (content scrolls underneath it); detail, settings and reader stack as full-screen layers above the tab
 * skeleton (stacking order = visual hierarchy).
 */
@Composable
fun AppRoot(
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    // Session gating: render only the login page while no instance is configured. The config is read
    // from disk asynchronously, so the gate resolves asynchronously too: keep that first frame blank
    // (page background only) instead of flashing the login page before the saved session arrives.
    val sessionGate by rootViewModel.sessionGate.collectAsStateWithLifecycle()
    when (sessionGate) {
        SessionGate.Loading -> {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            return
        }
        SessionGate.SignedOut -> {
            LoginScreen()
            return
        }
        SessionGate.SignedIn -> Unit
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
        // Overlay navigation on every form factor: the tab bar floats above the pages, so their content keeps the full
        // height and scrolls underneath it, reserving only LocalNavOverlaySpace at the bottom. Wide layouts just cap the
        // capsule width instead of switching to a different bar shape (no more NavigationBar row eating ~80dp on phones).
        val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        CompositionLocalProvider(LocalNavOverlaySpace provides (NavOverlaySpace + navigationInset)) {
            // The pages no longer own a Scaffold: the root paints the app background behind their edge-to-edge
            // content (status bar strip included), so the frosted top bars have something to sit on.
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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

                // Floating capsule navigation: drawn after the tab pages (so it stays above their content) and before
                // the full-screen overlay stack below (so detail/settings/reader pages still cover it).
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
        }

        // Full-screen overlays, one animated slot per depth: the tab skeleton is depth 0 and every page above
        // it (detail -> book -> list detail -> section -> settings -> reader) owns its own slot, so the pages
        // beneath keep their composition (scroll position, open sheets) while the page above slides in.
        val routeStack: List<Route> = buildList {
            detail?.let { add(SeriesDetailRoute(it)) }
            bookDetail?.let { add(BookDetailRoute(it)) }
            collectionDetailId?.let { add(CollectionDetailRoute(it)) }
            readListDetailId?.let { add(ReadListDetailRoute(it)) }
            smartListDetailId?.let { add(SmartListDetailRoute(it)) }
            dashboardSection?.let { add(DashboardSectionRoute(it)) }
            if (showSettings) add(SettingsRoute)
            settingsPage?.let { add(SettingsPageRoute(it)) }
            val reader = readerTarget
            val readerFormat = reader?.format
            if (reader != null && readerFormat != null) add(ReaderRoute(reader.book, readerFormat, reader.incognito))
        }

        OverlayStack(stack = routeStack) { route ->
            when (route) {
                is SeriesDetailRoute -> {
                    val series = route.series
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

                is BookDetailRoute -> {
                    val book = route.book
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

                is CollectionDetailRoute -> {
                    val id = route.id
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

                is ReadListDetailRoute -> {
                    val id = route.id
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

                is SmartListDetailRoute -> {
                    val id = route.id
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

                is DashboardSectionRoute -> {
                    val section = route.section
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

                is SettingsRoute -> SettingsScreen(
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
                    onOpenServer = { settingsPage = SettingsPage.Server },
                    onOpenAbout = { settingsPage = SettingsPage.About },
                    onOpenLicenses = { settingsPage = SettingsPage.Licenses },
                )

                is SettingsPageRoute -> when (route.page) {
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
                    SettingsPage.Server -> ServerAdminScreen(onBack = { settingsPage = null })
                    SettingsPage.About -> AboutScreen(onBack = { settingsPage = null })
                    SettingsPage.Licenses -> OpenSourceLicensesScreen(onBack = { settingsPage = null })
                }

                // Route the reader by real format (server mediaType): CBZ/CBR -> Divina, EPUB -> EPUB, PDF -> PDF.
                // The route joins the stack only once the format resolved, so the loading cover below never takes
                // part in a transition.
                is ReaderRoute -> when (route.format) {
                    // Cross-page navigation actions from the in-reader detail sheet: close the reader and open the target page
                    // (iOS can push in place inside the reader's NavigationStack; the Android reader is a top-layer overlay)
                    "EPUB" -> EpubReaderScreen(
                        book = route.book,
                        onBack = { readerTarget = null },
                        incognito = route.incognito,
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
                        book = route.book,
                        onBack = { readerTarget = null },
                        incognito = route.incognito,
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
                        book = route.book,
                        onBack = { readerTarget = null },
                        incognito = route.incognito,
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

        // Reader entry: the server mediaType is resolved before the reader route joins the stack, so the waiting
        // state is a plain black cover above everything; it fades out under the entering page.
        AnimatedVisibility(
            visible = readerTarget != null && readerTarget?.format == null,
            enter = fadeIn(animationSpec = tween(durationMillis = 80)),
            exit = fadeOut(animationSpec = tween(durationMillis = 200)),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * Renders the overlay stack above the tab skeleton, one fixed slot per [LayerDepth] rank.
 *
 * Every slot stays composed (an empty slot just draws nothing), so the page underneath an entering page keeps
 * its composition and its state; only the page that is actually pushed or popped takes part in a transition.
 * A page enters from the right, a page popped from the top slides back out to the right, and the page left
 * underneath recedes a little (slide left + dim) while anything covers it.
 */
@Composable
private fun OverlayStack(
    stack: List<Route>,
    content: @Composable (Route) -> Unit,
) {
    val byDepth = stack.associateBy { it.depth }
    Box(modifier = Modifier.fillMaxSize()) {
        for (depth in 1..LayerDepth.Max) {
            val route = byDepth[depth]
            LayerSlot(
                route = route,
                covered = route != null && (depth + 1..LayerDepth.Max).any { byDepth[it] != null },
                content = content,
            )
        }
    }
}

/**
 * One depth slot: the page currently living at that depth plus its enter and exit transition.
 *
 * [content] draws whichever route it is handed, so an exiting page is still drawn from its own route instead
 * of the nullable state that has already been cleared.
 */
@Composable
private fun LayerSlot(
    route: Route?,
    covered: Boolean,
    content: @Composable (Route) -> Unit,
) {
    val recede = animateFloatAsState(
        targetValue = if (covered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "layerRecede",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        // The covered page drifts left, so the sliver it uncovers is filled with the page surface instead of the
        // tab skeleton behind the stack; it fades with the drift, so nothing snaps while a page settles back.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = recede.value)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Parallax: the covered page drifts left while the new page slides in over it.
                    translationX = -size.width * 0.08f * recede.value
                },
        ) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    if (initialState != null && targetState != null) {
                        // The same depth swapped pages in place (book detail jumping to its series, a read list
                        // opening a series): the page above is the one that animates out, so the new page is
                        // simply in place by the time it is revealed instead of sliding in underneath.
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (
                            slideInHorizontally(
                                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                                initialOffsetX = { width -> width },
                            ) + fadeIn(animationSpec = tween(durationMillis = 150))
                        ).togetherWith(
                            slideOutHorizontally(
                                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                                targetOffsetX = { width -> width },
                            ),
                        )
                    }
                },
                contentKey = { it?.key },
                label = "layerSlot",
                modifier = Modifier.fillMaxSize(),
            ) { target ->
                if (target != null) content(target)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.10f * recede.value)),
            )
        }
    }
}

/**
 * Bottom floating capsule nav bar, used on every form factor (referencing mainstream tablet apps: compact
 * item height, a capsule indicator behind the selected icon, a translucent floating container). Unlike the
 * standard NavigationBar: item height is ~50dp instead of 80dp, it overlays the page content instead of
 * insetting it, and the width is capped at 400dp on wide screens.
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
                        lineHeight = 12.sp,
                        color = contentColor,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}
