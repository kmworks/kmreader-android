package com.kmworks.kmreader.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
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

/** [format] is [Book.readerFormat], known before the page is pushed, so opening a book needs no lookup. */
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
 * One page in the stack.
 *
 * [id] belongs to this appearance of the page, not to the page: closing a page and opening it again is two
 * appearances, and each one gets its own motion, because a page that is still travelling out and one that is
 * travelling in must not share a position.
 */
private class PageEntry(val id: Long, val route: Route)

/** Where a page is between its own place and one full width off the right edge: 0 in place, 1 fully out. */
private typealias PageMotion = Animatable<Float, AnimationVector1D>

/**
 * Bottom space the floating navigation capsule occupies above the navigation-bar inset: item row (icon +
 * label, ~44dp) plus the capsule and outer paddings. Pages reserve it as list content padding so the last
 * row can scroll clear of the bar; the system bar inset is added on top at the call site.
 */
private val NavOverlaySpace = 76.dp

/**
 * The page a system back gesture drags.
 *
 * The root owns the gesture -- it is the handler the system talks to -- while the stack owns the motion, so
 * the stack publishes which page is in front and the gesture writes its progress straight into that page's
 * animation. A frame of the drag therefore costs one draw instead of a recomposition of the whole stack, and
 * letting go continues from the finger instead of restarting the travel.
 */
private class BackDrag {
    /** The page in front, the one a completed gesture would pop. */
    var page: PageMotion? = null
}

/** How long a page takes to slide in over the one below (full width, from the right edge). */
private const val PushMillis = 300

/** How long a page takes to slide out when it is popped, starting from rest. */
private const val PopMillis = 250

/** How long a page takes to settle back when a back gesture is abandoned short of the edge. */
private const val BackSettleMillis = 200

/** How far the page below drifts left while it is covered, as a fraction of its width. */
private const val RecedeDrift = 0.08f

/** How much the page below dims while it is covered. */
private const val RecedeDim = 0.10f

/** Width of the shadow the page above casts over the page below, at its left edge. */
private val PageShadowWidth = 24.dp

/** Strength of that shadow where it meets the page. */
private const val PageShadowAlpha = 0.32f

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
    val pages = remember { mutableStateListOf<PageEntry>() }
    // Pages that were just popped and are still travelling out. They hold their own motion until they are
    // gone, and the page they uncover reads it, so a page is never left standing at a half-covered position.
    val leaving = remember { mutableStateListOf<PageEntry>() }
    // Identifies this appearance of a page rather than the page itself: the same route can be opened again
    // after it was closed, and the motion that slides a page in has to belong to the appearance.
    var pageSequence by remember { mutableStateOf(0L) }
    // Keeps every metadata-chip jump a distinct request (so a second jump re-applies the filter instead of
    // being treated as unchanged)
    var filterSequence by remember { mutableStateOf(0L) }

    val scope = rememberCoroutineScope()
    // Pushes a page. The page on top is never stacked twice, so tapping the same entry twice does nothing.
    val push: (Route) -> Unit = { route ->
        if (pages.lastOrNull()?.route?.key != route.key) {
            pages.add(PageEntry(id = pageSequence++, route = route))
        }
    }
    // Leaves the front-most page: a page's own back arrow, the back key and the gesture all run this, so
    // they can never disagree about which page leaves.
    val pop: () -> Unit = { if (pages.isNotEmpty()) leaving.add(pages.removeAt(pages.lastIndex)) }
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
                            readingDirection = "LEFT_TO_RIGHT",
                        ),
                    ),
                )
            }
        }
    }
    // The reader route needs the format before the page is pushed, and every book already carries it
    // (Book.readerFormat, from the server mediaType): the page slides in immediately, over the page it
    // leaves, instead of holding a cover screen while the format is looked up.
    val openReader: (Book, Boolean) -> Unit = { book, incognito ->
        push(ReaderRoute(book = book, format = book.readerFormat, incognito = incognito))
    }

    // The page the system back gesture drags: the stack keeps it pointing at the page in front, and the
    // gesture below writes the drag straight into that page's motion, so the page follows the finger.
    val backDrag = remember { BackDrag() }

    // Back: the front-most page leaves, following the predictive gesture when the system sends one. With
    // nothing pushed no handler runs, so the system leaves the app. This has to stay registered before the
    // pages themselves: a page that handles back on its own (the server admin screens) registers later and
    // therefore wins.
    PredictiveBackHandler(enabled = pages.isNotEmpty()) { events ->
        val page = backDrag.page
        if (page == null) {
            events.collect { }
            pop()
        } else {
            // The drag continues from wherever the page happens to be (a page can still be sliding in when
            // the gesture starts), so the finger always picks the page up where it is drawn.
            val from = page.value
            try {
                events.collect { event -> page.snapTo(from + (1f - from) * event.progress) }
                pop()
            } catch (cancelled: CancellationException) {
                // The gesture was let go short: the page travels back to where it came from. The handler's
                // own job is already cancelled here, so the way back has to run on the composition's scope.
                scope.launch {
                    page.animateTo(0f, tween(durationMillis = BackSettleMillis, easing = FastOutSlowInEasing))
                }
                throw cancelled
            }
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

        // Pages above the tab skeleton, drawn one layer per page: the tab skeleton is the page below index 0
        // and every pushed page owns its layer, so the pages beneath keep their composition (scroll position,
        // open sheets) while the page above slides in.
        OverlayStack(
            stack = pages,
            leaving = leaving,
            backDrag = backDrag,
            onLeaveFinished = { leaving.remove(it) },
        ) { route ->
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
                                                    readingDirection = "LEFT_TO_RIGHT",
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
                                            readingDirection = "LEFT_TO_RIGHT",
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

                // Route the reader by the format the book carries (Book.readerFormat, from the server
                // mediaType): CBZ/CBR -> Divina, EPUB -> EPUB, PDF -> PDF.
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
    // The page's view models are cleared one main-thread message after the page is gone instead of inside the
    // frame that also removes it: the teardown then happens outside the frame that is still drawing the page.
    val mainThread = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(owner) {
        onDispose {
            owner?.let { gone -> mainThread.post { gone.viewModelStore.clear() } }
        }
    }
    if (owner != null) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
    } else {
        content()
    }
}

/**
 * Renders the pushed pages above the tab skeleton, one layer per page.
 *
 * Each page owns one motion value: 0 while it sits in place, 1 once it is off the screen to the right. Being
 * pushed and being popped animate that same value, the page underneath reads it to drift left and dim by
 * exactly as much, and a back gesture writes the finger straight into it -- so the travel, the parallax and the
 * dim cannot drift apart, however a page is left.
 *
 * A page that was popped stays in the tree until its motion finishes ([leaving]) and is drawn above the stack,
 * so the page it uncovers keeps its composition and its state all the way through.
 */
@Composable
private fun OverlayStack(
    stack: List<PageEntry>,
    leaving: List<PageEntry>,
    backDrag: BackDrag,
    onLeaveFinished: (PageEntry) -> Unit,
    content: @Composable (Route) -> Unit,
) {
    // One motion per page appearance. They exist before the layers compose, so a layer can read the value of
    // the page above it, and a page drops its own once it is gone.
    val motions = remember { mutableMapOf<Long, PageMotion>() }
    val drawn = stack + leaving
    drawn.forEach { page -> motions.getOrPut(page.id) { Animatable(1f) } }
    // The gesture drags the page in front; with an empty stack there is nothing to drag.
    SideEffect { backDrag.page = stack.lastOrNull()?.let { motions[it.id] } }

    Box(modifier = Modifier.fillMaxSize()) {
        drawn.forEachIndexed { index, page ->
            key(page.id) {
                LayerSlot(
                    route = page.route,
                    motion = motions.getValue(page.id),
                    // The page drawn directly above this one, in drawing order.
                    above = drawn.getOrNull(index + 1)?.let { motions[it.id] },
                    leaving = index >= stack.size,
                    onLeaveFinished = {
                        motions.remove(page.id)
                        onLeaveFinished(page)
                    },
                    content = content,
                )
            }
        }
    }
}

/**
 * One page: its motion, the room it makes for the page above, and the shadow it casts while it travels.
 *
 * The page below a moving page is not animated on its own: it reads the motion of the page above it while it
 * draws, so being covered is one motion instead of two that have to be kept in step.
 */
@Composable
private fun LayerSlot(
    route: Route,
    motion: PageMotion,
    above: PageMotion?,
    leaving: Boolean,
    onLeaveFinished: () -> Unit,
    content: @Composable (Route) -> Unit,
) {
    // A page that leaves finishes the travel it was dragged into -- from wherever the drag or an earlier
    // animation left it, so letting go near the edge does not make the last stretch crawl -- and a page that
    // arrives comes in from the right edge.
    LaunchedEffect(motion, leaving) {
        if (leaving) {
            val remaining = (1f - motion.value).coerceIn(0f, 1f)
            motion.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = (PopMillis * remaining).roundToInt().coerceIn(0, PopMillis),
                    easing = FastOutSlowInEasing,
                ),
            )
            onLeaveFinished()
        } else {
            motion.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = PushMillis, easing = FastOutSlowInEasing),
            )
        }
    }

    val surface = MaterialTheme.colorScheme.background
    val shadow = Color.Black

    Box(modifier = Modifier.fillMaxSize()) {
        // A covered page drifts left, so the sliver it uncovers is filled with the page surface instead of the
        // tab skeleton behind the stack; it fades in with the drift, so nothing snaps while a page settles.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = above?.let { 1f - it.value } ?: 0f }
                .background(surface),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // The page's own travel across the screen, plus the little drift a covered page makes
                    // room with. Both read their animation while drawing, so a page in motion costs a draw
                    // instead of a recomposition of everything below it.
                    val recede = above?.let { 1f - it.value } ?: 0f
                    translationX = size.width * (motion.value - RecedeDrift * recede)
                }
                // The page casts a shadow over the one it uncovers. It is strongest mid-travel and gone at
                // both ends, so no dark line is left against the screen edge while a page sits still.
                .drawBehind {
                    val weight = minOf(motion.value, 1f - motion.value) * 2f
                    if (weight <= 0f) return@drawBehind
                    val width = PageShadowWidth.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, shadow),
                            startX = -width,
                            endX = 0f,
                        ),
                        topLeft = Offset(-width, 0f),
                        size = Size(width, size.height),
                        alpha = weight * PageShadowAlpha,
                    )
                },
        ) {
            PageScope(key = route.key) { content(route) }
            // A covered page dims by exactly as much as the page above has moved in.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = RecedeDim * (above?.let { 1f - it.value } ?: 0f) }
                    .background(Color.Black),
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
