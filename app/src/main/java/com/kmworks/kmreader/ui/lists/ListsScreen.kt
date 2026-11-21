package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.EmptyState
import com.kmworks.kmreader.ui.components.ContextMenuItem
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.collectionContextMenuItems
import com.kmworks.kmreader.ui.components.LibraryPinSheet
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
import com.kmworks.kmreader.ui.components.PullRefreshBox
import com.kmworks.kmreader.ui.components.readListContextMenuItems
import com.kmworks.kmreader.ui.components.smartListContextMenuItems
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.glassPageContentWindowInsets
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.hasScrolled
import com.kmworks.kmreader.ui.components.libraryScopeLabel
import com.kmworks.kmreader.ui.util.shareText
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Lists tab: mirrors KMReader ListsBrowseView — three horizontal strips of
 *  collections / read lists / smart lists, pinned entries first, each strip header opens the
 *  full page of that kind; toolbar scope filter (LibraryScopeMenu).
 *
 *  With [initialGroup] set the same composable renders that page instead of the tab (back arrow +
 *  every entry of the kind in a vertical grid), so the strip and its page share one view model, one
 *  paging pass and one set of edit sheets: opening "see all" never re-fetches what the tab already
 *  has, and an edit made on either destination shows up on both. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onOpenCollection: (Int) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onOpenSmartList: (Int) -> Unit = {},
    onOpenGroup: (ListsGroupKind) -> Unit = {},
    initialGroup: ListsGroupKind? = null,
    onBack: () -> Unit = {},
) {
    val groupCollectionsLabel = stringResource(ListsGroupKind.Collections.labelRes)
    val groupReadListsLabel = stringResource(ListsGroupKind.ReadLists.labelRes)
    val groupSmartListsLabel = stringResource(ListsGroupKind.SmartLists.labelRes)
    // The sheets below address their action by the group title, and both destinations (strip and
    // page) resolve it the same way, so one menu implementation serves both.
    fun titleOf(kind: ListsGroupKind): String = when (kind) {
        ListsGroupKind.Collections -> groupCollectionsLabel
        ListsGroupKind.ReadLists -> groupReadListsLabel
        ListsGroupKind.SmartLists -> groupSmartListsLabel
    }
    fun openEntryOf(kind: ListsGroupKind): (ListEntry) -> Unit = when (kind) {
        ListsGroupKind.Collections -> { entry -> onOpenCollection(entry.id) }
        ListsGroupKind.ReadLists -> { entry -> onOpenReadList(entry.id) }
        ListsGroupKind.SmartLists -> { entry -> onOpenSmartList(entry.id) }
    }
    // Pin-editing sheet opened from the scope menu
    var showPinSheet by remember { mutableStateOf(false) }
    // Long-press context menu target (group name + entry)
    var menuTarget by remember { mutableStateOf<Pair<String, ListEntry>?>(null) }
    // Edit sheet target (entered from the context menu "Edit")
    var editTarget by remember { mutableStateOf<Pair<String, ListEntry>?>(null) }
    // Delete confirmation dialog target (entered from the context menu "Delete")
    var deleteTarget by remember { mutableStateOf<Pair<String, ListEntry>?>(null) }
    val context = LocalContext.current

    val viewModel: ListsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val notifyActionFailure: suspend (String) -> Unit = { action ->
        snackbarHostState.showSnackbar(context.getString(R.string.common_action_failed_retry, action))
    }
    // real groups only (capability-gated: smart lists hidden when unsupported/empty)
    val groups = buildList {
        state.collections?.let { if (it.isNotEmpty()) add(ListsStripGroup(ListsGroupKind.Collections, groupCollectionsLabel, it)) }
        state.readLists?.let { if (it.isNotEmpty()) add(ListsStripGroup(ListsGroupKind.ReadLists, groupReadListsLabel, it)) }
        state.smartLists?.let { if (it.isNotEmpty()) add(ListsStripGroup(ListsGroupKind.SmartLists, groupSmartListsLabel, it)) }
    }

    // Two destinations share this screen: the tab draws the strips and the scope chrome, and a strip
    // header opens one kind as a page (its own back arrow and grid). The sheet state lives here, so a
    // long-press menu, its edit sheet and its delete dialog work the same on both.
    if (initialGroup != null) {
        ListsGroupPage(
            kind = initialGroup,
            title = titleOf(initialGroup),
            state = state,
            onBack = onBack,
            onOpenEntry = openEntryOf(initialGroup),
            onEntryMenu = { entry -> menuTarget = titleOf(initialGroup) to entry },
            onLoadMore = when (initialGroup) {
                ListsGroupKind.Collections -> viewModel::loadMoreCollections
                ListsGroupKind.ReadLists -> viewModel::loadMoreReadLists
                ListsGroupKind.SmartLists -> ({})
            },
        )
    } else {
        ListsTabBody(
            viewModel = viewModel,
            state = state,
            groups = groups,
            snackbarHostState = snackbarHostState,
            onOpenPinSheet = { showPinSheet = true },
            onOpenGroup = onOpenGroup,
            onOpenEntry = { kind, entry -> openEntryOf(kind)(entry) },
            onEntryMenu = { kind, entry -> menuTarget = titleOf(kind) to entry },
        )
    }

    // Pin-editing sheet (aligned with iOS LibraryPickerSheet / the dashboard)
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

    menuTarget?.let { (groupTitle, entry) ->
        val items: List<ContextMenuItem> = when (groupTitle) {
            groupCollectionsLabel -> collectionContextMenuItems(
                pinned = entry.pinned,
                onTogglePin = { viewModel.toggleCollectionPinned(entry) },
                onEdit = { editTarget = groupTitle to entry },
                onRefreshCover = {
                    if (!viewModel.refreshCover(entry.id)) {
                        coroutineScope.launch { notifyActionFailure(context.getString(R.string.common_refresh_cover)) }
                    }
                },
                onDelete = { deleteTarget = groupTitle to entry },
            )
            groupReadListsLabel -> readListContextMenuItems(
                pinned = entry.pinned,
                onTogglePin = { viewModel.toggleReadListPinned(entry) },
                onEdit = { editTarget = groupTitle to entry },
                onRefreshCover = {
                    if (!viewModel.refreshCover(entry.id)) {
                        coroutineScope.launch { notifyActionFailure(context.getString(R.string.common_refresh_cover)) }
                    }
                },
                onShare = {
                    val url = viewModel.readListWebUrl(entry.id)
                    if (url != null) {
                        shareText(context, url)
                    } else {
                        coroutineScope.launch { notifyActionFailure(context.getString(R.string.readlist_share)) }
                    }
                },
                onDelete = { deleteTarget = groupTitle to entry },
            )
            else -> smartListContextMenuItems(
                pinned = entry.pinned,
                onEdit = { editTarget = groupTitle to entry },
                onRefreshCover = {
                    if (!viewModel.refreshCover(entry.id)) {
                        coroutineScope.launch { notifyActionFailure(context.getString(R.string.common_refresh_cover)) }
                    }
                },
                onDelete = { deleteTarget = groupTitle to entry },
            )
        }
        ContextMenuSheet(items = items, onDismiss = { menuTarget = null })
    }

    deleteTarget?.let { (groupTitle, entry) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.lists_delete_item_title, groupTitle)) },
            text = { Text(stringResource(R.string.lists_delete_item_message, entry.title)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    when (groupTitle) {
                        groupCollectionsLabel -> viewModel.deleteCollection(entry) { ok ->
                            if (!ok) coroutineScope.launch { notifyActionFailure(context.getString(R.string.collection_delete)) }
                        }
                        groupReadListsLabel -> viewModel.deleteReadList(entry) { ok ->
                            if (!ok) coroutineScope.launch { notifyActionFailure(context.getString(R.string.readlist_delete)) }
                        }
                        else -> viewModel.deleteSmartList(entry) { ok ->
                            if (!ok) coroutineScope.launch { notifyActionFailure(context.getString(R.string.smartlist_delete)) }
                        }
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    editTarget?.let { (groupTitle, entry) ->
        when (groupTitle) {
            groupCollectionsLabel -> CollectionEditSheet(
                onSave = { name, ordered ->
                    viewModel.saveCollection(entry, name, ordered) { ok ->
                        if (ok) editTarget = null else coroutineScope.launch { notifyActionFailure(context.getString(R.string.collection_save)) }
                    }
                },
                onDismiss = { editTarget = null },
                initialName = entry.title,
                initialOrdered = entry.ordered ?: false,
                isEdit = true,
                onDelete = {
                    viewModel.deleteCollection(entry) { ok ->
                        if (ok) editTarget = null else coroutineScope.launch { notifyActionFailure(context.getString(R.string.collection_delete)) }
                    }
                },
            )
            groupReadListsLabel -> ReadListEditSheet(
                onSave = { name, summary, ordered ->
                    viewModel.saveReadList(entry, name, summary, ordered) { ok ->
                        if (ok) editTarget = null else coroutineScope.launch { notifyActionFailure(context.getString(R.string.readlist_save)) }
                    }
                },
                onDismiss = { editTarget = null },
                initialName = entry.title,
                initialSummary = entry.summary.orEmpty(),
                initialOrdered = entry.ordered ?: true,
                isEdit = true,
                onDelete = {
                    viewModel.deleteReadList(entry) { ok ->
                        if (ok) editTarget = null else coroutineScope.launch { notifyActionFailure(context.getString(R.string.readlist_delete)) }
                    }
                },
            )
            else -> SmartListEditSheet(
                onSave = { name, _, matchAll, rules ->
                    viewModel.saveSmartList(entry, name, matchAll, rules) { ok ->
                        if (ok) editTarget = null else coroutineScope.launch { notifyActionFailure(context.getString(R.string.smartlist_save)) }
                    }
                },
                onDismiss = { editTarget = null },
                initialName = entry.title,
                initialTarget = entry.smartListTarget ?: com.kmworks.kmreader.data.SmartListTarget.Book,
                isEdit = true,
                onDelete = {
                    viewModel.deleteSmartList(entry) { ok ->
                        if (ok) editTarget = null else coroutineScope.launch { notifyActionFailure(context.getString(R.string.smartlist_delete)) }
                    }
                },
            )
        }
    }
}

/** One strip of the tab: the kind it shows, the label to print, and the entries loaded so far. */
private data class ListsStripGroup(
    val kind: ListsGroupKind,
    val title: String,
    val items: List<ListEntry>,
)

/**
 * One group opened as its own page ("see all"): the kind's entries in an adaptive vertical grid,
 * paged through the tab's view model, with the same long-press menu the strip offers.
 *
 * The page reads the view model state rather than fetching: the tab has already loaded the first
 * page, and scrolling here requests the next one, so both destinations always show the same list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListsGroupPage(
    kind: ListsGroupKind,
    title: String,
    state: ListsUiState,
    onBack: () -> Unit,
    onOpenEntry: (ListEntry) -> Unit,
    onEntryMenu: (ListEntry) -> Unit,
    onLoadMore: () -> Unit,
) {
    val entries = when (kind) {
        ListsGroupKind.Collections -> state.collections
        ListsGroupKind.ReadLists -> state.readLists
        ListsGroupKind.SmartLists -> state.smartLists
    }.orEmpty()
    val hasMore = when (kind) {
        ListsGroupKind.Collections -> state.collectionsHasMore
        ListsGroupKind.ReadLists -> state.readListsHasMore
        ListsGroupKind.SmartLists -> false
    }

    val gridState = rememberLazyGridState()
    val barHazeState = rememberHazeState()
    // Page the group exactly like the strip does: request the next page once the last visible card
    // is within the final 6, and keep requesting while still parked at the end after an append.
    val shouldLoadMore by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, state.loadingMore, entries.size) {
        if (shouldLoadMore && hasMore && !state.loadingMore) onLoadMore()
    }

    // Same chrome contract as the dashboard section page: the frosted bar floats inside the page box
    // so the grid scrolls underneath it and the strip under the status bar is frosted too.
    Scaffold(contentWindowInsets = glassPageContentWindowInsets()) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            GlassTopBar(
                title = title,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
                sheetVisible = gridState.hasScrolled(),
                hazeState = barHazeState,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
            when {
                !state.hasSession -> EmptyState(
                    title = stringResource(R.string.lists_no_session_title),
                    subtitle = stringResource(R.string.lists_no_session_subtitle),
                )
                state.error != null && entries.isEmpty() -> EmptyState(title = state.error)
                // Empty before the first page lands is "loading"; empty afterwards is "this group has
                // no entries" (the user removed the last one), which needs a word rather than a blank page.
                entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        EmptyState(title = stringResource(R.string.browse_hint_no_results))
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(barHazeState),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = glassTopBarTopPadding() + 16.dp,
                        end = 16.dp,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // pinned first, same ordering as the strip
                    items(entries.sortedByDescending { it.pinned }, key = { it.id }) { entry ->
                        ListEntryCard(
                            entry = entry,
                            onClick = { onOpenEntry(entry) },
                            onLongClick = { onEntryMenu(entry) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (hasMore) {
                        item(key = "load-more", span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One horizontal strip: section header (tap to open the full page) + card row;
 *  requests the next page when scrolled to the end. */
@Composable
private fun ListStrip(
    group: ListsStripGroup,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onOpenEntry: (ListEntry) -> Unit,
    onEntryMenu: (ListEntry) -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    // Strip end detection: request the next page once the last visible item is within
    // the final 3 items.
    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
    }
    // items.size is part of the key: keep requesting pages while still parked at the
    // end of the strip after an append
    LaunchedEffect(shouldLoadMore, hasMore, loadingMore, group.items.size) {
        if (shouldLoadMore && hasMore && !loadingMore) onLoadMore()
    }
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // The chevron is the entry point: without a destination (no session) it would promise a
            // page that cannot have content, so it is drawn only when the header is clickable.
            if (onOpen != null) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.common_all),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // pinned first (matches the original app's ordering)
            items(group.items.sortedByDescending { it.pinned }, key = { it.id }) { entry ->
                ListEntryCard(
                    entry = entry,
                    onClick = { onOpenEntry(entry) },
                    onLongClick = { onEntryMenu(entry) },
                    modifier = Modifier.width(110.dp),
                )
            }
            // Trailing loading row: shown while another page exists; scrolling to the
            // end requests it automatically
            if (hasMore) {
                item(key = "load-more") {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(164.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

/** List entry card: cover placeholder + title + count (the grouping target for smart
 *  lists, which carry no count of their own); pinned entries carry a pin badge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListEntryCard(
    entry: ListEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            CoverArt(
                title = entry.title,
                hue = entry.hue,
                stableId = entry.id,
                modifier = Modifier.matchParentSize(),
            )
            if (entry.pinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = stringResource(R.string.lists_pinned),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // A smart list is a saved query, not a container: the server never reports how many
        // entries it holds, so a "%d items" line here would read "0 items" forever. Show what
        // the list groups by instead; its own page carries the item counts and timestamps.
        val secondary = if (entry.smartListTarget != null) {
            entry.targetLabel.orEmpty()
        } else {
            stringResource(R.string.lists_item_count, entry.count)
        }
        if (secondary.isNotEmpty()) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The tab itself: frosted bar with the library-scope menu, the three strips, pull-to-refresh.
 *
 * Split out of [ListsScreen] because both destinations share one view model and one set of sheets but
 * not the chrome: a pushed "see all" page must not grow the tab's scope menu and strips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListsTabBody(
    viewModel: ListsViewModel,
    state: ListsUiState,
    groups: List<ListsStripGroup>,
    snackbarHostState: SnackbarHostState,
    onOpenPinSheet: () -> Unit,
    onOpenGroup: (ListsGroupKind) -> Unit,
    onOpenEntry: (ListsGroupKind, ListEntry) -> Unit,
    onEntryMenu: (ListsGroupKind, ListEntry) -> Unit,
) {
    var scopeMenuOpen by remember { mutableStateOf(false) }
    // The frosted bar floats over the page: the list groups scroll underneath it.
    val pageListState = rememberLazyListState()
    val hazeState = rememberHazeState()
    // True while the pull-to-refresh control is up: its own indicator already says a reload is running,
    // so the list-wide loading bar below would only duplicate it.
    var pulling by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        GlassTopBar(
            title = stringResource(R.string.lists_tab_title),
            modifier = Modifier.zIndex(1f),
            sheetVisible = pageListState.hasScrolled(),
            hazeState = hazeState,
            actions = {
                // LibraryScopeMenu: all libraries / pinned / single library
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
                        // Aligned with iOS LibraryScopeMenu / the dashboard: Pinned (when any pinned) /
                        // All Libraries / single library / pin editing. The choice is persisted, and the
                        // view model re-queries both strips against the resolved libraryIds.
                        if (state.pinnedIds.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.lists_scope_pinned)) },
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
                                onOpenPinSheet()
                            },
                        )
                    }
                }
            },
        )
        PullRefreshBox(
            onRefresh = { viewModel.refresh().join() },
            modifier = Modifier.fillMaxSize(),
            indicatorTopPadding = if (state.hasSession) glassTopBarTopPadding() else 0.dp,
            onRefreshingChange = { pulling = it },
        ) {
            LazyColumn(
                state = pageListState,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
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
                            title = stringResource(R.string.lists_no_session_title),
                            subtitle = stringResource(R.string.lists_no_session_subtitle),
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                }
                items(groups, key = { it.kind }) { group ->
                    ListStrip(
                        group = group,
                        hasMore = when (group.kind) {
                            ListsGroupKind.Collections -> state.collectionsHasMore
                            ListsGroupKind.ReadLists -> state.readListsHasMore
                            ListsGroupKind.SmartLists -> false
                        },
                        loadingMore = state.loadingMore,
                        onLoadMore = when (group.kind) {
                            ListsGroupKind.Collections -> viewModel::loadMoreCollections
                            ListsGroupKind.ReadLists -> viewModel::loadMoreReadLists
                            ListsGroupKind.SmartLists -> ({})
                        },
                        onOpenEntry = { entry -> onOpenEntry(group.kind, entry) },
                        onEntryMenu = { entry -> onEntryMenu(group.kind, entry) },
                        // No session or an empty group means there is nothing to see all of, so the
                        // header stops advertising the page (and draws no chevron) instead of pushing
                        // an empty grid.
                        onOpen = if (state.hasSession) ({ onOpenGroup(group.kind) }) else null,
                    )
                }
            }
        }
        // Snackbars sit above the floating capsule navigation instead of under it
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalNavOverlaySpace.current),
        )
    }
}
