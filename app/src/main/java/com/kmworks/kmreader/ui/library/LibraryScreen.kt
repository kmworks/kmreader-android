package com.kmworks.kmreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.EmptyState
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BookListRow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.MetaChip
import com.kmworks.kmreader.ui.components.notableEntityIcon
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
import com.kmworks.kmreader.ui.components.PullRefreshBox
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.hasScrolled
import com.kmworks.kmreader.ui.components.rememberBrowseFilterSuggestions
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.LibraryPinSheet
import com.kmworks.kmreader.ui.components.SeriesGridCard
import com.kmworks.kmreader.ui.components.SeriesListRow
import com.kmworks.kmreader.ui.components.libraryScopeLabel
import com.kmworks.kmreader.ui.components.libraryMetricsCaption
import com.kmworks.kmreader.ui.components.rememberPersistentBrowseLayout
import com.kmworks.kmreader.ui.components.bookContextMenuItems
import com.kmworks.kmreader.ui.components.seriesContextMenuItems
import com.kmworks.kmreader.ui.detail.BookEditSheet
import com.kmworks.kmreader.ui.detail.KomfSeriesContextDialogs
import com.kmworks.kmreader.ui.detail.KomfSeriesViewModel
import com.kmworks.kmreader.ui.detail.SeriesEditSheet
import com.kmworks.kmreader.ui.lists.AddToListSheet
import com.kmworks.kmreader.ui.util.shareText
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Library: content-first browsing (Apple Books style). Top = content type chips (series/books,
 *  with counts), below it the scope summary (scope title + size caption); the scope
 *  (all/pinned/single library) is switched in place from the toolbar menu and defaults to pinned.
 *  Mirrors KMReader LibraryBrowseView + BrowseContentView. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    // Set when this list was pushed by a metadata chip: the page opens already filtered by that route and
    // keeps its own view model, so the library tab's own conditions are never touched.
    initialFilter: LibraryFilterRequest? = null,
    // Pops this page; the header shows a back arrow exactly when the page was pushed (null on the tab).
    onBack: (() -> Unit)? = null,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onVisitSeries: (Book) -> Unit = {},
    onReadBook: (Book, incognito: Boolean) -> Unit = { _, _ -> },
    // A pushed filtered list gets a page-scoped view model from the AppRoot; the library tab keeps its own
    viewModel: LibraryViewModel = hiltViewModel(),
    komfViewModel: KomfSeriesViewModel = hiltViewModel(),
) {
    var contentType by remember { mutableStateOf(initialFilter?.contentType ?: LibraryContentType.Series) }
    // The route restricting this page (null = none). The applied condition lives in the view model, so it
    // survives this screen leaving composition and can always be taken back -- both when the page is popped
    // and when the chip is cleared.
    var activeFilter by remember { mutableStateOf(initialFilter) }
    LaunchedEffect(initialFilter?.route) {
        val request = initialFilter ?: return@LaunchedEffect
        viewModel.applyMetadataRoute(request.route)
        contentType = request.contentType
        activeFilter = request
    }
    // Layout persisted per content type (mirrors iOS seriesBrowseLayout / bookBrowseLayout,
    // large grid by default)
    val (seriesLayout, setSeriesLayout) = rememberPersistentBrowseLayout("librarySeries", BrowseLayout.LargeGrid)
    val (booksLayout, setBooksLayout) = rememberPersistentBrowseLayout("libraryBooks", BrowseLayout.LargeGrid)
    val layout = if (contentType == LibraryContentType.Series) seriesLayout else booksLayout
    val setLayout = if (contentType == LibraryContentType.Series) setSeriesLayout else setBooksLayout
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var showPinSheet by remember { mutableStateOf(false) }
    // Target of the "Add to Collection/Read List" sheet
    var addTarget by remember { mutableStateOf<Any?>(null) }
    var addToCollectionOpen by remember { mutableStateOf(false) }
    var addToReadListOpen by remember { mutableStateOf(false) }
    // komf identify sheet + reset confirmation targets (series context menu)
    var komfIdentifyTarget by remember { mutableStateOf<Series?>(null) }
    var komfResetTarget by remember { mutableStateOf<Series?>(null) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    // Long-press context menu target (mirrors the original card contextMenu)
    var menuSeries by remember { mutableStateOf<Series?>(null) }
    var menuBook by remember { mutableStateOf<Book?>(null) }
    // Edit/delete sheet targets (entered from the context menu)
    var editSeries by remember { mutableStateOf<Series?>(null) }
    var editBook by remember { mutableStateOf<Book?>(null) }
    var deleteSeriesTarget by remember { mutableStateOf<Series?>(null) }
    var deleteBookTarget by remember { mutableStateOf<Book?>(null) }
    // True while the pull-to-refresh control is up: its own indicator already says a reload is running,
    // so the list-wide loading bar below would only duplicate it.
    var pulling by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val state by viewModel.state.collectAsState()
    val komfState by komfViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(komfViewModel) {
        komfViewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }
    // real content only; empty state when there is no session or nothing loaded
    val series = state.series ?: emptyList()
    val allBooks = state.books ?: emptyList()
    // Display name of the current scope (mirrors iOS LibraryScopeMenu)
    val scopeLabel = libraryScopeLabel(state.scope, state.libraries)

    // Series/books keep independent scroll positions, so both grids feed the same haze state for the bar.
    val seriesGridState = rememberLazyGridState()
    val booksGridState = rememberLazyGridState()
    val gridState = if (contentType == LibraryContentType.Series) seriesGridState else booksGridState
    val seriesListState = rememberLazyListState()
    val booksListState = rememberLazyListState()
    val listState = if (contentType == LibraryContentType.Series) seriesListState else booksListState
    // A pushed filtered list starts from the top instead of wherever the library scrollers happened to rest
    // (the old content is gone, so the saved index means nothing).
    LaunchedEffect(initialFilter?.requestId) {
        if (initialFilter == null) return@LaunchedEffect
        seriesGridState.scrollToItem(0)
        booksGridState.scrollToItem(0)
        seriesListState.scrollToItem(0)
        booksListState.scrollToItem(0)
    }
    // Backdrop the bar blurs: whichever scroll container is showing feeds the haze state.
    val hazeState = rememberHazeState()
    // Load the next page when the last row comes into reach
    val gridShouldLoadMore by remember(contentType, gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    val listShouldLoadMore by remember(contentType, listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    val shouldLoadMore = if (layout == BrowseLayout.List) listShouldLoadMore else gridShouldLoadMore
    val hasMore = when (contentType) {
        LibraryContentType.Series -> state.seriesHasMore
        LibraryContentType.Books -> state.booksHasMore
    }
    // loadedCount joins the key: keep requesting pages while still at the bottom after a page was appended
    val loadedCount = if (contentType == LibraryContentType.Series) series.size else allBooks.size
    LaunchedEffect(shouldLoadMore, contentType, hasMore, loadedCount) {
        if (!shouldLoadMore || !hasMore) return@LaunchedEffect
        when (contentType) {
            LibraryContentType.Series -> viewModel.loadMoreSeries()
            LibraryContentType.Books -> viewModel.loadMoreBooks()
        }
    }

    // One compact caption row: size/series/book totals once a source answered, otherwise just the scope/library count
    val headerCaption = libraryMetricsCaption(state.scope, state.metricsByLibrary, state.metricsTotal)
        ?: if (state.hasSession) {
            pluralStringResource(
                R.plurals.library_scope_libraries,
                state.libraryCount,
                scopeLabel,
                state.libraryCount,
            )
        } else {
            scopeLabel
        }

    // Content-first page: the bar floats over the content (it reserves no layout height) and only the header rows
    // scroll away with it, so the grid starts right below the bar instead of below four stacked caption rows.
    // The page paints its own surface: it is also pushed above another page (a metadata-chip jump), and a stack
    // slot paints none of its own -- it must not, or a page that fades in (the reader out of black) would lose
    // that backdrop -- so without this the page the jump started from would show through and both would interleave.
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PullRefreshBox(
            onRefresh = { viewModel.refresh().join() },
            modifier = Modifier.fillMaxSize(),
            indicatorTopPadding = if (state.hasSession) glassTopBarTopPadding() else 0.dp,
            onRefreshingChange = { pulling = it },
        ) {
            when {
                !state.hasSession -> Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(glassTopBarTopPadding()))
                    EmptyState(
                        title = stringResource(R.string.library_offline_title),
                        subtitle = stringResource(R.string.library_offline_subtitle),
                        modifier = Modifier.weight(1f),
                    )
                }

                layout == BrowseLayout.List -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    contentPadding = PaddingValues(
                        top = glassTopBarTopPadding() + 4.dp,
                        bottom = 8.dp + LocalNavOverlaySpace.current,
                    ),
                ) {
                    if (state.loading && !pulling) {
                        item(key = "loading") {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    state.error?.let { message ->
                        item(key = "error") { LibraryErrorText(message) }
                    }
                    item(key = "library-header") {
                        LibraryHeader(
                            contentType = contentType,
                            onContentTypeChange = { contentType = it },
                            seriesCount = state.seriesTotal ?: series.size.toLong(),
                            booksCount = state.booksTotal ?: allBooks.size.toLong(),
                            caption = headerCaption,
                            activeFilter = activeFilter?.route,
                            onClearFilter = {
                                viewModel.clearMetadataRoute()
                                activeFilter = null
                            },
                            horizontalPadding = 16.dp,
                        )
                    }
                    when (contentType) {
                        LibraryContentType.Series -> items(series.size, key = { series[it].id }) { index ->
                            val item = series[index]
                            SeriesListRow(
                                series = item,
                                onClick = { onOpenSeries(item) },
                                onLongClick = { menuSeries = item },
                            )
                        }
                        LibraryContentType.Books -> items(allBooks.size, key = { allBooks[it].id }) { index ->
                            val book = allBooks[index]
                            BookListRow(
                                book = book,
                                onClick = { onReadBook(book, false) },
                                onLongClick = { menuBook = book },
                            )
                        }
                    }
                    if (hasMore) {
                        item(key = "load-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (layout == BrowseLayout.LargeGrid) 152.dp else 110.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = glassTopBarTopPadding() + 4.dp,
                        end = 16.dp,
                        bottom = 16.dp + LocalNavOverlaySpace.current,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.loading && !pulling) {
                        item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    state.error?.let { message ->
                        item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                            LibraryErrorText(message, horizontalPadding = 0.dp)
                        }
                    }
                    item(key = "library-header", span = { GridItemSpan(maxLineSpan) }) {
                        LibraryHeader(
                            contentType = contentType,
                            onContentTypeChange = { contentType = it },
                            seriesCount = state.seriesTotal ?: series.size.toLong(),
                            booksCount = state.booksTotal ?: allBooks.size.toLong(),
                            caption = headerCaption,
                            activeFilter = activeFilter?.route,
                            onClearFilter = {
                                viewModel.clearMetadataRoute()
                                activeFilter = null
                            },
                            horizontalPadding = 0.dp,
                        )
                    }
                    when (contentType) {
                        LibraryContentType.Series -> items(series, key = { it.id }) { item ->
                            SeriesGridCard(
                                series = item,
                                onClick = { onOpenSeries(item) },
                                modifier = Modifier.fillMaxWidth(),
                                onLongClick = { menuSeries = item },
                            )
                        }
                        LibraryContentType.Books -> items(allBooks, key = { it.id }) { book ->
                            BookGridCard(
                                book = book,
                                onClick = { onReadBook(book, false) },
                                modifier = Modifier.fillMaxWidth(),
                                onLongClick = { menuBook = book },
                            )
                        }
                    }
                    if (hasMore) {
                        item(
                            key = "load-more",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }

        GlassTopBar(
            title = stringResource(R.string.library_title),
            modifier = Modifier.zIndex(1f),
            // Same real backdrop blur as the dashboard/list tab roots: the scrolling grid or list feeds
            // this state, so content sliding under the bar is blurred instead of hidden by a solid sheet.
            sheetVisible = seriesGridState.hasScrolled() || booksGridState.hasScrolled() || seriesListState.hasScrolled() || booksListState.hasScrolled(),
            hazeState = hazeState,
            // Only a pushed list (a metadata-chip jump) leaves somewhere: the tab root has no predecessor.
            navigationIcon = onBack?.let { back ->
                {
                    IconButton(onClick = back) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                }
            },
            actions = {
                // The menu must live inside the same Box as its anchor button: a DropdownMenu placed directly in
                // the actions Row anchors its popup to that full-width Row and opens against the screen's left edge.
                Box {
                    TextButton(onClick = { scopeMenuOpen = true }) {
                        Text(scopeLabel)
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = stringResource(R.string.library_switch_scope),
                        )
                    }
                    DropdownMenu(
                        expanded = scopeMenuOpen,
                        onDismissRequest = { scopeMenuOpen = false },
                    ) {
                        // Mirrors iOS LibraryScopeMenu: Pinned (when pins exist) / All Libraries /
                        // single libraries / pin editing
                        if (state.pinnedIds.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_scope_pinned)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.PushPin, contentDescription = null)
                                },
                                onClick = {
                                    viewModel.setScope(AppSettingsStore.SCOPE_PINNED)
                                    scopeMenuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_all_libraries)) },
                            onClick = {
                                viewModel.setScope(AppSettingsStore.SCOPE_ALL)
                                scopeMenuOpen = false
                            },
                        )
                        state.libraries.forEach { library ->
                            DropdownMenuItem(
                                text = { Text(library.name) },
                                onClick = {
                                    viewModel.setScope(AppSettingsStore.scopeForLibrary(library.id))
                                    scopeMenuOpen = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_pin_libraries)) },
                            onClick = {
                                scopeMenuOpen = false
                                showPinSheet = true
                            },
                        )
                    }
                }
                IconButton(onClick = { setLayout(layout.next) }) {
                    Icon(
                        Icons.Filled.GridView,
                        contentDescription = stringResource(R.string.library_layout_cd, stringResource(layout.labelRes)),
                    )
                }
                IconButton(onClick = { showOptionsSheet = true }) {
                    Icon(
                        Icons.Filled.FilterList,
                        contentDescription = stringResource(R.string.browse_filter),
                    )
                }
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalNavOverlaySpace.current + 8.dp),
        )
    }

    if (showOptionsSheet) {
        BrowseOptionsSheet(
            initialOptions = state.browseOptions,
            onConfirm = viewModel::setBrowseOptions,
            onDismiss = { showOptionsSheet = false },
            showSeriesFilters = true,
            suggestionLibraryIds = resolveLibraryScopeIds(state.scope, state.pinnedIds),
        )
    }
    if (showPinSheet) {
        LibraryPinSheet(
            libraries = state.libraries,
            pinnedIds = state.pinnedIds,
            onSave = { ids ->
                viewModel.setPinnedLibraries(ids)
                showPinSheet = false
            },
            onDismiss = { showPinSheet = false },
        )
    }
    menuSeries?.let { series ->
        ContextMenuSheet(
            items = seriesContextMenuItems(
                pinned = false,
                hasStarted = series.progress != null,
                completed = series.progress == 1f,
                downloaded = false,
                onMarkRead = { viewModel.markSeriesRead(series, true) },
                onMarkUnread = { viewModel.markSeriesRead(series, false) },
                onDownload = { viewModel.downloadSeries(series.id) },
                onAddToCollection = if (state.isAdmin) {
                    {
                        addTarget = series
                        addToCollectionOpen = true
                    }
                } else {
                    null
                },
                onAddToReadList = if (state.isAdmin) {
                    {
                        addTarget = series
                        addToReadListOpen = true
                    }
                } else {
                    null
                },
                onEdit = if (state.isAdmin) ({ editSeries = series }) else null,
                onAnalyze = if (state.isAdmin) ({ viewModel.analyzeSeries(series.id) {} }) else null,
                onRefresh = if (state.isAdmin) ({ viewModel.refreshSeriesMetadata(series.id) {} }) else null,
                onKomfIdentify = if (state.komfAvailable) {
                    {
                        komfViewModel.prepare(series.id) { viewModel.refresh() }
                        komfIdentifyTarget = series
                    }
                } else {
                    null
                },
                onKomfMatch = if (state.komfAvailable) {
                    { komfViewModel.komfMatch(series.id) { viewModel.refresh() } }
                } else {
                    null
                },
                onKomfReset = if (state.komfAvailable) ({ komfResetTarget = series }) else null,
                onRefreshCover = { viewModel.refreshCover(series.id) },
                onShare = {
                    viewModel.seriesWebUrl(series.id)?.let { url -> shareText(context, url) }
                },
                onDelete = { deleteSeriesTarget = series },
            ),
            onDismiss = { menuSeries = null },
        )
    }
    menuBook?.let { book ->
        ContextMenuSheet(
            items = bookContextMenuItems(
                hasStarted = book.progress != null,
                completed = book.progress == 1f,
                downloaded = false,
                oneshot = book.oneshot,
                onOpenSeries = { onVisitSeries(book) },
                onRead = { onReadBook(book, false) },
                onReadIncognito = { onReadBook(book, true) },
                onOpenDetails = { onOpenBook(book) },
                onMarkRead = { viewModel.markBookRead(book, true) },
                onMarkUnread = { viewModel.markBookRead(book, false) },
                onDownload = { viewModel.downloadBook(book.id, book.title, book.pageCount) },
                onAddToReadList = if (state.isAdmin) {
                    {
                        addTarget = book
                        addToReadListOpen = true
                    }
                } else {
                    null
                },
                onEdit = if (state.isAdmin) ({ editBook = book }) else null,
                onAnalyze = if (state.isAdmin) ({ viewModel.analyzeBook(book.id) {} }) else null,
                onRefresh = if (state.isAdmin) ({ viewModel.refreshBookMetadata(book.id) {} }) else null,
                onShare = {
                    viewModel.bookWebUrl(book.id)?.let { url -> shareText(context, url) }
                },
                onDelete = { deleteBookTarget = book },
            ),
            onDismiss = { menuBook = null },
        )
    }
    // Edit series/book metadata (mirrors SeriesEditSheet/BookEditSheet; closes directly when
    // unchanged)
    editSeries?.let { series ->
        SeriesEditSheet(
            series = series,
            onSave = { dto ->
                viewModel.saveSeriesMetadata(series.id, dto) { ok ->
                    if (ok) editSeries = null
                }
            },
            onDismiss = { editSeries = null },
        )
    }
    editBook?.let { book ->
        BookEditSheet(
            book = book,
            meta = null,
            onSave = { draft ->
                viewModel.saveBookMetadata(book.id, book.title, draft) { ok ->
                    if (ok) editBook = null
                }
            },
            onDismiss = { editBook = null },
        )
    }
    // Delete confirmation (deletes the files on the server, destructive)
    deleteSeriesTarget?.let { series ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.series_delete_files_title),
            message = stringResource(R.string.series_delete_files_message, series.title),
            onConfirm = { viewModel.deleteSeries(series.id) {} },
            onDismiss = { deleteSeriesTarget = null },
        )
    }
    deleteBookTarget?.let { book ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.book_delete_files_title),
            message = stringResource(R.string.book_delete_files_message, book.title),
            onConfirm = { viewModel.deleteBook(book.id) {} },
            onDismiss = { deleteBookTarget = null },
        )
    }
    // "Add to Collection/Read List" pickers: real list data (empty without a session)
    if (addToCollectionOpen) {
        (addTarget as? Series)?.let { series ->
            AddToListSheet(
                collections = state.collections ?: emptyList(),
                readLists = emptyList(),
                onCreateReadList = viewModel::createReadListEntry,
                onCreateCollection = viewModel::createCollectionEntry,
                onSelect = { entry ->
                    viewModel.addSeriesToCollection(entry, series)
                    addToCollectionOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToCollectionOpen = false
                    addTarget = null
                },
            )
        }
    }
    if (addToReadListOpen) {
        when (val target = addTarget) {
            is Series -> AddToListSheet(
                collections = emptyList(),
                readLists = state.readLists ?: emptyList(),
                onCreateReadList = viewModel::createReadListEntry,
                onCreateCollection = viewModel::createCollectionEntry,
                onSelect = { entry ->
                    // Komga read lists accept books only: appending a series adds its books
                    // one by one
                    viewModel.addSeriesToReadList(entry, target)
                    addToReadListOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToReadListOpen = false
                    addTarget = null
                },
            )
            is Book -> AddToListSheet(
                collections = emptyList(),
                readLists = state.readLists ?: emptyList(),
                onCreateReadList = viewModel::createReadListEntry,
                onCreateCollection = viewModel::createCollectionEntry,
                onSelect = { entry ->
                    viewModel.addBookToReadList(entry, target)
                    addToReadListOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToReadListOpen = false
                    addTarget = null
                },
            )
            else -> Unit
        }
    }
    KomfSeriesContextDialogs(
        identifyTarget = komfIdentifyTarget,
        resetTarget = komfResetTarget,
        state = komfState,
        viewModel = komfViewModel,
        onRefresh = { viewModel.refresh() },
        onDismissIdentify = { komfIdentifyTarget = null },
        onDismissReset = { komfResetTarget = null },
    )
}

/** Inline error line of the library page; the grid already applies its own horizontal content padding. */
@Composable
private fun LibraryErrorText(message: String, horizontalPadding: Dp = 16.dp) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 4.dp),
    )
}

/**
 * Header rows of the library page: content-type chips carrying the server-side totals, one compact caption
 * line and the active metadata-filter chip. It lives inside the scrolling content (instead of above it like
 * the original toolbar captions), so it scrolls away under the frosted bar and the grid starts right below it.
 */
@Composable
private fun LibraryHeader(
    contentType: LibraryContentType,
    onContentTypeChange: (LibraryContentType) -> Unit,
    seriesCount: Long,
    booksCount: Long,
    caption: String,
    activeFilter: String?,
    onClearFilter: () -> Unit,
    horizontalPadding: Dp,
) {
    Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryContentType.entries.forEach { type ->
                FilterChip(
                    selected = contentType == type,
                    onClick = { onContentTypeChange(type) },
                    label = {
                        Text(
                            stringResource(
                                R.string.library_content_count,
                                stringResource(type.labelRes),
                                if (type == LibraryContentType.Series) seriesCount else booksCount,
                            ),
                        )
                    },
                )
            }
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        // Target of the metadata-chip jump. It wears the very chip that was tapped on the detail page (field
        // icon + value, no close glyph): the condition is metadata, not a text filter, and tapping the chip
        // drops it again (the page itself stays, like a browse page whose filter was reset).
        activeFilter?.let { filter ->
            val parts = filter.split("·").map { it.trim() }
            val notableKind = parts.getOrNull(1).takeIf { parts.size == 3 && parts[0] == FILTER_KIND_NOTABLE }
            val chipLabel = when {
                parts.size == 3 && parts[0] == FILTER_KIND_NOTABLE -> parts[2]
                parts.size == 2 -> parts[1]
                else -> filter
            }
            Row(
                modifier = Modifier.padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaChip(
                    text = chipLabel,
                    icon = filterKindIcon(parts.firstOrNull().orEmpty(), notableKind),
                    onClickLabel = stringResource(R.string.browse_clear_filter),
                    onClick = onClearFilter,
                )
            }
        }
    }
}

/** The pin-editing sheet and library-scope resolution/labeling live in
 *  ui/components/LibraryScope.kt (shared by dashboard/search/library). */

/**
 * Icon of the injected-filter chip: the same one the detail page puts on the chip of that field, so the jump
 * reads as "this metadata" rather than as a named filter (publishers are buildings, tags are tags, genres are
 * masks, notable entities keep their own character/team/location icon).
 */
private fun filterKindIcon(kind: String, notableKind: String?): ImageVector = when (kind) {
    FILTER_KIND_AUTHOR -> Icons.Outlined.Person
    FILTER_KIND_TAG -> Icons.Outlined.Sell
    FILTER_KIND_PUBLISHER -> Icons.Outlined.Business
    FILTER_KIND_GENRE -> Icons.Outlined.TheaterComedy
    FILTER_KIND_AGE_RATING -> Icons.Outlined.StarBorder
    FILTER_KIND_LANGUAGE -> Icons.Outlined.Translate
    FILTER_KIND_RELEASE_YEAR -> Icons.Outlined.CalendarMonth
    FILTER_KIND_NOTABLE -> notableEntityIcon(notableKind.orEmpty())
    else -> Icons.Outlined.Sell
}
