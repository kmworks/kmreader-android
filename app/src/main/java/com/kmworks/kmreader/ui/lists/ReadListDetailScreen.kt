package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.glassPageContentWindowInsets
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.hasScrolled
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.ReadListDetail
import com.kmworks.kmreader.data.ReadStatus
import com.kmworks.kmreader.data.TriState
import com.kmworks.kmreader.ui.components.BrowseFilterChipFlow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.rememberPersistentBrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.SelectionToolbar
import com.kmworks.kmreader.ui.components.bookContextMenuItems
import com.kmworks.kmreader.ui.detail.BookEditSheet
import com.kmworks.kmreader.ui.util.shareText
import kotlinx.coroutines.launch

private val WideLayoutBreakpoint = 960.dp

/** Read list detail page (mirrors ReadListDetailView.swift + ReadListDetailWideLayoutView.swift). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadListDetailScreen(
    readList: ReadListDetail,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    onOpenSeries: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onReadIncognito: (Book) -> Unit = {},
    onOpenBook: (Book) -> Unit = {},
    onTogglePin: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRemoveBooks: (List<Int>) -> Unit = {},
    viewModel: ReadListDetailViewModel = hiltViewModel(),
) {
    // Real data: resolves the DTO and fetches each book in bookIds order (with real
    // progress); shows an empty state without a session
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val notifyActionFailure: suspend (String) -> Unit = { action ->
        snackbarHostState.showSnackbar(context.getString(R.string.common_action_failed_retry, action))
    }
    LaunchedEffect(readList.id) { viewModel.load(readList.id) }
    val displayReadList = readList.copy(
        name = state.name ?: readList.name,
        summary = state.summary ?: readList.summary,
        ordered = state.ordered ?: readList.ordered,
    )
    val sourceBooks = state.books ?: emptyList()
    val (layout, setLayout) = rememberPersistentBrowseLayout("readListDetail", BrowseLayout.List)
    var options by remember { mutableStateOf(BookBrowseOptions()) }
    var optionsOpen by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var removedIds by remember { mutableStateOf(setOf<Int>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val wideListScrollState = rememberScrollState()
    val narrowListScrollState = rememberScrollState()
    // per-book "..." menu and "Add to Read List" sheet
    var menuBook by remember { mutableStateOf<Book?>(null) }
    // Book metadata edit sheet (entered from the context menu "Edit")
    var editBook by remember { mutableStateOf<Book?>(null) }
    var addTarget by remember { mutableStateOf<Book?>(null) }
    var addToReadListOpen by remember { mutableStateOf(false) }
    var addSelectedToReadListOpen by remember { mutableStateOf(false) }
    // Local immediate feedback under demo data (no session): read/unread overrides
    var demoMarks by remember { mutableStateOf(mapOf<Int, Boolean>()) }
    val readLists by viewModel.readLists.collectAsState()

    val visible = sourceBooks.filter { it.id !in removedIds }.map { b ->
        demoMarks[b.id]?.let { r -> b.copy(progress = if (r) 1f else null) } ?: b
    }
    val books = visible.filter { matchesReadStatus(it, options) }
    val continuation = visible.indexOfFirst { it.progress == null || it.progress < 1f }
        .let { if (it < 0) null else it }
    val heroFirst = visible.firstOrNull()

    // Selection actions, hoisted so the pinned bottom bar and the list rows share one implementation.
    val toggleSelectAll: () -> Unit = {
        selected = if (selected.size == books.size) emptySet() else books.map { it.id }.toSet()
    }
    val exitSelection: () -> Unit = {
        selecting = false
        selected = emptySet()
    }
    // Real data goes through the API (updated in the VM); demo data gets local feedback.
    val onSelectedMarkRead: () -> Unit = {
        if (!viewModel.markBooksRead(selected, true)) {
            demoMarks = demoMarks + selected.associateWith { true }
        }
        exitSelection()
    }
    val onSelectedMarkUnread: () -> Unit = {
        if (!viewModel.markBooksRead(selected, false)) {
            demoMarks = demoMarks + selected.associateWith { false }
        }
        exitSelection()
    }
    val onSelectedDownload: () -> Unit = {
        viewModel.downloadBooks(selected) { ok ->
            if (!ok) coroutineScope.launch { notifyActionFailure(context.getString(R.string.lists_download_selected_books)) }
        }
        exitSelection()
    }
    val onSelectedAddToReadList: () -> Unit = {
        viewModel.ensureReadListsLoaded()
        addSelectedToReadListOpen = true
    }
    val onSelectedRemove: () -> Unit = {
        if (!viewModel.removeBooks(selected)) {
            removedIds = removedIds + selected
        }
        onRemoveBooks(selected.toList())
        exitSelection()
    }

    // The frosted bar floats inside the page box instead of occupying a Scaffold slot, so the page
    // scrolls underneath it and whatever passes under the row is frosted (same as the tab roots).
    val barHazeState = rememberHazeState()
    val railScrollState = rememberScrollState()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Pinned to the page bottom: the book list is long, and the inline row scrolled away with it,
        // forcing the user back to the top to act on the selection they had just made.
        bottomBar = {
            if (selecting) {
                SelectionToolbar(
                    selectedCount = selected.size,
                    totalCount = books.size,
                    onSelectAll = toggleSelectAll,
                    onMarkRead = onSelectedMarkRead,
                    onMarkUnread = onSelectedMarkUnread,
                    onDownload = onSelectedDownload,
                    onAddToReadList = onSelectedAddToReadList,
                    onDelete = onSelectedRemove,
                    onDone = exitSelection,
                )
            }
        },
        // Keep the page under the status bar: the Scaffold inset would clip every scroller at the
        // status bar and leave the bar's top strip without a backdrop (a hard seam).
        contentWindowInsets = glassPageContentWindowInsets(),
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            GlassTopBar(
                title = "",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
                sheetVisible = railScrollState.hasScrolled() || wideListScrollState.hasScrolled() || narrowListScrollState.hasScrolled(),
                hazeState = barHazeState,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.lists_pin)) },
                                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onTogglePin()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_edit)) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_refresh_cover)) },
                                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                onClick = { menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.readlist_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    deleteConfirm = true
                                },
                            )
                        }
                    }
                },
            )
            if (maxWidth >= WideLayoutBreakpoint) {
                // Wide layout: left info column (maxOf(maxWidth*0.382f, 340.dp)) + right
                // book list, scrolling independently
                // One haze source over the whole split instead of one per pane: the bar's backdrop has to
                // be the full width of the page, and a scroller that is only as tall as its own pane
                // leaves the strip behind the bar empty whenever the other pane is the one scrolling.
                Row(modifier = Modifier.fillMaxSize().hazeSource(barHazeState)) {
                    Column(
                        modifier = Modifier
                            .width(maxOf(this@BoxWithConstraints.maxWidth * 0.382f, 340.dp))
                            .fillMaxHeight()
                            .verticalScroll(railScrollState)
                            .padding(
                                start = 24.dp,
                                end = 24.dp,
                                top = glassTopBarTopPadding() + 16.dp,
                                bottom = 16.dp,
                            ),
                    ) {
                        ReadListHero(
                            displayReadList,
                            bookCount = visible.size,
                            centered = false,
                            first = heroFirst,
                            createdDate = state.createdDate,
                            lastModifiedDate = state.lastModifiedDate,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(wideListScrollState)
                            .padding(top = glassTopBarTopPadding() + 8.dp, bottom = 8.dp),
                    ) {
                        if (continuation != null) {
                            ContinuationHintCard(
                                index = continuation,
                                book = visible[continuation],
                                onClick = { onRead(visible[continuation]) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        ReadListToolbar(
                            selectionMode = selecting,
                            options = options,
                            layout = layout,
                            onLayoutChange = { setLayout(it) },
                            onOpenOptions = { optionsOpen = true },
                            onApplyPreset = { options = it.options },
                            onStartSelecting = { selecting = true },
                        )
                        ReadListBookList(
                            books = books,
                            loading = state.loading,
                            selecting = selecting,
                            selected = selected,
                            onRead = onRead,
                            onToggleSelect = { id ->
                                selected = if (id in selected) selected - id else selected + id
                            },
                            onMenu = { menuBook = it },
                        )
                        if (state.loadingMore) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(narrowListScrollState)
                        .hazeSource(barHazeState)
                        .padding(top = glassTopBarTopPadding() + 16.dp, bottom = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        ReadListHero(
                            displayReadList,
                            bookCount = visible.size,
                            centered = true,
                            first = heroFirst,
                            createdDate = state.createdDate,
                            lastModifiedDate = state.lastModifiedDate,
                        )
                    }
                    if (continuation != null) {
                        ContinuationHintCard(
                            index = continuation,
                            book = visible[continuation],
                            onClick = { onRead(visible[continuation]) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    ReadListToolbar(
                        selectionMode = selecting,
                        options = options,
                        layout = layout,
                        onLayoutChange = { setLayout(it) },
                        onOpenOptions = { optionsOpen = true },
                        onApplyPreset = { options = it.options },
                        onStartSelecting = { selecting = true },
                    )
                    ReadListBookList(
                        books = books,
                        loading = state.loading,
                        selecting = selecting,
                        selected = selected,
                        onRead = onRead,
                        onToggleSelect = { id ->
                            selected = if (id in selected) selected - id else selected + id
                        },
                        onMenu = { menuBook = it },
                    )
                    if (state.loadingMore) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            AutoLoadMore(
                scrollState = if (maxWidth >= WideLayoutBreakpoint) wideListScrollState else narrowListScrollState,
                hasMore = state.hasMore,
                loadingMore = state.loadingMore,
                onLoadMore = viewModel::loadMore,
            )
        }
    }

    if (optionsOpen) {
        BrowseOptionsSheet(
            initialOptions = options,
            onConfirm = { options = it },
            onDismiss = { optionsOpen = false },
            suggestionReadListStableId = readList.id,
        )
    }
    // per-book "..." menu: mark read/unread, add to read list, remove from list
    // (mirrors BookContextMenu)
    menuBook?.let { target ->
        ContextMenuSheet(
            items = bookContextMenuItems(
                hasStarted = target.progress != null,
                completed = target.progress == 1f,
                downloaded = false,
                oneshot = target.oneshot,
                onOpenSeries = {
                    viewModel.seriesStableIdForBook(target.id) { seriesStableId ->
                        if (seriesStableId != null) onOpenSeries(seriesStableId)
                        else coroutineScope.launch { notifyActionFailure(context.getString(R.string.lists_open_series)) }
                    }
                },
                onRead = { onRead(target) },
                onReadIncognito = { onReadIncognito(target) },
                onOpenDetails = { onOpenBook(target) },
                onDownload = {
                    viewModel.downloadBooks(setOf(target.id)) { ok ->
                        if (!ok) coroutineScope.launch { notifyActionFailure(context.getString(R.string.lists_download_book)) }
                    }
                },
                onMarkRead = {
                    if (!viewModel.markBooksRead(setOf(target.id), true)) {
                        demoMarks = demoMarks + (target.id to true)
                    }
                },
                onMarkUnread = {
                    if (!viewModel.markBooksRead(setOf(target.id), false)) {
                        demoMarks = demoMarks + (target.id to false)
                    }
                },
                onAddToReadList = if (state.isAdmin) {
                    {
                        addTarget = target
                        viewModel.ensureReadListsLoaded()
                        addToReadListOpen = true
                    }
                } else {
                    null
                },
                onEdit = if (state.isAdmin) ({ editBook = target }) else null,
                onAnalyze = if (state.isAdmin) {
                    {
                        viewModel.analyzeBook(target.id) { ok ->
                            if (!ok) coroutineScope.launch { notifyActionFailure(context.getString(R.string.lists_analyze_book)) }
                        }
                    }
                } else {
                    null
                },
                onRefresh = if (state.isAdmin) {
                    {
                        viewModel.refreshBookMetadata(target.id) { ok ->
                            if (!ok) coroutineScope.launch { notifyActionFailure(context.getString(R.string.lists_refresh_metadata)) }
                        }
                    }
                } else {
                    null
                },
                onShare = {
                    val url = viewModel.bookWebUrl(target.id)
                    if (url != null) {
                        shareText(context, url)
                    } else {
                        coroutineScope.launch { notifyActionFailure(context.getString(R.string.lists_share_book)) }
                    }
                },
                onDelete = {
                    if (!viewModel.removeBooks(setOf(target.id))) {
                        removedIds = removedIds + target.id
                    }
                },
            ),
            onDismiss = { menuBook = null },
        )
    }
    // Book metadata edit sheet (meta passed as null: only the title-baseline diff is submitted)
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
                        coroutineScope.launch { notifyActionFailure(context.getString(R.string.lists_save_book_metadata)) }
                    }
                }
            },
            onDismiss = { editBook = null },
        )
    }
    if (addSelectedToReadListOpen) {
        AddToListSheet(
            collections = emptyList(),
            readLists = readLists ?: emptyList(),
            onCreateReadList = viewModel::createReadListEntry,
            onCreateCollection = viewModel::createCollectionEntry,
            onSelect = { entry ->
                viewModel.addBooksToReadList(entry, selected) { ok ->
                    if (!ok) coroutineScope.launch { notifyActionFailure(context.getString(R.string.lists_add_to_readlist)) }
                }
                addSelectedToReadListOpen = false
                selected = emptySet()
                selecting = false
            },
            onDismiss = { addSelectedToReadListOpen = false },
        )
    }
    if (addToReadListOpen) {
        addTarget?.let { target ->
            AddToListSheet(
                collections = emptyList(),
                readLists = readLists ?: emptyList(),
                onCreateReadList = viewModel::createReadListEntry,
                onCreateCollection = viewModel::createCollectionEntry,
                onSelect = { entry ->
                    viewModel.addBookToReadList(entry, target.id)
                    addToReadListOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToReadListOpen = false
                    addTarget = null
                },
            )
        }
    }
    if (deleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.lists_delete_item_title, stringResource(R.string.common_readlist)),
            message = stringResource(R.string.readlist_delete_message, displayReadList.name),
            onConfirm = onDelete,
            onDismiss = { deleteConfirm = false },
        )
    }
}

@Composable
private fun AutoLoadMore(
    scrollState: ScrollState,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(scrollState, hasMore, loadingMore) {
        snapshotFlow {
            Triple(scrollState.value, scrollState.maxValue, scrollState.viewportSize)
        }.collect { (value, maxValue, viewportSize) ->
            if (hasMore && !loadingMore && maxValue > 0 && maxValue - value <= viewportSize) {
                onLoadMore()
            }
        }
    }
}

/** Hero: cover + name + book count/ordered + summary + timestamps (mirrors ReadListDetailContentView). */
@Composable
private fun ReadListHero(
    readList: ReadListDetail,
    bookCount: Int,
    centered: Boolean,
    first: Book?,
    createdDate: String?,
    lastModifiedDate: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (first != null) {
            CoverArt(
                title = first.seriesTitle,
                hue = first.hue,
                stableId = first.id,
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(2f / 3f),
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = readList.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.book_count, bookCount, bookCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (readList.ordered) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Filled.FormatListNumbered,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.lists_ordered_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        readList.summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        val timestamps = listOfNotNull(
            createdDate?.take(10)?.let { stringResource(R.string.lists_created_at, it) },
            lastModifiedDate?.take(10)?.let { stringResource(R.string.lists_modified_at, it) },
        ).joinToString(" · ")
        if (timestamps.isNotEmpty()) {
            Text(
                text = timestamps,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Continuation hint card: "Continue Reading: Book N" (mirrors ReadListContinuationHintView). */
@Composable
private fun ContinuationHintCard(
    index: Int,
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.FormatListNumbered,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.readlist_continue_reading, index + 1),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Toolbar row: filter chip flow + enter multi-select. The selection actions live in the pinned
 *  bottom bar instead, so they stay reachable while the list is scrolled (mirrors
 *  BooksListViewForReadList). */
@Composable
private fun ReadListToolbar(
    selectionMode: Boolean,
    options: BookBrowseOptions,
    layout: BrowseLayout,
    onLayoutChange: (BrowseLayout) -> Unit,
    onOpenOptions: () -> Unit,
    onApplyPreset: (com.kmworks.kmreader.data.SavedFilter) -> Unit,
    onStartSelecting: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrowseFilterChipFlow(
            options = options,
            layout = layout,
            onLayoutChange = onLayoutChange,
            onOpenOptions = onOpenOptions,
            onApplyPreset = onApplyPreset,
            modifier = Modifier.weight(1f),
        )
        // Hidden while a selection is active: the pinned bar owns the selection actions, and a second
        // entry here would only re-enter the mode the page is already in.
        if (!selectionMode) {
            IconButton(onClick = onStartSelecting) {
                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.lists_select))
            }
        }
    }
}

/** Ordered book list: the index conveys the order and each row shows reading
 *  progress (mirrors the ReadList book rows). */
@Composable
private fun ReadListBookList(
    books: List<Book>,
    loading: Boolean,
    selecting: Boolean,
    selected: Set<Int>,
    onRead: (Book) -> Unit,
    onToggleSelect: (Int) -> Unit,
    onMenu: (Book) -> Unit,
) {
    if (loading && books.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
        return
    }
    Column {
        books.forEachIndexed { index, book ->
            ReadListBookRow(
                index = index,
                book = book,
                selected = if (selecting) book.id in selected else null,
                onClick = {
                    if (selecting) onToggleSelect(book.id) else onRead(book)
                },
                onMenu = if (selecting) null else ({ onMenu(book) }),
            )
        }
    }
}

/** Read list book row: index + cover + title/pages/progress + read badge + "..." menu. */
@Composable
private fun ReadListBookRow(
    index: Int,
    book: Book,
    selected: Boolean?,
    onMenu: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        CoverArt(
            title = book.seriesTitle,
            hue = book.hue,
            stableId = book.id,
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.book_pages, book.pageCount, book.pageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            book.progress?.let { p ->
                if (p < 1f) {
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(3.dp),
                    )
                }
            }
        }
        if (book.progress == 1f) {
            Text(
                text = stringResource(R.string.lists_read_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (onMenu != null) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
            }
        }
    }
}

private fun matchesReadStatus(book: Book, options: BookBrowseOptions): Boolean {
    val status = when {
        book.progress == 1f -> ReadStatus.Read
        book.progress != null -> ReadStatus.InProgress
        else -> ReadStatus.Unread
    }
    val states = options.readStatuses
    if (states.values.any { it == TriState.Include }) {
        return states[status] == TriState.Include
    }
    return states[status] != TriState.Exclude
}
