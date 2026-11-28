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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.kmworks.kmreader.ui.components.LocalDownloadedIndex
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
import com.kmworks.kmreader.ui.components.OfflineIndicator
import com.kmworks.kmreader.ui.dashboard.DashboardScreen
import com.kmworks.kmreader.ui.dashboard.DashboardSectionDetailScreen
import com.kmworks.kmreader.ui.dashboard.DashboardViewModel
import com.kmworks.kmreader.ui.dashboard.SettingsDashboardScreen
import com.kmworks.kmreader.ui.detail.BookDetailScreen
import com.kmworks.kmreader.ui.detail.SeriesDetailScreen
import com.kmworks.kmreader.ui.library.LibraryContentType
import com.kmworks.kmreader.ui.library.LibraryFilterRequest
import com.kmworks.kmreader.ui.library.LibraryScreen
import com.kmworks.kmreader.ui.lists.CollectionDetailScreen
import com.kmworks.kmreader.ui.lists.ListsGroupKind
import com.kmworks.kmreader.ui.lists.ListsScreen
import com.kmworks.kmreader.ui.lists.ListsViewModel
import com.kmworks.kmreader.ui.lists.ReadListDetailScreen
import com.kmworks.kmreader.ui.lists.SmartListDetailScreen
import com.kmworks.kmreader.ui.login.LoginScreen
import com.kmworks.kmreader.ui.offline.OfflineScreen
import com.kmworks.kmreader.ui.reader.CustomFontsScreen
import com.kmworks.kmreader.ui.reader.DivinaSettingsViewModel
import com.kmworks.kmreader.ui.reader.EpubReaderScreen
import com.kmworks.kmreader.ui.reader.KeepScreenOnEffect
import com.kmworks.kmreader.ui.reader.ReaderScreen
import com.kmworks.kmreader.ui.settings.AboutScreen
import com.kmworks.kmreader.ui.settings.ServerListScreen
import com.kmworks.kmreader.ui.settings.AppearanceSettingsScreen
import com.kmworks.kmreader.ui.settings.BrowseSettingsScreen
import com.kmworks.kmreader.ui.settings.CacheSettingsScreen
import com.kmworks.kmreader.ui.settings.LogsScreen
import com.kmworks.kmreader.ui.settings.LocalSettingsPane
import com.kmworks.kmreader.ui.settings.NetworkSettingsScreen
import com.kmworks.kmreader.ui.settings.OpenSourceLicensesScreen
import com.kmworks.kmreader.ui.settings.ReaderDefaultsScreen
import com.kmworks.kmreader.ui.settings.SettingsPage
import com.kmworks.kmreader.ui.settings.SettingsScreen
import com.kmworks.kmreader.ui.settings.SseSettingsScreen
import com.kmworks.kmreader.ui.stats.ReadingStatsScreen
import com.kmworks.kmreader.ui.server.ServerAdminScreen
import com.kmworks.kmreader.ui.tracker.TrackerSettingsScreen
import com.kmworks.kmreader.ui.update.AppUpdateViewModel
import com.kmworks.kmreader.ui.update.UpdateAvailableDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

/**
 * One Lists-tab group opened as a page ("see all"): every collection / read list / smart list of that
 * kind in a vertical grid, paged through the Lists tab's own view model so the strip and the page never
 * disagree about what has been loaded.
 */
private data class ListsGroupRoute(val kind: ListsGroupKind) : Route {
    override val key: String get() = "listsGroup:${kind.name}"
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

/**
 * [format] is [Book.readerFormat], known before the page is pushed, so opening a book needs no lookup.
 * [readListStableId] is the read list the book was opened from, so the session continues in its order.
 * [startAtEnd] opens the book on its last page (explicit end-of-book entries). The backward seam into a
 * previous book no longer sets it: that book resumes on its own progress.
 */
private data class ReaderRoute(
    val book: Book,
    val format: String,
    val incognito: Boolean,
    val readListStableId: Int? = null,
    val startAtEnd: Boolean = false,
) : Route {
    override val key: String get() = "reader:${book.id}:$incognito:$startAtEnd"
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
    updateViewModel: AppUpdateViewModel = hiltViewModel(),
    // Only read here, for the device-wide keep-screen-on flag: the readers hold the same preference
    // but never write the flag themselves (see KeepScreenOnEffect).
    readerSettingsViewModel: DivinaSettingsViewModel = hiltViewModel(),
) {
    // Session gating: render only the login page while no instance is configured. The config is read
    // from disk asynchronously, so the gate resolves asynchronously too: keep that first frame blank
    // (page background only) instead of flashing the login page before the saved session arrives.
    val sessionGate by rootViewModel.sessionGate.collectAsStateWithLifecycle()
    // Auto update check: the launch lookup prompts only when a release newer than this build exists, and
    // at most once a day (the check itself is throttled in the view model; the About page can force one).
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    // Completed downloads for the card badges (books + per-series aggregates), one app-wide snapshot.
    val downloadedIndex by rootViewModel.downloadedIndex.collectAsStateWithLifecycle()
    // Server reachability (fed by the shared OkHttp client): drives the global offline indicator,
    // the counterpart of the red wifi.slash the iOS app shows (startup failures and mid-session alike).
    val serverOffline by rootViewModel.serverOffline.collectAsStateWithLifecycle()
    // True while the indicator's tap-to-retry probe is in flight (spinner in the pill).
    val probingServer by rootViewModel.probingServer.collectAsStateWithLifecycle()
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
    // The tab skeleton's view-model store. A pushed page gets its own store from PageScope, so a
    // page that must share a tab's view model resolves it against this owner instead.
    val tabViewModelStoreOwner = LocalViewModelStoreOwner.current
    // The Lists tab's view model, resolved against the tab store even from inside a pushed page:
    // the group page then paints the rows the tab already loaded instead of re-seeding the disk
    // cache (stale cards flash, then get replaced) and re-fetching, and edits made on the page
    // are visible on the tab the moment it shows again.
    val sharedListsViewModel: @Composable () -> ListsViewModel = {
        tabViewModelStoreOwner?.let { hiltViewModel<ListsViewModel>(viewModelStoreOwner = it) } ?: hiltViewModel()
    }
    // Same sharing for the dashboard: the section "More" page reads the tab's already-loaded
    // section feeds instead of re-fetching page 0 behind a spinner.
    val sharedDashboardViewModel: @Composable () -> DashboardViewModel = {
        tabViewModelStoreOwner?.let { hiltViewModel<DashboardViewModel>(viewModelStoreOwner = it) } ?: hiltViewModel()
    }
    // The real page stack: index 0 is the first page above the tabs. Pages are appended when something is
    // opened and removed from the end by back, so back always returns to the page the current one was opened
    // from (a detail page, a filtered list, a list detail) instead of leaving the app.
    val pages = remember { mutableStateListOf<PageEntry>() }
    // Pages that were just popped and are still travelling out. They hold their own motion until they are
    // gone, and the page they uncover reads it, so a page is never left standing at a half-covered position.
    val leaving = remember { mutableStateListOf<PageEntry>() }

    // Keep-screen-on has exactly ONE writer: this host, for the page the stack currently draws on top.
    // The three readers only share the preference. AppRoot keeps departing and covered readers composed
    // on the same view, so a per-reader effect would let an outgoing reader clear the flag the incoming
    // reader just set, and would keep the device awake while a reader sits covered by a library page.
    val readerSettings by readerSettingsViewModel.settings.collectAsStateWithLifecycle()
    KeepScreenOnEffect(
        enabled = readerSettings.keepScreenAwake && pages.lastOrNull()?.route is ReaderRoute,
    )
    // Identifies this appearance of a page rather than the page itself: the same route can be opened again
    // after it was closed, and the motion that slides a page in has to belong to the appearance.
    var pageSequence by remember { mutableLongStateOf(0L) }
    // Keeps every metadata-chip jump a distinct request (so a second jump re-applies the filter instead of
    // being treated as unchanged)
    var filterSequence by remember { mutableLongStateOf(0L) }

    val scope = rememberCoroutineScope()
    // Input on a page that is about to be covered has to give up its focus. The covered page stays
    // composed (OverlayStack draws every layer), so a focused field keeps its input session alive:
    // backgrounding hides the IME while the session survives, and returning re-attaches the keyboard
    // to a field the user cannot see. This is the one place a page covers another, so every field
    // is served by it -- and with nothing pushed, a field keeps its focus, keyboard and cursor
    // through a trip to the background.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Pushes a page. The page on top is never stacked twice, so tapping the same entry twice does nothing.
    val push: (Route) -> Unit = { route ->
        if (pages.lastOrNull()?.route?.key != route.key) {
            // End the input session before dropping the IME: the keyboard would otherwise collapse
            // over the old page while the new one is already sliding in.
            focusManager.clearFocus()
            keyboardController?.hide()
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
    // Opening from a read list carries the list so the session continues in list order (mirrors the
    // original ReaderReadListContext passed from the read list page)
    val openReadListBook: (Book, Boolean, Int) -> Unit = { book, incognito, readListId ->
        push(
            ReaderRoute(
                book = book,
                format = book.readerFormat,
                incognito = incognito,
                readListStableId = readListId,
            ),
        )
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Overlay navigation on every form factor: the tab bar floats above the pages, so their content keeps the full
        // height and scrolls underneath it, reserving only LocalNavOverlaySpace at the bottom. Wide layouts just cap the
        // capsule width instead of switching to a different bar shape (no more NavigationBar row eating ~80dp on phones).
        val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        CompositionLocalProvider(
            LocalNavOverlaySpace provides (NavOverlaySpace + navigationInset),
            LocalDownloadedIndex provides downloadedIndex,
        ) {
            // The pages no longer own a Scaffold: the root paints the app background behind their edge-to-edge
            // content (status bar strip included), so the frosted top bars have something to sit on.
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                // Keep each tab's remembered state (scroll positions, drafts) alive across tab switches:
                // without a per-tab SaveableStateProvider the leaving page drops out of composition and its
                // rememberSaveable state (the lazy list/grid scroll offsets included) is discarded.
                val tabStateHolder = rememberSaveableStateHolder()
                tabStateHolder.SaveableStateProvider(tab) {
                when (tab) {
                    AppTab.Home -> DashboardScreen(
                        onOpenSeries = openSeries,
                        onOpenSettings = { push(SettingsRoute) },
                        onOpenBook = openBook,
                        onOpenSectionDetail = { push(DashboardSectionRoute(it)) },
                        onOpenContinuation = { push(ReadListDetailRoute(it.readListId)) },
                        onVisitSeries = visitSeriesOfBook,
                        onReadBook = openReader,
                        viewModel = sharedDashboardViewModel(),
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
                        onOpenGroup = { push(ListsGroupRoute(it)) },
                        viewModel = sharedListsViewModel(),
                    )

                    AppTab.Offline -> OfflineScreen(
                        onOpenBook = openBook,
                        onOpenSeries = openSeries,
                    )

                    AppTab.Browse -> BrowseScreen(
                        onOpenSeries = openSeries,
                        onOpenBook = openBook,
                        onOpenReadList = { push(ReadListDetailRoute(it)) },
                        onOpenCollection = { push(CollectionDetailRoute(it)) },
                        onReadBook = openReader,
                    )
                }
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

            // Pages above the tab skeleton, drawn one layer per page: the tab skeleton is the page below index 0
            // and every pushed page owns its layer, so the pages beneath keep their composition (scroll position,
            // open sheets) while the page above slides in. The provider above wraps this stack too: detail and
            // reader pages read the same locals (downloaded index, nav overlay space) as the tab pages.
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
                            onRead = { openReadListBook(it, false, id) },
                            onReadIncognito = { openReadListBook(it, true, id) },
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
                        viewModel = sharedDashboardViewModel(),
                    )
                }

                is ListsGroupRoute -> ListsScreen(
                    initialGroup = route.kind,
                    onBack = pop,
                    onOpenCollection = { push(CollectionDetailRoute(it)) },
                    onOpenReadList = { push(ReadListDetailRoute(it)) },
                    onOpenSmartList = { push(SmartListDetailRoute(it)) },
                    viewModel = sharedListsViewModel(),
                )

                is SettingsRoute -> SettingsScreen(
                    onBack = pop,
                    onOpenPage = { page -> push(SettingsPageRoute(page)) },
                    // Tablet split: the hub keeps its column and the chosen page renders beside it. The
                    // pane gets its own view-model scope, so a page that is left takes its state with it.
                    splitDetail = { page, onDismiss, onNavigate ->
                        PageScope(key = "settings:${page.name}") {
                            // The pane's own pages drop their back arrow; the hub's arrow leaves settings.
                            CompositionLocalProvider(LocalSettingsPane provides true) {
                                SettingsPageContent(
                                    page = page,
                                    onBack = onDismiss,
                                    onNavigate = onNavigate,
                                    updateViewModel = updateViewModel,
                                )
                            }
                        }
                    },
                )

                is SettingsPageRoute -> SettingsPageContent(
                    page = route.page,
                    onBack = pop,
                    onNavigate = { page -> push(SettingsPageRoute(page)) },
                    updateViewModel = updateViewModel,
                )

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
                // mediaType): EPUB -> EPUB reader, everything else (CBZ/CBR/PDF) -> DIVINA, which
                // renders PDF pages the same way it renders archive pages.
                is ReaderRoute -> {
                    // Cross-page navigation actions from the in-reader detail sheet push the target page
                    // above the reader, so back returns into the book being read. A neighbouring book is
                    // read by the reader for its own format: replace this route so that reader takes over
                    // (the Divina reader stays open across books, the other two swap the page).
                    fun openAdjacent(next: Book, atEnd: Boolean) {
                        pop()
                        push(
                            ReaderRoute(
                                book = next,
                                format = next.readerFormat,
                                incognito = route.incognito,
                                readListStableId = route.readListStableId,
                                startAtEnd = atEnd,
                            ),
                        )
                    }
                    when (route.format) {
                        "EPUB" -> EpubReaderScreen(
                            book = route.book,
                            onBack = pop,
                            incognito = route.incognito,
                            readListStableId = route.readListStableId,
                            startAtEnd = route.startAtEnd,
                            onOpenBook = ::openAdjacent,
                            onVisitSeries = {
                                pop()
                                visitSeriesOfBook(it)
                            },
                            onOpenReadList = { push(ReadListDetailRoute(it)) },
                            onBrowseFilter = { filter ->
                                pushFilteredLibrary(filter, LibraryContentType.Books)
                            },
                        )

                        // PDF (and any other non-EPUB format) opens in the DIVINA comic reader, which
                        // renders each page — online from the server, offline from the downloaded PDF.
                        else -> ReaderScreen(
                            book = route.book,
                            onBack = pop,
                            incognito = route.incognito,
                            readListStableId = route.readListStableId,
                            startAtEnd = route.startAtEnd,
                            onVisitSeries = {
                                pop()
                                visitSeriesOfBook(it)
                            },
                            onOpenReadList = { push(ReadListDetailRoute(it)) },
                            onBrowseFilter = { filter ->
                                pushFilteredLibrary(filter, LibraryContentType.Books)
                            },
                            onOpenBook = ::openAdjacent,
                        )
                    }
                }
            }
        }
        // The offline pill floats at the top centre, above every tab page and every pushed page
        // (reader included): the frosted top bars keep their title left and actions right, so the
        // centre is free everywhere. Drawn before the update dialog, which still tops it.
        OfflineIndicator(
            visible = serverOffline,
            probing = probingServer,
            onRetry = rootViewModel::retryConnection,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        )
        // One "new version" prompt for the whole app: it sits above the page stack, so a release found at
        // launch is announced on whatever page the user is on.
        if (updateState.dialogVisible) {
            UpdateAvailableDialog(state = updateState, onDismiss = updateViewModel::dismissDialog)
        }
        }
    }
}

/**
 * Renders one settings page.
 *
 * A pushed route on a phone and the detail pane of the tablet split show the same composable, so the two
 * presentations cannot drift apart: [onBack] leaves the page (pop, or clear the split selection) and
 * [onNavigate] opens a page from inside a page -- another pane on a tablet, a pushed page on a phone.
 */
@Composable
private fun SettingsPageContent(
    page: SettingsPage,
    onBack: () -> Unit,
    onNavigate: (SettingsPage) -> Unit,
    // Shared with the root so the About page reports into the one dialog the root draws: a page keeps
    // its own view-model scope, which would otherwise hide the prompt from the app-wide instance.
    updateViewModel: AppUpdateViewModel,
) {
    when (page) {
        SettingsPage.Appearance -> AppearanceSettingsScreen(onBack = onBack)
        SettingsPage.Browse -> BrowseSettingsScreen(onBack = onBack)
        SettingsPage.Dashboard -> SettingsDashboardScreen(onBack = onBack)
        SettingsPage.ReaderDefaults -> ReaderDefaultsScreen(
            onBack = onBack,
            onOpenCustomFonts = { onNavigate(SettingsPage.CustomFonts) },
        )
        SettingsPage.Trackers -> TrackerSettingsScreen(onBack = onBack)
        SettingsPage.Stats -> ReadingStatsScreen(onBack = onBack)
        SettingsPage.CustomFonts -> CustomFontsScreen(onBack = onBack)
        SettingsPage.Cache -> CacheSettingsScreen(onBack = onBack)
        SettingsPage.Network -> NetworkSettingsScreen(onBack = onBack)
        SettingsPage.Sse -> SseSettingsScreen(onBack = onBack)
        SettingsPage.Logs -> LogsScreen(onBack = onBack)
        SettingsPage.Account -> ServerListScreen(onBack = onBack)
        SettingsPage.Server -> ServerAdminScreen(onBack = onBack)
        SettingsPage.About -> AboutScreen(onBack = onBack, updateViewModel = updateViewModel)
        SettingsPage.Licenses -> OpenSourceLicensesScreen(onBack = onBack)
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
    // The Animatable constructor is @RememberInComposition: it must be created inside remember, and
    // key(page.id) keeps one remember slot per page appearance so the getOrPut still runs only once.
    // (RememberReturnType suppressed: the remember result is intentionally unbound — the layers read
    // the motion back through the map, and the nested getOrPut lambda makes lint infer Unit.)
    @Suppress("RememberReturnType")
    drawn.forEach { page ->
        key(page.id) { remember { motions.getOrPut(page.id) { Animatable(1f) } } }
    }
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
                // A page is opaque, and so must its touch area be: when its content draws no gesture
                // handler of its own (a reader still loading, an error state) hit testing would skip
                // this layer and fall through to the page underneath, where the tap lands on that
                // page's cards. This sink only claims the hit path -- it never consumes, so the
                // page's own gestures are unaffected. It rides on the translated layer so it covers
                // exactly the part of the page that is on screen.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
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
