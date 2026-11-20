package com.kmworks.kmreader.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.kmworks.kmreader.R
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.glassPageContentWindowInsets
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.rememberGlassBarProgress
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.bookCountLineText
import com.kmworks.kmreader.ui.components.completedLastReadText
import com.kmworks.kmreader.ui.components.oneshotLineText
import com.kmworks.kmreader.ui.components.pageLabelText
import com.kmworks.kmreader.ui.components.subtitleText
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BookListRow
import com.kmworks.kmreader.ui.components.BrowseFilterChipFlow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.rememberPersistentBrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.bookContextMenuItems
import com.kmworks.kmreader.ui.components.rememberBrowseFilterSuggestions
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.SelectionToolbar
import com.kmworks.kmreader.ui.components.notableEntityIcon
import com.kmworks.kmreader.ui.lists.AddToListSheet
import com.kmworks.kmreader.ui.tracker.TrackerSection
import com.kmworks.kmreader.ui.tracker.TrackerSeriesSheet
import com.kmworks.kmreader.ui.util.shareText
import kotlinx.coroutines.launch

// Mirrors LayoutConfig.detailWideLayoutMinimumWidth = 960 (the threshold for the wide detail layout)
private val WideLayoutBreakpoint = 960.dp
private val HeroCoverWidth = 168.dp
private val ActionCardMaxWidth = 480.dp
private val ActionCardShape = RoundedCornerShape(16.dp)

/** Wire tokens of the metadata-filter route shared with the library screen (this screen emits, it
 *  consumes). Internal routing values only; display labels are localized at render time. */
private const val FILTER_KIND_AUTHOR = "author"
private const val FILTER_KIND_TAG = "tag"
private const val FILTER_KIND_PUBLISHER = "publisher"
private const val FILTER_KIND_GENRE = "genre"
private const val FILTER_KIND_NOTABLE = "notable"

/** Series detail, mirrors KMReader SeriesDetailView / SeriesDetailContentView:
 *  Hero (large cover + centered info) → DetailActionCard (book-count row + continue-reading capsule +
 *  download area) → timestamp → summary → left-aligned genre/tag/link chips → alternate titles → book list
 *  (filter/sort/layout/multi-select row). On wide screens (≥960dp) the left info rail sits beside the book
 *  list, matching SeriesDetailWideLayoutView. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    series: Series,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    onBrowseFilter: (String) -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onOpenCollection: (Int) -> Unit = {},
    onReadIncognito: (Book) -> Unit = {},
    /** Navigation after a successful series delete (closes the detail screen). */
    onDeleted: () -> Unit = {},
    viewModel: SeriesDetailViewModel = hiltViewModel(),
) {
    // Real data: on entry, resolve the DTO id through the registry and load the series and its books;
    // fall back to the demo data when there is no session or the series is not registered
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(series.id) { viewModel.load(series.id) }
    // Instant local feedback for "mark as read" on the demo data (no session)
    var demoMarkedRead by remember(series.id) { mutableStateOf(false) }
    val collections by viewModel.collections.collectAsState()
    val readLists by viewModel.readLists.collectAsState()
    val scope = rememberCoroutineScope()
    val displaySeries = (state.series ?: series).let {
        if (demoMarkedRead && state.series == null) it.copy(progress = 1f, unreadCount = 0) else it
    }
    val rawBooks = state.books ?: emptyList()
    val books = if (demoMarkedRead && state.books == null) rawBooks.map { it.copy(progress = 1f) } else rawBooks
    // reading target: first in-progress, else first unread, else first book (mirrors SeriesContinueReadingResolver)
    val continueBook = books.firstOrNull { it.progress != null && it.progress < 1f }
        ?: books.firstOrNull { it.progress == null }
        ?: books.firstOrNull()
    // Mirrors seriesDetailLayout: list / medium grid / large grid, list by default, persisted per page
    val (booksLayout, setBooksLayout) = rememberPersistentBrowseLayout("seriesDetail", BrowseLayout.List)
    // Filter state (mirrors BookBrowseOptions; the real implementation persists per page via AppStorage)
    var browseOptions by remember { mutableStateOf(BookBrowseOptions()) }
    // Selection state: null = selection mode off; once on, tapping a book toggles it
    // (mirrors BooksListViewForSeries selection mode)
    var selectedIds by remember { mutableStateOf<Set<Int>?>(null) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var showKomfSheet by remember { mutableStateOf(false) }
    var komfResetConfirm by remember { mutableStateOf(false) }
    var deleteSeriesConfirm by remember { mutableStateOf(false) }
    var showTrackerSheet by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var addToCollectionOpen by remember { mutableStateOf(false) }
    var addSelectedToReadListOpen by remember { mutableStateOf(false) }
    var deleteSelectedOpen by remember { mutableStateOf(false) }
    // Book long-press menu (mirrors iOS: long-pressing in the grid shows BookContextMenu; entering
    // multi-select goes through the toolbar button)
    var menuBook by remember { mutableStateOf<Book?>(null) }
    var addMenuBookToReadListOpen by remember { mutableStateOf(false) }
    // Book metadata edit sheet and delete confirmation (reached from the long-press menu)
    var editBook by remember { mutableStateOf<Book?>(null) }
    var deleteBookConfirm by remember { mutableStateOf<Book?>(null) }
    val context = LocalContext.current
    // Download state: real file scan result (stableId -> complete) + demo local feedback
    val downloadedMap by viewModel.downloaded.collectAsState()
    var demoDownloaded by remember { mutableStateOf(false) }
    val seriesDownloaded = (
        books.isNotEmpty() && downloadedMap.isNotEmpty() &&
            books.all { downloadedMap[it.id] == true }
        ) || demoDownloaded
    val onDownloadSeries = {
        if (viewModel.downloadSeries()) {
            // Already queued; once finished, downloadedMap is refreshed by the file scan
        } else {
            demoDownloaded = true
        }
    }

    // In-page snackbar (shared by komf jobs and multi-select failure notices)
    val snackbarHostState = remember { SnackbarHostState() }

    // Long-pressing a book while multi-select is off enters multi-select with that book
    // (mirrors the original long-press gesture)
    val toggleSelect: (Book) -> Unit = { book ->
        selectedIds = selectedIds?.let { if (book.id in it) it - book.id else it + book.id }
            ?: setOf(book.id)
    }

    val selectionFailure: suspend (String) -> Unit = { action ->
        snackbarHostState.showSnackbar(
            context.getString(R.string.detail_selection_no_data, action),
        )
    }
    val onSelectedMarkRead: (Boolean) -> Unit = { read ->
        val ids = selectedIds.orEmpty()
        if (ids.isNotEmpty()) {
            if (viewModel.markBooksRead(ids, read)) {
                selectedIds = null
            } else {
                scope.launch { selectionFailure(if (read) context.getString(R.string.book_mark_read) else context.getString(R.string.book_mark_unread)) }
            }
        }
    }
    val onSelectedDownload: () -> Unit = {
        val ids = selectedIds.orEmpty()
        if (ids.isNotEmpty()) {
            if (viewModel.downloadBooks(ids)) {
                selectedIds = null
            } else {
                scope.launch { selectionFailure(context.getString(R.string.common_download)) }
            }
        }
    }
    val onSelectedAddToReadList: () -> Unit = {
        if (!selectedIds.isNullOrEmpty()) {
            viewModel.ensureReadListsLoaded()
            addSelectedToReadListOpen = true
        }
    }
    val onSelectedDelete: () -> Unit = {
        if (!selectedIds.isNullOrEmpty()) deleteSelectedOpen = true
    }

    // One-shot komf job notifications (in-page snackbar, mirrors ErrorManager notifications)
    LaunchedEffect(viewModel) {
        viewModel.komfEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    // The frosted bar floats inside the page box instead of occupying a Scaffold slot, so the page
    // scrolls underneath it and the glass fades in with the scroll offset (same as the tab roots).
    val barHazeState = rememberHazeState()
    val railScrollState = rememberScrollState()
    val narrowScrollState = rememberScrollState()
    val booksListState = rememberLazyListState()
    val barProgress = rememberGlassBarProgress(
        firstVisibleItemIndex = {
            if (booksListState.firstVisibleItemIndex > 0 || railScrollState.value > 0 || narrowScrollState.value > 0) {
                1
            } else {
                0
            }
        },
        firstVisibleItemScrollOffset = {
            // The wide layout scrolls the rail and the book list independently; both have to fade the
            // glass in, so fold the list offset in as well instead of only the rail's scroll state.
            maxOf(railScrollState.value, narrowScrollState.value, booksListState.firstVisibleItemScrollOffset)
        },
    )

    // The content box has to start under the status bar: if the Scaffold insets it instead, the
    // scrollers stop at the status bar and the bar has no backdrop for that strip (hard seam).
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = glassPageContentWindowInsets(),
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            GlassTopBar(
                title = displaySeries.title,
                progress = barProgress,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
                hazeState = barHazeState,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // Mirrors the original detail toolbar's single more menu (edit/komf/reset are admin-only)
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                        }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_share)) },
                                onClick = {
                                    moreOpen = false
                                    val url = viewModel.seriesWebUrl()
                                    if (url != null) {
                                        shareText(context, url)
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.detail_share_failed),
                                            )
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.series_edit_title)) },
                                onClick = {
                                    moreOpen = false
                                    showEditSheet = true
                                },
                            )
                            // Analyze/refresh/delete: admin-gated (mirrors iOS current.isAdmin)
                            if (state.isAdmin) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.book_analyze)) },
                                    onClick = {
                                        moreOpen = false
                                        viewModel.analyzeSeries { ok ->
                                            if (!ok) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.detail_analyze_failed),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.book_refresh_metadata)) },
                                    onClick = {
                                        moreOpen = false
                                        viewModel.refreshSeriesMetadata { ok ->
                                            if (!ok) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.detail_refresh_failed),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            // Three komf entries: a kmrs feature + admin-gated (mirrors KomfIntegrationStore)
                            if (state.komfAvailable) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.komf_identify_menu)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                                    },
                                    onClick = {
                                        moreOpen = false
                                        showKomfSheet = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.komf_match)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Sync, contentDescription = null)
                                    },
                                    onClick = {
                                        moreOpen = false
                                        viewModel.komfMatch()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.komf_reset)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.RestartAlt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        moreOpen = false
                                        komfResetConfirm = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.series_add_to_collection)) },
                                onClick = {
                                    moreOpen = false
                                    viewModel.ensureCollectionsLoaded()
                                    addToCollectionOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.book_mark_read)) },
                                onClick = {
                                    moreOpen = false
                                    // Real data goes through the API (updated inside the VM);
                                    // demo data gets local feedback
                                    if (!viewModel.markSeriesRead()) demoMarkedRead = true
                                },
                            )
                            if (state.isAdmin) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.detail_delete_menu),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        moreOpen = false
                                        deleteSeriesConfirm = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
            if (maxWidth >= WideLayoutBreakpoint) {
                // Wide layout: info rail on the left + book list on the right, both scrolling independently
                // Rail width = max(available width × 0.382, 340), mirroring the original DetailWideLayoutView
                // golden-ratio minor part (width / φ² ≈ 38.2%, with 340 as the minimum usable rail)
                val railWidth = maxOf(maxWidth * 0.382f, 340.dp)
                // One haze source over the whole split instead of one per pane: the bar's backdrop has to
                // be the full width of the page, and a scroller that is only as tall as its own pane
                // leaves the strip behind the bar empty whenever the other pane is the one scrolling.
                Row(modifier = Modifier.fillMaxSize().hazeSource(barHazeState)) {
                    Column(
                        modifier = Modifier
                            .width(railWidth)
                            .fillMaxSize()
                            .verticalScroll(railScrollState)
                            .padding(
                                start = 24.dp,
                                end = 24.dp,
                                top = glassTopBarTopPadding() + 16.dp,
                                bottom = 16.dp,
                            ),
                    ) {
                        DetailHero(
                            series = displaySeries,
                            centered = true,
                            onBrowseFilter = onBrowseFilter,
                        )
                        DetailActionCard(
                            series = displaySeries,
                            continueBook = continueBook,
                            centered = true,
                            downloaded = seriesDownloaded,
                            onRead = { continueBook?.let(onRead) },
                            onDownload = onDownloadSeries,
                        )
                        DetailSummary(displaySeries)
                        DetailChips(series = displaySeries, onBrowseFilter = onBrowseFilter)
                        DetailMembershipChips(state.collections, onOpen = onOpenCollection)
                        if (state.hasSession && state.trackerSupported) {
                            TrackerSection(
                                links = state.trackerLinks,
                                onManage = { showTrackerSheet = true },
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        state = booksListState,
                        contentPadding = PaddingValues(
                            top = glassTopBarTopPadding() + 8.dp,
                            bottom = 8.dp,
                        ),
                    ) {
                        item(key = "books-toolbar") {
                            BooksToolbar(
                                options = browseOptions,
                                layout = booksLayout,
                                onOptionsChange = { browseOptions = it },
                                onOpenOptions = { showOptionsSheet = true },
                                onCycleLayout = { setBooksLayout(booksLayout.next) },
                                selection = selectedIds,
                                totalCount = books.size,
                                onEnterSelection = { selectedIds = emptySet() },
                                onToggleSelectAll = {
                                    selectedIds = if (selectedIds?.size == books.size) emptySet() else books.map { it.id }.toSet()
                                },
                                onDoneSelection = { selectedIds = null },
                                onMarkRead = { onSelectedMarkRead(true) },
                                onMarkUnread = { onSelectedMarkRead(false) },
                                onDownload = onSelectedDownload,
                                onAddToReadList = onSelectedAddToReadList,
                                onDelete = onSelectedDelete,
                            )
                        }
                        item(key = "books") {
                            BooksSection(
                                books = books,
                                layout = booksLayout,
                                onReadBook = onRead,
                                onBookLongClick = { menuBook = it },
                                selection = selectedIds,
                                onToggleSelect = toggleSelect,
                            )
                        }
                    }
                }
            } else {
                // Phone: hero + action card stay centered (same as the wide rail, and the same as a tablet in
                // portrait); only the metadata block under the summary — notable entities / genres / tags /
                // links / collections — starts at the content edge so it lines up with the book list below
                // instead of floating in the middle of the page.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(narrowScrollState)
                        .hazeSource(barHazeState)
                        .padding(top = glassTopBarTopPadding() + 16.dp, bottom = 16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DetailHero(
                            series = displaySeries,
                            centered = true,
                            onBrowseFilter = onBrowseFilter,
                        )
                        DetailActionCard(
                            series = displaySeries,
                            continueBook = continueBook,
                            centered = true,
                            downloaded = seriesDownloaded,
                            onRead = { continueBook?.let(onRead) },
                            onDownload = onDownloadSeries,
                        )
                        DetailSummary(displaySeries)
                        // Left aligned even though the hero above is centered: this block reads as the start of
                        // the list section and continues into the book list below.
                        DetailChips(displaySeries, centered = false, onBrowseFilter = onBrowseFilter)
                        DetailMembershipChips(state.collections, centered = false, onOpen = onOpenCollection)
                        if (state.hasSession && state.trackerSupported) {
                            TrackerSection(
                                links = state.trackerLinks,
                                onManage = { showTrackerSheet = true },
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    BooksToolbar(
                        options = browseOptions,
                        layout = booksLayout,
                        onOptionsChange = { browseOptions = it },
                        onOpenOptions = { showOptionsSheet = true },
                        onCycleLayout = { setBooksLayout(booksLayout.next) },
                        selection = selectedIds,
                        totalCount = books.size,
                        onEnterSelection = { selectedIds = emptySet() },
                        onToggleSelectAll = {
                            selectedIds = if (selectedIds?.size == books.size) emptySet() else books.map { it.id }.toSet()
                        },
                        onDoneSelection = { selectedIds = null },
                        onMarkRead = { onSelectedMarkRead(true) },
                        onMarkUnread = { onSelectedMarkRead(false) },
                        onDownload = onSelectedDownload,
                        onAddToReadList = onSelectedAddToReadList,
                        onDelete = onSelectedDelete,
                    )
                    BooksSection(
                        books = books,
                        layout = booksLayout,
                        onReadBook = onRead,
                        onBookLongClick = { menuBook = it },
                        selection = selectedIds,
                        onToggleSelect = toggleSelect,
                    )
                }
            }
        }
    }

    if (showOptionsSheet) {
        BrowseOptionsSheet(
            initialOptions = browseOptions,
            onConfirm = { browseOptions = it },
            onDismiss = { showOptionsSheet = false },
            suggestionSeriesStableId = series.id,
        )
    }
    if (showEditSheet) {
        var editSaving by remember { mutableStateOf(false) }
        SeriesEditSheet(
            series = displaySeries,
            values = state.editable,
            saving = editSaving,
            onSave = { dto ->
                editSaving = true
                viewModel.saveMetadata(dto) { ok ->
                    editSaving = false
                    if (ok) showEditSheet = false
                }
            },
            onDismiss = { if (!editSaving) showEditSheet = false },
        )
    }
    if (showKomfSheet) {
        KomfIdentifySheet(
            series = displaySeries,
            viewModel = viewModel,
            onDismiss = { showKomfSheet = false },
        )
    }
    if (komfResetConfirm) {
        AlertDialog(
            onDismissRequest = { komfResetConfirm = false },
            title = { Text(stringResource(R.string.komf_reset)) },
            text = { Text(stringResource(R.string.komf_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        komfResetConfirm = false
                        viewModel.komfReset()
                    },
                ) {
                    Text(stringResource(R.string.common_reset), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { komfResetConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (deleteSeriesConfirm) {
        AlertDialog(
            onDismissRequest = { deleteSeriesConfirm = false },
            title = { Text(stringResource(R.string.series_delete_files_title)) },
            text = {
                Text(stringResource(R.string.series_delete_files_message, displaySeries.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteSeriesConfirm = false
                        viewModel.deleteSeries { ok ->
                            if (ok) {
                                onDeleted()
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.detail_delete_failed),
                                    )
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSeriesConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (showTrackerSheet) {
        // TrackerSeriesSheet wires up its own real data via Hilt (TrackerSeriesViewModel)
        TrackerSeriesSheet(
            series = displaySeries,
            alternateTitles = state.editable?.alternateTitles.orEmpty().map { it.second },
            onDismiss = {
                showTrackerSheet = false
                viewModel.refreshTrackerLinks()
            },
        )
    }
    // "Add to collection" picker: real collection data, calls the API after selection (empty without a session)
    if (addToCollectionOpen) {
        AddToListSheet(
            collections = collections ?: emptyList(),
            readLists = emptyList(),
            onCreateReadList = viewModel::createReadListEntry,
            onCreateCollection = viewModel::createCollectionEntry,
            onSelect = { entry ->
                viewModel.addToCollection(entry)
                addToCollectionOpen = false
            },
            onDismiss = { addToCollectionOpen = false },
        )
    }
    if (addSelectedToReadListOpen) {
        val ids = selectedIds.orEmpty()
        AddToListSheet(
            collections = emptyList(),
            readLists = readLists ?: emptyList(),
            onCreateReadList = viewModel::createReadListEntry,
            onCreateCollection = viewModel::createCollectionEntry,
            onSelect = { entry ->
                if (viewModel.addBooksToReadList(entry, ids)) {
                    selectedIds = null
                } else {
                    scope.launch { selectionFailure(context.getString(R.string.book_add_to_readlist)) }
                }
                addSelectedToReadListOpen = false
            },
            onDismiss = { addSelectedToReadListOpen = false },
        )
    }
    // Book long-press menu (mirrors iOS: long-pressing in the grid shows BookContextMenu; the series
    // detail screen has no "open series" navigation)
    menuBook?.let { target ->
        ContextMenuSheet(
            items = bookContextMenuItems(
                hasStarted = target.progress != null,
                completed = target.progress == 1f,
                downloaded = downloadedMap[target.id] == true,
                onRead = { onRead(target) },
                onReadIncognito = { onReadIncognito(target) },
                onOpenDetails = { onOpenBook(target) },
                onMarkRead = { viewModel.markBooksRead(setOf(target.id), true) },
                onMarkUnread = { viewModel.markBooksRead(setOf(target.id), false) },
                onDownload = { viewModel.downloadBooks(setOf(target.id)) },
                onAddToReadList = {
                    viewModel.ensureReadListsLoaded()
                    addMenuBookToReadListOpen = true
                },
                onEdit = { editBook = target },
                onAnalyze = {
                    viewModel.analyzeBook(target.id) { ok ->
                        if (!ok) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.detail_analyze_failed),
                                )
                            }
                        }
                    }
                },
                onRefresh = {
                    viewModel.refreshBookMetadata(target.id) { ok ->
                        if (!ok) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.detail_refresh_failed),
                                )
                            }
                        }
                    }
                },
                onShare = {
                    val url = viewModel.bookWebUrl(target.id)
                    if (url != null) {
                        shareText(context, url)
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.detail_share_failed),
                            )
                        }
                    }
                },
                onDelete = { deleteBookConfirm = target },
            ),
            onDismiss = { menuBook = null },
        )
    }
    // Book metadata edit sheet (meta is null: diff against the title baseline only)
    editBook?.let { book ->
        var editSaving by remember { mutableStateOf(false) }
        BookEditSheet(
            book = book,
            meta = null,
            saving = editSaving,
            onSave = { draft ->
                editSaving = true
                viewModel.saveBookMetadata(book.id, book.title, draft) { ok ->
                    editSaving = false
                    if (ok) {
                        editBook = null
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.detail_save_failed),
                            )
                        }
                    }
                }
            },
            onDismiss = { editBook = null },
        )
    }
    // Delete book files confirmation (red button; on Komga deleting a book deletes its files, irreversible)
    deleteBookConfirm?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteBookConfirm = null },
            title = { Text(stringResource(R.string.book_delete_files_title)) },
            text = { Text(stringResource(R.string.book_delete_files_message, book.title)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteBookConfirm = null
                    viewModel.deleteBook(book.id) { ok ->
                        if (!ok) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.detail_delete_failed),
                                )
                            }
                        }
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteBookConfirm = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (addMenuBookToReadListOpen) {
        menuBook?.let { target ->
            AddToListSheet(
                collections = emptyList(),
                readLists = readLists ?: emptyList(),
                onCreateReadList = viewModel::createReadListEntry,
                onCreateCollection = viewModel::createCollectionEntry,
                onSelect = { entry ->
                    if (!viewModel.addBooksToReadList(entry, setOf(target.id))) {
                        scope.launch { selectionFailure(context.getString(R.string.book_add_to_readlist)) }
                    }
                    addMenuBookToReadListOpen = false
                },
                onDismiss = { addMenuBookToReadListOpen = false },
            )
        }
    }
    if (deleteSelectedOpen) {
        val ids = selectedIds.orEmpty()
        ConfirmDeleteDialog(
            title = stringResource(R.string.series_delete_selected_title),
            message = pluralStringResource(
                R.plurals.series_delete_selected_message,
                ids.size,
                ids.size,
            ),
            onConfirm = {
                viewModel.deleteBooks(ids) { ok ->
                    if (ok) {
                        selectedIds = null
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.detail_delete_failed),
                            )
                        }
                    }
                }
            },
            onDismiss = { deleteSelectedOpen = false },
        )
    }
}

/**
 * Hero: large cover on top with the info below it, mirrors DetailHeroView + SeriesHeroInfoView.
 * [centered] is true on phones and in the wide info rail (the default look); false keeps a left-aligned
 * variant where cover, title, creator chips and meta rows all align to the content edge.
 */
@Composable
private fun DetailHero(
    series: Series,
    centered: Boolean,
    onBrowseFilter: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        CoverArt(
            title = series.title,
            hue = series.hue,
            stableId = series.id,
            modifier = Modifier
                .width(HeroCoverWidth)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            series.ageRating?.let { rating ->
                Text(
                    text = " $rating+",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // SeriesHeroInfoView.creatorItems: publisher (building.2) + authors sorted by role (role.icon),
        // all tappable and navigating to the series browse page filtered by that metadata;
        // long flows collapse behind "+N" and expand in place (limit 4).
        // The chip label is the name; the pair's first element is the internal route token, so the
        // emitted route stays language independent ("author · X", "publisher · X").
        val creatorChips = buildList {
            series.publisher?.let { add(Icons.Outlined.Business to (FILTER_KIND_PUBLISHER to it)) }
            series.creators.sortedBy { it.role.ordinal }.forEach { creator ->
                add(creator.role.icon() to (FILTER_KIND_AUTHOR to creator.name))
            }
        }
        CollapsibleMetaChipFlow(
            items = creatorChips,
            limit = 4,
            centered = centered,
            onClick = { (kind, value) -> onBrowseFilter("$kind · $value") },
        )
        Spacer(Modifier.height(12.dp))
        // DetailHeroMetadataGroup: the centered layout (phones and the wide rail) uses a horizontal wrapping
        // flow (wrapping only when a row does not fit), while the left-aligned variant puts each meta item on
        // its own row.
        if (centered) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeroMetaItems(series)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeroMetaItems(series)
            }
        }
    }
}

@Composable
private fun HeroMetaItems(series: Series) {
    val endedLabel = stringResource(R.string.series_status_ended)
    series.releaseYear?.let {
        MetaRow(
            icon = Icons.Filled.DateRange,
            text = stringResource(R.string.series_started_year, it),
        )
    }
    series.status?.let {
        MetaRow(icon = null, text = it, tint = if (it == endedLabel) Color(0xFF4CAF50) else Color(0xFFFFA726))
    }
    series.language?.let { MetaRow(icon = Icons.Filled.Public, text = it) }
    MetaRow(
        icon = Icons.AutoMirrored.Filled.FormatTextdirectionRToL,
        text = if (series.directionRtl) {
            stringResource(R.string.series_direction_rtl)
        } else {
            stringResource(R.string.series_direction_ltr)
        },
    )
}

/** Action card: rounded 16 neutral container (maxWidth 480), book-count row + continue-reading capsule + download series.
 *  Mirrors DetailActionCard + SeriesBookCountView + SeriesReadingActionButton + SeriesDownloadActionsSection. */
@Composable
private fun DetailActionCard(
    series: Series,
    continueBook: Book?,
    centered: Boolean,
    downloaded: Boolean,
    onRead: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .widthIn(max = ActionCardMaxWidth)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, ActionCardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = series.bookCountLineText(),
                style = MaterialTheme.typography.titleSmall,
            )
            // Mirrors the iOS SeriesBookCountView status line; per the user's request the partially-read
            // branch is phrased with the read count (iOS says "N unread"), never showing the word "unread".
            val allReadLabel = stringResource(R.string.series_status_all_read)
            val readingLabel = stringResource(R.string.series_status_reading)
            val statusLabel = when {
                series.unreadCount > 0 && series.unreadCount < series.bookCount ->
                    stringResource(R.string.series_status_partial_read, series.bookCount - series.unreadCount)
                series.progress != null && series.progress < 1f -> readingLabel
                series.unreadCount == 0 && series.bookCount > 0 -> allReadLabel
                else -> stringResource(R.string.common_unread)
            }
            Text(
                text = "  ·  $statusLabel",
                style = MaterialTheme.typography.bodySmall,
                color = when (statusLabel) {
                    allReadLabel -> Color(0xFF4CAF50)
                    readingLabel -> Color(0xFFFFA726)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (continueBook != null) {
            ReadingActionButton(continueBook = continueBook, onRead = onRead)
        }
        // Secondary button mirrors iOS optimizedControlSize(.mini): mini capsule (28 high, 13sp text, small icon)
        OutlinedButton(
            onClick = onDownload,
            enabled = !downloaded,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Icon(
                if (downloaded) Icons.Outlined.CheckCircle else Icons.Filled.Download,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (downloaded) {
                    stringResource(R.string.book_downloaded)
                } else {
                    stringResource(R.string.series_download)
                },
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

/** Reading capsule: primary-colored, two lines — the action line at 15sp semibold on top, the target book line at 12sp/0.85 below.
 *  Mirrors SeriesReadingActionButton (label=.subheadline.semibold, detail=.caption, icon .callout). */
@Composable
private fun ReadingActionButton(continueBook: Book, onRead: () -> Unit) {
    // caption: resume with page/percent when in progress, otherwise start (mirrors readLabel)
    val caption = continueBook.progress?.let { p ->
        if (p > 0f && p < 1f) {
            val page = (p * continueBook.pageCount).toInt().coerceAtLeast(1)
            val percent = (p * 100).toInt()
            stringResource(R.string.series_resume_reading_progress, page, percent)
        } else {
            stringResource(R.string.book_start_reading)
        }
    } ?: stringResource(R.string.book_start_reading)
    Button(
        onClick = onRead,
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = caption,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "#${continueBook.number} - ${continueBook.title}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailSummary(series: Series) {
    series.summary?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** Notable entity / genre / tag / link chips, mirrors SeriesDetailChipsView.
 *  Notable entities (character person / team person.2 / location mappin), genres (theatermasks) and tags (tag) are
 *  tappable and jump to the series browse page filtered by that metadata; links open an external URL; long flows
 *  collapse behind "+N" and expand in place (limit 6). */
@Composable
private fun DetailChips(
    series: Series,
    centered: Boolean = false,
    onBrowseFilter: (String) -> Unit,
) {
    if (series.notableEntities.isEmpty() && series.genres.isEmpty() &&
        series.tags.isEmpty() && series.links.isEmpty()
    ) return
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            // Fill the width when left aligned so the flows start at the content edge even inside a centered
            // parent column (the phone hero above this block is centered).
            .then(if (centered) Modifier else Modifier.fillMaxWidth())
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (series.notableEntities.isNotEmpty()) {
            CollapsibleMetaChipFlow(
                items = series.notableEntities.map { e ->
                    notableEntityIcon(e.kind) to (e.kind to e.name)
                },
                limit = 6,
                centered = centered,
                // Three-part route format "notable · kind · name" (same as the book detail; LibraryScreen
                // parses by prefix and localizes the kind for display)
                onClick = { (kind, value) -> onBrowseFilter("$FILTER_KIND_NOTABLE · $kind · $value") },
            )
        }
        if (series.genres.isNotEmpty()) {
            CollapsibleMetaChipFlow(
                items = series.genres.map { Icons.Outlined.TheaterComedy to (FILTER_KIND_GENRE to it) },
                limit = 6,
                centered = centered,
                onClick = { (kind, value) -> onBrowseFilter("$kind · $value") },
            )
        }
        if (series.tags.isNotEmpty()) {
            CollapsibleMetaChipFlow(
                items = series.tags.map { Icons.Outlined.Sell to (FILTER_KIND_TAG to it) },
                limit = 6,
                centered = centered,
                onClick = { (kind, value) -> onBrowseFilter("$kind · $value") },
            )
        }
        if (series.links.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(
                    8.dp,
                    if (centered) Alignment.CenterHorizontally else Alignment.Start,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                series.links.forEach { (label, url) ->
                    MetaChip(
                        text = label,
                        icon = Icons.Outlined.Link,
                        onClick = { uriHandler.openUri(url) },
                    )
                }
            }
        }
    }
}

/** Membership collection chips, mirrors SeriesDetailCollectionsView. */
@Composable
private fun DetailMembershipChips(
    entries: List<ListEntry>,
    centered: Boolean = false,
    onOpen: (Int) -> Unit,
) {
    if (entries.isEmpty()) return
    Column(
        modifier = Modifier
            // Same as DetailChips: fill the width when left aligned so the title and chips sit at the edge of a
            // centered parent column.
            .then(if (centered) Modifier else Modifier.fillMaxWidth())
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.browse_scope_collections),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(
                8.dp,
                if (centered) Alignment.CenterHorizontally else Alignment.Start,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                MetaChip(
                    text = entry.title,
                    icon = Icons.Outlined.CollectionsBookmark,
                    onClick = { onOpen(entry.id) },
                )
            }
        }
    }
}

/** Collapsible metadata chip flow, mirrors DetailChipFlow (the "+N" chip expands in place). */
@Composable
private fun CollapsibleMetaChipFlow(
    items: List<Pair<ImageVector, Pair<String, String>>>,
    limit: Int,
    centered: Boolean = false,
    onClick: (Pair<String, String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(
            8.dp,
            if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val visible = if (expanded) items else items.take(limit)
        visible.forEach { (icon, target) ->
            MetaChip(text = target.second, icon = icon, onClick = { onClick(target) })
        }
        if (!expanded && items.size > limit) {
            MetaChip(text = "+${items.size - limit}", onClick = { expanded = true })
        }
    }
}

/** Author role icon, mirroring AuthorRole.icon (writer=pencil / penciller=paintbrush / …). */
private fun AuthorRole.icon(): ImageVector = when (this) {
    AuthorRole.Writer -> Icons.Outlined.Create
    AuthorRole.Penciller -> Icons.Outlined.Brush
    AuthorRole.Inker -> Icons.Outlined.EditNote
    AuthorRole.Colorist -> Icons.Outlined.Palette
    AuthorRole.Letterer -> Icons.Outlined.TextFormat
    AuthorRole.Cover -> Icons.Outlined.Photo
    AuthorRole.Editor -> Icons.Outlined.ContentCut
    AuthorRole.Translator -> Icons.Filled.Public
    AuthorRole.Custom -> Icons.Outlined.Person
}

/** Shared notable-entity icon/label definition: icon → ui/components/NotableEntityUi.kt,
 *  kindLabel → data/SampleData.kt (custom kinds are shown as-is, mirroring iOS kindDisplayName). */

/** Books section: list rows or an adaptive grid (medium grid card 108 / large grid 160, mirroring LayoutConfig).
 *  Tapping reads the book (mirroring the original BookRowView/BookCardView, where tap = read);
 *  long-pressing opens the book menu (mirroring the original grid long-press gesture); in selection mode a tap toggles selection. */
@Composable
private fun BooksSection(
    books: List<Book>,
    layout: BrowseLayout,
    onReadBook: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    selection: Set<Int>?,
    onToggleSelect: (Book) -> Unit,
) {
    when (layout) {
        BrowseLayout.List -> Column {
            books.forEach { book ->
                if (selection == null) {
                    BookListRow(
                        book = book,
                        onClick = { onReadBook(book) },
                        onLongClick = { onBookLongClick(book) },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BookListRow(
                            book = book,
                            onClick = { onToggleSelect(book) },
                            modifier = Modifier.weight(1f),
                        )
                        Checkbox(
                            checked = book.id in selection,
                            onCheckedChange = { onToggleSelect(book) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        }

        BrowseLayout.Grid, BrowseLayout.LargeGrid -> BooksGrid(
            books = books,
            large = layout == BrowseLayout.LargeGrid,
            onReadBook = onReadBook,
            onBookLongClick = onBookLongClick,
            selection = selection,
            onToggleSelect = onToggleSelect,
        )
    }
}

@Composable
private fun BooksGrid(
    books: List<Book>,
    large: Boolean,
    onReadBook: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    selection: Set<Int>?,
    onToggleSelect: (Book) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        val minCell = if (large) 160.dp else 108.dp
        val columns = maxOf(2, ((maxWidth + 12.dp) / (minCell + 12.dp)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            books.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { book ->
                        Box(modifier = Modifier.weight(1f)) {
                            val inSelection = selection != null
                            BookGridCard(
                                book = book,
                                onClick = { if (inSelection) onToggleSelect(book) else onReadBook(book) },
                                onLongClick = { if (!inSelection) onBookLongClick(book) },
                            )
                            if (selection != null && book.id in selection) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = stringResource(R.string.detail_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(22.dp)
                                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                                )
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Books toolbar: in selection mode it shows the SelectionToolbar (mirroring SelectionActionsToolbar),
 *  otherwise the filter chip flow (layout / presets / sort / active conditions, mirroring BookFilterView) plus the enter-selection action. */
@Composable
private fun BooksToolbar(
    options: BookBrowseOptions,
    layout: BrowseLayout,
    onOptionsChange: (BookBrowseOptions) -> Unit,
    onOpenOptions: () -> Unit,
    onCycleLayout: () -> Unit,
    selection: Set<Int>?,
    totalCount: Int,
    onEnterSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onDoneSelection: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDownload: () -> Unit,
    onAddToReadList: () -> Unit,
    onDelete: () -> Unit,
) {
    if (selection != null) {
        SelectionToolbar(
            selectedCount = selection.size,
            totalCount = totalCount,
            onSelectAll = onToggleSelectAll,
            onMarkRead = onMarkRead,
            onMarkUnread = onMarkUnread,
            onDownload = onDownload,
            onAddToReadList = onAddToReadList,
            onDelete = onDelete,
            onDone = onDoneSelection,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BrowseFilterChipFlow(
                options = options,
                layout = layout,
                onLayoutChange = { onCycleLayout() },
                onOpenOptions = onOpenOptions,
                onApplyPreset = { onOptionsChange(it.options) },
            )
        }
        IconButton(onClick = onEnterSelection) {
            Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.detail_multi_select))
        }
    }
}

@Composable
private fun MetaChip(text: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MetaRow(icon: ImageVector?, text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(tint, CircleShape),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}
