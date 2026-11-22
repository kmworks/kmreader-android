package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.ReadListContinuationUi
import com.kmworks.kmreader.data.ReadListsInProgressSectionId
import com.kmworks.kmreader.data.SampleDashboardData
import com.kmworks.kmreader.ui.components.EmptyState
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BookHorizontalCard
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.LibraryPinSheet
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
import com.kmworks.kmreader.ui.components.PullRefreshBox
import com.kmworks.kmreader.ui.components.SectionHeader
import com.kmworks.kmreader.ui.components.SeriesGridCard
import com.kmworks.kmreader.ui.components.bookContextMenuItems
import com.kmworks.kmreader.ui.components.libraryScopeLabel
import com.kmworks.kmreader.ui.components.seriesContextMenuItems
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.hasScrolled
import com.kmworks.kmreader.ui.components.isPhoneWindow
import com.kmworks.kmreader.ui.detail.BookEditSheet
import com.kmworks.kmreader.ui.detail.KomfSeriesContextDialogs
import com.kmworks.kmreader.ui.detail.KomfSeriesViewModel
import com.kmworks.kmreader.ui.detail.SeriesEditSheet
import com.kmworks.kmreader.ui.lists.AddToListSheet
import com.kmworks.kmreader.ui.lists.ListSelection
import com.kmworks.kmreader.ui.util.shareText
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

/** Dashboard: a list of sections, each = title header + horizontal card flow. Aligned with KMReader DashboardView. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSeries: (Series) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onOpenSectionDetail: (DashboardSection<out Any>) -> Unit = {},
    onOpenContinuation: (ReadListContinuationUi) -> Unit = {},
    onVisitSeries: (Book) -> Unit = {},
    onReadBook: (Book, incognito: Boolean) -> Unit = { _, _ -> },
    komfViewModel: KomfSeriesViewModel = hiltViewModel(),
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val komfState by komfViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(komfViewModel) {
        komfViewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }
    // real sections only; empty state when there is no session or nothing loaded
    val sections = state.sections ?: emptyList()
    // Library-scope switch (Pinned/All/single library + pin editing), aligned with iOS LibraryScopeMenu;
    // the dashboard scope is a session-level choice, defaulting to "Pinned" (original DashboardLibraryScopeStore)
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var showPinSheet by remember { mutableStateOf(false) }
    // Long-press context menus (aligned with the original dashboard card contextMenu)
    var seriesMenuTarget by remember { mutableStateOf<Series?>(null) }
    var bookMenuTarget by remember { mutableStateOf<Book?>(null) }
    // Edit/delete sheet targets (entered from the context menus)
    var editSeries by remember { mutableStateOf<Series?>(null) }
    var editBook by remember { mutableStateOf<Book?>(null) }
    var deleteSeriesTarget by remember { mutableStateOf<Series?>(null) }
    var deleteBookTarget by remember { mutableStateOf<Book?>(null) }
    val context = LocalContext.current
    // "Add to Collection/Read List" sheet target
    var addTarget by remember { mutableStateOf<Any?>(null) }
    var addToCollectionOpen by remember { mutableStateOf(false) }
    var addToReadListOpen by remember { mutableStateOf(false) }
    // komf identify sheet + reset confirmation targets (series context menu)
    var komfIdentifyTarget by remember { mutableStateOf<Series?>(null) }
    var komfResetTarget by remember { mutableStateOf<Series?>(null) }

    // The frosted bar floats over the page: the sections scroll underneath it instead of below a
    // full-width toolbar, so the dashboard keeps its content-first density.
    val pageListState = rememberLazyListState()
    // Backdrop the bar blurs: the list is the haze source, so whatever slides under the bar is what
    // gets blurred (Haze needs to capture the page; a Compose layer cannot blur itself).
    val hazeState = rememberHazeState()
    // True while the pull-to-refresh control is up: its own indicator already says a reload is running,
    // so the list-wide loading bar below would only duplicate it.
    var pulling by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        GlassTopBar(
            title = stringResource(R.string.dashboard_title),
            modifier = Modifier.zIndex(1f),
            sheetVisible = pageListState.hasScrolled(),
            hazeState = hazeState,
            actions = {
                // Visibility-scope switch matching the library/list pages: text button + dropdown
                Box {
                    TextButton(onClick = { scopeMenuOpen = true }) {
                        Icon(
                            Icons.Filled.Checklist,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(libraryScopeLabel(state.libraryScope, state.libraries))
                    }
                    DropdownMenu(
                        expanded = scopeMenuOpen,
                        onDismissRequest = { scopeMenuOpen = false },
                    ) {
                        // Aligned with iOS LibraryScopeMenu: Pinned (when any pinned) / All Libraries / single library / pin editing
                        if (state.pinnedIds.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dashboard_scope_pinned)) },
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
                            val libraryScope = AppSettingsStore.scopeForLibrary(library.id)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (libraryScope == state.libraryScope) "✓ ${library.name}"
                                        else library.name,
                                    )
                                },
                                onClick = {
                                    viewModel.setScope(libraryScope)
                                    scopeMenuOpen = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dashboard_pin_libraries)) },
                            onClick = {
                                scopeMenuOpen = false
                                showPinSheet = true
                            },
                        )
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.dashboard_action_settings))
                }
            },
        )
        PullRefreshBox(
            onRefresh = { if (state.hasSession) viewModel.refresh().join() },
            modifier = Modifier.fillMaxSize(),
            indicatorTopPadding = if (state.hasSession) glassTopBarTopPadding() else 0.dp,
            onRefreshingChange = { pulling = it },
        ) {
            LazyColumn(
                state = pageListState,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                // The floating capsule navigation overlays content; reserve bottom space so the last row can
                // scroll above it. The signed-out state keeps its small inset so it stays centred in the viewport.
                contentPadding = PaddingValues(
                    top = if (state.hasSession) glassTopBarTopPadding() + 8.dp else 8.dp,
                    bottom = 8.dp + LocalNavOverlaySpace.current,
                ),
            ) {
                if (state.loading && !pulling) {
                    item(key = "loading") {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                state.error?.let { message ->
                    item(key = "error") {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (!state.hasSession) {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(R.string.dashboard_not_connected_title),
                            subtitle = stringResource(R.string.dashboard_not_connected_subtitle),
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                }
                items(sections, key = { it.id }) { section ->
                    DashboardSectionRow(
                        section = section,
                        onOpenSeries = onOpenSeries,
                        onOpenBook = onOpenBook,
                        onOpenSectionDetail = onOpenSectionDetail,
                        onOpenContinuation = onOpenContinuation,
                        onSeriesLongClick = { seriesMenuTarget = it },
                        onBookLongClick = { bookMenuTarget = it },
                        onReadBook = { book -> onReadBook(book, false) },
                        continuations = state.continuations,
                        kindMenuEnabled = state.kindMenuEnabled,
                        kindOverrides = state.kindOverrides,
                        onKindChange = { kind -> viewModel.setSectionKind(section.id, kind) },
                        hasMore = state.sectionPaging[section.id]?.hasMore == true,
                        loadingMore = state.sectionPaging[section.id]?.loadingMore == true,
                        onLoadMore = { viewModel.loadMoreSection(section.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalNavOverlaySpace.current + 8.dp),
        )
    }

    // Long-press menus: series / book (the book one includes a "Go to Series" navigation, aligned with the original BookContextMenu)
    seriesMenuTarget?.let { series ->
        ContextMenuSheet(
            items = seriesContextMenuItems(
                pinned = false,
                hasStarted = series.progress != null,
                completed = series.unreadCount == 0,
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
            onDismiss = { seriesMenuTarget = null },
        )
    }
    bookMenuTarget?.let { book ->
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
                onDownload = { viewModel.downloadBook(book) },
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
            onDismiss = { bookMenuTarget = null },
        )
    }
    // Edit series/book metadata (aligned with SeriesEditSheet/BookEditSheet; closes immediately when nothing changed)
    editSeries?.let { series ->
        SeriesEditSheet(
            series = series,
            loadValues = { viewModel.seriesEditValues(series.id) },
            onSave = { dto ->
                viewModel.saveSeriesMetadata(series.id, dto) { ok ->
                    if (ok) {
                        editSeries = null
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.detail_save_failed))
                        }
                    }
                }
            },
            onDismiss = { editSeries = null },
        )
    }
    editBook?.let { book ->
        BookEditSheet(
            book = book,
            meta = null,
            loadMeta = { viewModel.bookMetadata(book.id) },
            onSave = { draft ->
                viewModel.saveBookMetadata(book.id, book.title, draft) { ok ->
                    if (ok) {
                        editBook = null
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.detail_save_failed))
                        }
                    }
                }
            },
            onDismiss = { editBook = null },
        )
    }
    // Delete confirmation (deletes files on the server, destructive)
    deleteSeriesTarget?.let { series ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.dashboard_delete_series_title),
            message = stringResource(R.string.dashboard_delete_series_message, series.title),
            onConfirm = { viewModel.deleteSeries(series.id) {} },
            onDismiss = { deleteSeriesTarget = null },
        )
    }
    deleteBookTarget?.let { book ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.dashboard_delete_book_title),
            message = stringResource(R.string.dashboard_delete_book_message, book.title),
            onConfirm = { viewModel.deleteBook(book.id) {} },
            onDismiss = { deleteBookTarget = null },
        )
    }
    // Pin-editing sheet (aligned with iOS LibraryPickerSheet)
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
    // "Add to Collection/Read List" pickers: real list data (empty without a session). The selection seeds a
    // list created from the picker, so a new collection/read list is born holding what the user picked.
    if (addToCollectionOpen) {
        (addTarget as? Series)?.let { series ->
            AddToListSheet(
                collections = state.collections ?: emptyList(),
                readLists = emptyList(),
                selection = ListSelection(seriesIds = listOf(series.id)),
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
                selection = ListSelection(seriesIds = listOf(target.id)),
                onCreateReadList = viewModel::createReadListEntry,
                onSelect = { entry ->
                    // Komga read lists only accept books: adding a series appends its books one by one
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
                selection = ListSelection(bookIds = listOf(target.id)),
                onCreateReadList = viewModel::createReadListEntry,
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

@Composable
private fun DashboardSectionRow(
    section: DashboardSection<out Any>,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSectionDetail: (DashboardSection<out Any>) -> Unit,
    onOpenContinuation: (ReadListContinuationUi) -> Unit,
    onSeriesLongClick: (Series) -> Unit = {},
    onBookLongClick: (Book) -> Unit = {},
    onReadBook: (Book) -> Unit = {},
    continuations: List<ReadListContinuationUi> = emptyList(),
    kindMenuEnabled: Boolean = false,
    kindOverrides: Map<String, DashboardCardKind> = emptyMap(),
    onKindChange: (DashboardCardKind?) -> Unit = {},
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
) {
    // Phone windows use medium cards for every section except the horizontal ones; an explicit
    // card-kind choice still wins, and tablets/wide windows keep the stored default.
    val storedDefaultKind = SampleDashboardData.section(section.id)?.defaultKind ?: section.kind
    val defaultKind = if (isPhoneWindow() && storedDefaultKind != DashboardCardKind.Horizontal) {
        DashboardCardKind.Medium
    } else {
        storedDefaultKind
    }
    val cardKind = kindOverrides[section.id] ?: defaultKind
    val availableKinds = SampleDashboardData.section(section.id)?.availableKinds
        ?: DashboardCardKind.entries.toList()
    var kindMenuOpen by remember(section.id) { mutableStateOf(false) }
    // Card-kind menu at the far right of the header (can be disabled in settings, aligned with the
    // card-kind menu toggle in SettingsDashboardView). Icon-only like the iOS DashboardCardKindMenu:
    // the current kind already shows in the cards themselves, and a text label squeezed the section title.
    val kindTrailing: (@Composable () -> Unit)? = if (kindMenuEnabled) {
        {
            Box {
                IconButton(onClick = { kindMenuOpen = true }) {
                    Icon(
                        Icons.Filled.ViewModule,
                        contentDescription = stringResource(
                            R.string.dashboard_layout,
                            stringResource(dashboardCardKindLabelRes(cardKind)),
                        ),
                    )
                }
                DropdownMenu(
                    expanded = kindMenuOpen,
                    onDismissRequest = { kindMenuOpen = false },
                ) {
                    availableKinds.forEach { kind ->
                        val kindLabel = stringResource(dashboardCardKindLabelRes(kind))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (kind == defaultKind) {
                                        stringResource(R.string.dashboard_card_kind_default, kindLabel)
                                    } else {
                                        kindLabel
                                    },
                                )
                            },
                            onClick = {
                                kindMenuOpen = false
                                onKindChange(if (kind == defaultKind) null else kind)
                            },
                        )
                    }
                }
            }
        }
    } else {
        null
    }
    SectionHeader(
        title = section.title,
        onClick = { onOpenSectionDetail(section) },
        modifier = Modifier.padding(horizontal = 16.dp),
        trailing = kindTrailing,
    )
    // Strip end detection: request the section's next page once the last visible card is within the
    // final 3 (the "More" page shares the same feed state, so either destination grows both).
    val stripState = rememberLazyListState()
    val shouldLoadMore by remember(stripState) {
        derivedStateOf {
            val info = stripState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
    }
    // items.size is part of the key: keep requesting pages while still parked at the end after an append.
    LaunchedEffect(shouldLoadMore, hasMore, loadingMore, section.items.size) {
        if (shouldLoadMore && hasMore && !loadingMore) onLoadMore()
    }
    LazyRow(
        state = stripState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // read-list section renders continuation cards (aligned with ReadListsInProgressSectionView)
        if (section.id == ReadListsInProgressSectionId) {
            items(continuations, key = { it.readListId }) { continuation ->
                ReadListContinuationCard(
                    continuation = continuation,
                    onClick = { onOpenContinuation(continuation) },
                    stableId = continuation.readListId,
                    modifier = Modifier.width(DashboardCardKind.Horizontal.widthDp.dp),
                )
            }
        } else {
            items(section.items) { item ->
                when {
                    item is Book && cardKind == DashboardCardKind.Horizontal ->
                        BookHorizontalCard(
                            book = item,
                            onClick = { onReadBook(item) },
                            onLongClick = { onBookLongClick(item) },
                            modifier = Modifier.width(DashboardCardKind.Horizontal.widthDp.dp),
                        )

                    item is Book ->
                        BookGridCard(
                            book = item,
                            onClick = { onReadBook(item) },
                            onLongClick = { onBookLongClick(item) },
                            modifier = Modifier.width(cardKind.widthDp.dp),
                            reserveProgressSpace = true,
                        )

                    item is Series ->
                        SeriesGridCard(
                            series = item,
                            onClick = { onOpenSeries(item) },
                            onLongClick = { onSeriesLongClick(item) },
                            modifier = Modifier.width(cardKind.widthDp.dp),
                            reserveProgressSpace = true,
                        )
                }
            }
            // Trailing loading row: shown while another page exists; reaching it requests the page.
            if (hasMore) {
                item(key = "load-more") {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(
                                if (cardKind == DashboardCardKind.Horizontal) 120.dp
                                else cardKind.widthDp.dp * 1.5f,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
