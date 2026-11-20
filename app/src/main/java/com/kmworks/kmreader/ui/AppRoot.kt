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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
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
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import com.kmworks.kmreader.ui.library.LibraryContentType
import com.kmworks.kmreader.ui.library.LibraryFilterRequest
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

/**
 * One full-screen page pushed above the tab skeleton.
 *
 * [key] is the stable identity of the page: pushing a route that is already on top is a no-op, so the same
 * entry is never stacked twice. Pages live in a real stack, so back always returns to the page the current
 * one was opened from — including the filtered library list a metadata chip pushes.
 */
private sealed interface Route {
    val key: String
}

private data class SeriesDetailRoute(val series: Series) : Route {
    override val key: String get() = "series:${series.id}"
}

private data class BookDetailRoute(val book: Book) : Route {
    override val key: String get() = "book:${book.id}"
}

private data class CollectionDetailRoute(val id: Int) : Route {
    override val key: String get() = "collection:$id"
}

private data class ReadListDetailRoute(val id: Int) : Route {
    override val key: String get() = "readList:$id"
}

private data class SmartListDetailRoute(val id: Int) : Route {
    override val key: String get() = "smartList:$id"
}

private data class DashboardSectionRoute(val section: DashboardSection<out Any>) : Route {
    override val key: String get() = "dashboardSection:${section.id}"
}

private data object SettingsRoute : Route {
    override val key: String get() = "settings"
}

private data class SettingsPageRoute(val page: SettingsPage) : Route {
    override val key: String get() = "settings:${page.name}"
}

private data class ReaderRoute(val book: Book, val format: String, val incognito: Boolean) : Route {
    override val key: String get() = "reader:${book.id}:$incognito"
}

/**
 * A library list restricted to one metadata route, pushed above the page the chip was tapped on.
 *
 * It is a page of its own (the original app pushes the same list above the detail page), so the library tab
 * keeps whatever the user set up there, back returns to the page the chip was tapped on, and the page can be
 * left by clearing its filter chip.
 */
private data class FilteredLibraryRoute(val request: LibraryFilterRequest) : Route {
    // The route and the list it applies to, so a repeated tap on the same chip does not stack a second copy
    override val key: String get() = "library:${request.contentType}:${request.route}"
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
    // The real page stack: index 0 is the first page above the tabs. Pages are appended when something is
    // opened and removed from the end by back, so back always returns to the page the current one was opened
    // from (a detail page, a filtered list, a list detail) instead of leaving the app.
    val pages = remember { mutableStateListOf<Route>() }
    // Keeps every metadata-chip jump a distinct request (so a second jump re-applies the filter instead of
    // being treated as unchanged)
    var filterSequence by remember { mutableStateOf(0L) }

    val scope = rememberCoroutineScope()
    // Pushes a page. The page on top is never stacked twice, so tapping the same entry twice does nothing.
    val push: (Route) -> Unit = { route ->
        if (pages.lastOrNull()?.key != route.key) pages.add(route)
    }
    // Leaves the front-most page: a page's own back arrow, the back key and the gesture all run this, so
    // they can never disagree about which page leaves.
    val pop: () -> Unit = { if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) }
    // The filtered library list a metadata chip opens: a page of its own, so the library tab keeps whatever
    // the user set up there. The jumping page decides which list it shows (a series-detail chip opens the
    // series list, a book-detail chip the book list), mirroring the iOS per-origin browse destinations.
    val pushFilteredLibrary: (String, LibraryContentType) -> Unit = { route, contentType ->
        filterSequence += 1
        push(
            FilteredLibraryRoute(
                LibraryFilterRequest(
                    route = route,
                    contentType = contentType,
                    requestId = filterSequence,
                ),
            ),
        )
    }
    // Oneshot series click -> single-book detail (mirrors iOS NavDestination.oneshotDetail); fall back to regular series detail when the lookup fails
    val openSeries: (Series) -> Unit = { series ->
        if (series.oneshot) {
            scope.launch {
                val book = rootViewModel.oneshotBookForSeries(series.id)
                if (book != null) push(BookDetailRoute(book)) else push(SeriesDetailRoute(series))
            }
        } else {
            push(SeriesDetailRoute(series))
        }
    }
    val openBook: (Book) -> Unit = { push(BookDetailRoute(it)) }
    // Book context menu "Visit Series": resolve the owning series from the registry (an empty-shell Series; the detail VM loads the real data)
    val visitSeriesOfBook: (Book) -> Unit = { book ->
        scope.launch {
            rootViewModel.seriesStableIdForBook(book.id)?.let { seriesId ->
                push(
                    SeriesDetailRoute(
                        Series(
                            id = seriesId,
                            title = "",
                            author = "",
                            hue = 0f,
                            bookCount = 0,
                            unreadCount = 0,
                            progress = null,
                        ),
                    ),
                )
            }
        }
    }
    // The reader stacks like any page, but only once the server said which reader to open: until the media
    // type arrives a black cover with a spinner holds the screen (see the loading layer below), so opening a
    // book never shows the wrong reader sliding in.
    var resolvingReader by remember { mutableStateOf(false) }
    val openReader: (Book, Boolean) -> Unit = { book, incognito ->
        scope.launch {
            resolvingReader = true
            try {
                val format = rootViewModel.resolveBookFormat(book.id) ?: "CBZ"
                push(ReaderRoute(book = book, format = format, incognito = incognito))
            } finally {
                resolvingReader = false
            }
        }
    }

    // Back: the front-most page leaves. With nothing pushed no handler runs, so the system leaves the app.
    BackHandler(enabled = pages.isNotEmpty()) { pop() }

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
                        onOpenSettings = { push(SettingsRoute) },
                        onOpenBook = openBook,
                        onOpenSectionDetail = { push(DashboardSectionRoute(it)) },
                        onOpenContinuation = { push(ReadListDetailRoute(it.readListId)) },
                        onVisitSeries = visitSeriesOfBook,
                        onReadBook = openReader,
                    )

                    AppTab.Library -> LibraryScreen(
                        onOpenSeries = openSeries,
                        onOpenBook = openBook,
                        onVisitSeries = visitSeriesOfBook,
                        onReadBook = openReader,
                    )

                    AppTab.Lists -> ListsScreen(
                        onOpenCollection = { push(CollectionDetailRoute(it)) },
                        onOpenReadList = { push(ReadListDetailRoute(it)) },
                        onOpenSmartList = { push(SmartListDetailRoute(it)) },
                    )

                    AppTab.Offline -> OfflineScreen()

                    AppTab.Browse -> BrowseScreen(
                        onOpenSeries = openSeries,
                        onOpenBook = openBook,
                        onOpenReadList = { push(ReadListDetailRoute(it)) },
                        onOpenCollection = { push(CollectionDetailRoute(it)) },
                        onReadBook = openReader,
                    )
                }

                // Floating capsule navigation: drawn after the tab pages (so it stays above their content) and
                // before the full-screen overlay stack below (so detail/settings/reader pages still cover it).
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingNavBar(
                        selected = tab,
                        // Switching tabs leaves the pages of the previous tab on the stack; the reader is
                        // the one page that keeps the screen (a backgrounded read is a phone call away).
                        onSelect = { next -> tab = next },
                    )
                }
            }
        }

        // The reader's media type is still in flight: hold the screen with the black the readers open on, so
        // the page slides in over the same black instead of a cover handing over to a page.
        AnimatedVisibility(
            visible = resolvingReader,
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // Pages above the tab skeleton, drawn one animated slot per stack position: the tab skeleton is the
        // page below index 0 and every pushed page owns its slot, so the pages beneath keep their
        // composition (scroll position, open sheets) while the page above slides in.
        OverlayStack(stack = pages) { route ->
            when (route) {
                is SeriesDetailRoute -> {
                    val series = route.series
                    // Isolate Screen-level remember state per entry key (browse options/multi-select/sheet toggles do not leak into the next entry)
                    key(series.id) {
                        SeriesDetailScreen(
                            series = series,
                            onBack = pop,
                            onRead = { book -> openReader(book, false) },
                            onReadIncognito = { book -> openReader(book, true) },
                            onDeleted = pop,
                            onBrowseFilter = { filter ->
                                pushFilteredLibrary(filter, LibraryContentType.Series)
                            },
                            onOpenBook = openBook,
                            onOpenCollection = { push(CollectionDetailRoute(it)) },
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
                            onBack = pop,
                            onRead = { openReader(book, false) },
                            onReadIncognito = { openReader(book, true) },
                            onOpenReadList = {
                                // Read list detail stacks above book detail; back returns to book detail
                                push(ReadListDetailRoute(it))
                            },
                            onBrowseFilter = { filter ->
                                // Same as series detail, but the book author/tag chips land on the book list
                                pushFilteredLibrary(filter, LibraryContentType.Books)
                            },
                            onOpenSeries = {
                                scope.launch {
                                    rootViewModel.seriesStableIdForBook(book.id)?.let { seriesId ->
                                        push(
                                            SeriesDetailRoute(
                                                Series(
                                                    id = seriesId,
                                                    title = "",
                                                    author = "",
                                                    hue = 0f,
                                                    bookCount = 0,
                                                    unreadCount = 0,
                                                    progress = null,
                                                ),
                                            ),
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
                            onBack = pop,
                            // The series list stacks above the collection; back returns to the collection
                            onOpenSeries = { push(SeriesDetailRoute(it)) },
                        )
                    }
                }

                is ReadListDetailRoute -> {
                    val id = route.id
                    val readList = ReadListDetail(id = id, name = "", summary = null, ordered = true, bookIds = emptyList())
                    key(id) {
                        ReadListDetailScreen(
                            readList = readList,
                            onBack = pop,
                            // Mirrors the original app: tapping a book reads it directly (details go through the "View Details" menu item)
                            onRead = { openReader(it, false) },
                            onReadIncognito = { openReader(it, true) },
                            onOpenBook = { push(BookDetailRoute(it)) },
                            onOpenSeries = { seriesStableId ->
                                push(
                                    SeriesDetailRoute(
                                        Series(
                                            id = seriesStableId,
                                            title = "",
                                            author = "",
                                            hue = 0f,
                                            bookCount = 0,
                                            unreadCount = 0,
                                            progress = null,
                                        ),
                                    ),
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
                            onBack = pop,
                            onOpenBook = { push(BookDetailRoute(it)) },
                            onOpenSeries = { push(SeriesDetailRoute(it)) },
                            onRead = { openReader(it, false) },
                            onReadIncognito = { openReader(it, true) },
                        )
                    }
                }

                is DashboardSectionRoute -> {
                    val section = route.section
                    DashboardSectionDetailScreen(
                        section = section,
                        onBack = pop,
                        onOpenSeries = { push(SeriesDetailRoute(it)) },
                        onOpenBook = { push(BookDetailRoute(it)) },
                        onReadBook = { book, incognito -> openReader(book, incognito) },
                    )
                }

                is SettingsRoute -> SettingsScreen(
                    onBack = pop,
                    onOpenAppearance = { push(SettingsPageRoute(SettingsPage.Appearance)) },
                    onOpenBrowse = { push(SettingsPageRoute(SettingsPage.Browse)) },
                    onOpenDashboard = { push(SettingsPageRoute(SettingsPage.Dashboard)) },
                    onOpenReaderDefaults = { push(SettingsPageRoute(SettingsPage.ReaderDefaults)) },
                    onOpenStats = { push(SettingsPageRoute(SettingsPage.Stats)) },
                    onOpenCustomFonts = { push(SettingsPageRoute(SettingsPage.CustomFonts)) },
                    onOpenCache = { push(SettingsPageRoute(SettingsPage.Cache)) },
                    onOpenNetwork = { push(SettingsPageRoute(SettingsPage.Network)) },
                    onOpenSse = { push(SettingsPageRoute(SettingsPage.Sse)) },
                    onOpenLogs = { push(SettingsPageRoute(SettingsPage.Logs)) },
                    onOpenAccount = { push(SettingsPageRoute(SettingsPage.Account)) },
                    onOpenServer = { push(SettingsPageRoute(SettingsPage.Server)) },
                    onOpenAbout = { push(SettingsPageRoute(SettingsPage.About)) },
                    onOpenLicenses = { push(SettingsPageRoute(SettingsPage.Licenses)) },
                )

                is SettingsPageRoute -> when (route.page) {
                    SettingsPage.Appearance -> AppearanceSettingsScreen(onBack = pop)
                    SettingsPage.Browse -> BrowseSettingsScreen(onBack = pop)
                    SettingsPage.Dashboard -> SettingsDashboardScreen(onBack = pop)
                    SettingsPage.ReaderDefaults -> ReaderDefaultsScreen(
                        onBack = pop,
                        onOpenCustomFonts = { push(SettingsPageRoute(SettingsPage.CustomFonts)) },
                    )
                    SettingsPage.Stats -> ReadingStatsScreen(onBack = pop)
                    SettingsPage.CustomFonts -> CustomFontsScreen(onBack = pop)
                    SettingsPage.Cache -> CacheSettingsScreen(onBack = pop)
                    SettingsPage.Network -> NetworkSettingsScreen(onBack = pop)
                    SettingsPage.Sse -> SseSettingsScreen(onBack = pop)
                    SettingsPage.Logs -> LogsScreen(onBack = pop)
                    SettingsPage.Account -> ServerListScreen(onBack = pop)
                    SettingsPage.Server -> ServerAdminScreen(onBack = pop)
                    SettingsPage.About -> AboutScreen(onBack = pop)
                    SettingsPage.Licenses -> OpenSourceLicensesScreen(onBack = pop)
                }

                // The filtered library list a metadata chip pushed: a page of its own, so the library tab
                // keeps its own conditions and back returns to the page the chip was tapped on.
                is FilteredLibraryRoute -> key(route.key) {
                    LibraryScreen(
                        initialFilter = route.request,
                        onBack = pop,
                        onOpenSeries = openSeries,
                        onOpenBook = openBook,
                        onVisitSeries = visitSeriesOfBook,
                        onReadBook = openReader,
                    )
                }

                // Route the reader by real format (server mediaType): CBZ/CBR -> Divina, EPUB -> EPUB, PDF -> PDF.
                // The format was resolved before the page was pushed, so this layer only picks the reader.
                is ReaderRoute -> when (route.format) {
                    // Cross-page navigation actions from the in-reader detail sheet push the target page
                    // above the reader, so back returns into the book being read.
                    "EPUB" -> EpubReaderScreen(
                        book = route.book,
                        onBack = pop,
                        incognito = route.incognito,
                        onVisitSeries = {
                            pop()
                            visitSeriesOfBook(it)
                        },
                        onOpenReadList = { push(ReadListDetailRoute(it)) },
                        onBrowseFilter = { filter ->
                            pushFilteredLibrary(filter, LibraryContentType.Books)
                        },
                    )

                    "PDF" -> PdfReaderScreen(
                        book = route.book,
                        onBack = pop,
                        incognito = route.incognito,
                        onVisitSeries = {
                            pop()
                            visitSeriesOfBook(it)
                        },
                        onOpenReadList = { push(ReadListDetailRoute(it)) },
                        onBrowseFilter = { filter ->
                            pushFilteredLibrary(filter, LibraryContentType.Books)
                        },
                    )

                    else -> ReaderScreen(
                        book = route.book,
                        onBack = pop,
                        incognito = route.incognito,
                        onVisitSeries = {
                            pop()
                            visitSeriesOfBook(it)
                        },
                        onOpenReadList = { push(ReadListDetailRoute(it)) },
                        onBrowseFilter = { filter ->
                            pushFilteredLibrary(filter, LibraryContentType.Books)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A view model store that lives exactly as long as the page composed in it.
 *
 * A pushed page owns its state the way a navigation entry does: the root hands it its own store, so its view
 * model -- and anything the page installed on it, such as the browse condition a metadata-chip jump applies --
 * is cleared when the page leaves instead of lingering in the activity-wide store. Factories and extras are
 * forwarded to the host, so the page still gets its dependencies injected exactly as before.
 */
private class PageViewModelStoreOwner(
    private val host: HasDefaultViewModelProviderFactory,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = host.defaultViewModelProviderFactory
    override val defaultViewModelCreationExtras: CreationExtras
        get() = host.defaultViewModelCreationExtras
}

/**
 * Gives the page composed here its own view models, identified by [key].
 *
 * Two pages of the same kind can stand in the stack at once (a series opened from a book of another series),
 * and each has to keep its own state instead of sharing one screen's; a page that leaves also takes its view
 * models with it.
 */
@Composable
private fun PageScope(key: String, content: @Composable () -> Unit) {
    val host = LocalViewModelStoreOwner.current as? HasDefaultViewModelProviderFactory
    val owner = remember(key, host) { host?.let { PageViewModelStoreOwner(it) } }
    DisposableEffect(owner) {
        onDispose { owner?.viewModelStore?.clear() }
    }
    if (owner != null) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
    } else {
        content()
    }
}

/**
 * Renders the pushed pages above the tab skeleton, one slot per stack position.
 *
 * Every slot stays composed (an empty slot draws nothing), so the page underneath an entering page keeps its
 * composition and its state; only the page that is pushed or popped takes part in a transition.
 */
@Composable
private fun OverlayStack(
    stack: List<Route>,
    content: @Composable (Route) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // One slot past the top: the page that was just popped is still drawn there while it leaves.
        for (index in 0..stack.size) {
            key(index) {
                LayerSlot(
                    route = stack.getOrNull(index),
                    covered = index < stack.lastIndex,
                    content = content,
                )
            }
        }
    }
}

/**
 * One stack position: the page living there plus the motion that brings it in and takes it away.
 *
 * A page that is covered drifts left and dims a little, and the sliver its drift uncovers is filled with the
 * page surface instead of the tab skeleton behind the stack. A page swapped at the same position (the series
 * of the book already open) is replaced in place rather than pushed.
 */
@Composable
private fun LayerSlot(
    route: Route?,
    covered: Boolean,
    content: @Composable (Route) -> Unit,
) {
    val recede by animateFloatAsState(
        targetValue = if (covered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "layerSlot",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        // A covered page drifts left, so the sliver it uncovers is filled with the page surface instead of
        // the tab skeleton behind the stack; it fades in with the drift, so nothing snaps while a page
        // settles.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = recede)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -size.width * 0.08f * recede },
        ) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    if (initialState != null && targetState != null) {
                        // Same stack position, different page: it replaces the page in place instead of
                        // claiming a place in the hierarchy it does not have.
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (
                            slideInHorizontally(
                                animationSpec = spring(
                                    dampingRatio = 0.9f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                                initialOffsetX = { width -> width },
                            ) + fadeIn(tween(150))
                            ).togetherWith(
                            slideOutHorizontally(
                                animationSpec = spring(
                                    dampingRatio = 0.9f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                                targetOffsetX = { width -> width },
                            ),
                        )
                    }
                },
                contentKey = { it?.key },
                label = "layerSlot",
            ) { target ->
                if (target != null) {
                    PageScope(key = target.key) { content(target) }
                }
            }
            // A covered page dims a little: enough depth to read the stack, not enough to hide the page.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.10f * recede)),
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
