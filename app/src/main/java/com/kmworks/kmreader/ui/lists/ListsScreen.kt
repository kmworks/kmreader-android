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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.SampleData.ListGroup
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.EmptyState
import com.kmworks.kmreader.ui.components.ContextMenuItem
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.collectionContextMenuItems
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
import com.kmworks.kmreader.ui.components.readListContextMenuItems
import com.kmworks.kmreader.ui.components.smartListContextMenuItems
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.rememberGlassBarProgress
import com.kmworks.kmreader.ui.util.shareText
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Lists tab: mirrors KMReader ListsBrowseView — three horizontal strips of
 *  collections / read lists / smart lists, pinned entries first, each strip header
 *  opens the full page of that type; toolbar scope filter (LibraryScopeMenu). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onOpenCollection: (Int) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onOpenSmartList: (Int) -> Unit = {},
) {
    val groupCollectionsLabel = stringResource(R.string.lists_group_collections)
    val groupReadListsLabel = stringResource(R.string.lists_group_readlists)
    val groupSmartListsLabel = stringResource(R.string.lists_group_smartlists)
    val scopePinnedLabel = stringResource(R.string.lists_scope_pinned)
    val scopeAllLibrariesLabel = stringResource(R.string.common_all_libraries)
    var scope by remember { mutableStateOf(scopePinnedLabel) }
    var scopeMenuOpen by remember { mutableStateOf(false) }
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
        state.collections?.let { if (it.isNotEmpty()) add(ListGroup(groupCollectionsLabel, it)) }
        state.readLists?.let { if (it.isNotEmpty()) add(ListGroup(groupReadListsLabel, it)) }
        state.smartLists?.let { if (it.isNotEmpty()) add(ListGroup(groupSmartListsLabel, it)) }
    }

    // The frosted bar floats over the page: the list groups scroll underneath it.
    val pageListState = rememberLazyListState()
    val barProgress = rememberGlassBarProgress(
        firstVisibleItemIndex = { pageListState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { pageListState.firstVisibleItemScrollOffset },
    )
    val hazeState = rememberHazeState()

    Box(modifier = Modifier.fillMaxSize()) {
        GlassTopBar(
            title = stringResource(R.string.lists_tab_title),
            progress = barProgress,
            modifier = Modifier.zIndex(1f),
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
                        Text(scope)
                    }
                    DropdownMenu(
                        expanded = scopeMenuOpen,
                        onDismissRequest = { scopeMenuOpen = false },
                    ) {
                        // Demo library names are fixture data.
                        listOf(scopeAllLibrariesLabel, scopePinnedLabel, "Comics", "Manga", "Manhua").forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s) },
                                onClick = {
                                    scope = s
                                    scopeMenuOpen = false
                                },
                            )
                        }
                    }
                }
            },
        )
        LazyColumn(
            state = pageListState,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(
                top = if (state.hasSession) glassTopBarTopPadding() + 8.dp else 8.dp,
                bottom = 8.dp + LocalNavOverlaySpace.current,
            ),
        ) {
            if (state.loading) {
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
            items(groups, key = { it.title }) { group ->
                val onOpenEntry: (ListEntry) -> Unit = when (group.title) {
                    groupCollectionsLabel -> ({ e -> onOpenCollection(e.id) })
                    groupReadListsLabel -> ({ e -> onOpenReadList(e.id) })
                    else -> ({ e -> onOpenSmartList(e.id) })
                }
                ListStrip(
                    group = group,
                    hasMore = when (group.title) {
                        groupCollectionsLabel -> state.collectionsHasMore
                        groupReadListsLabel -> state.readListsHasMore
                        else -> false
                    },
                    loadingMore = state.loadingMore,
                    onLoadMore = when (group.title) {
                        groupCollectionsLabel -> viewModel::loadMoreCollections
                        groupReadListsLabel -> viewModel::loadMoreReadLists
                        else -> ({})
                    },
                    onOpenEntry = onOpenEntry,
                    onEntryMenu = { entry -> menuTarget = group.title to entry },
                )
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

/** One horizontal strip: section header (tap to open the full page) + card row;
 *  requests the next page when scrolled to the end. */
@Composable
private fun ListStrip(
    group: ListGroup,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onOpenEntry: (ListEntry) -> Unit,
    onEntryMenu: (ListEntry) -> Unit,
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
                .clickable { }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.common_all),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    group = group,
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

/** List entry card: cover placeholder + title + count; pinned entries carry a pin badge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListEntryCard(
    entry: ListEntry,
    group: ListGroup,
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
        Text(
            text = if (group.title == stringResource(R.string.lists_group_smartlists)) {
                stringResource(
                    R.string.lists_smartlist_meta,
                    entry.count,
                    entry.targetLabel ?: "",
                    entry.modifiedLabel ?: "",
                )
            } else {
                stringResource(R.string.lists_item_count, entry.count)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
