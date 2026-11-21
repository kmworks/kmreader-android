package com.kmworks.kmreader.ui.lists

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.glassPageContentWindowInsets
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.hasScrolled
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.data.SmartListDetail
import com.kmworks.kmreader.data.SmartListTarget
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BrowseFilterChipFlow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.rememberPersistentBrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.ContextMenuItem
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.SeriesGridCard

private val WideLayoutBreakpoint = 960.dp

/** Smart list detail page (mirrors SmartListDetailView.swift + SmartListDetailWideLayoutView.swift).
 *  Data comes from the kmrs private endpoint /api/v1/smart-lists (stock Komga has no such
 *  endpoint; demo data not registered in DetailIdRegistry falls back to fake data automatically). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListDetailScreen(
    smartList: SmartListDetail,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (Series) -> Unit,
    modifier: Modifier = Modifier,
    onRead: (Book) -> Unit = {},
    onReadIncognito: (Book) -> Unit = {},
    viewModel: SmartListDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(smartList.id) { viewModel.load(smartList.id) }

    val (layout, setLayout) = rememberPersistentBrowseLayout("smartListDetail", BrowseLayout.LargeGrid)
    var options by remember { mutableStateOf(BookBrowseOptions()) }
    var optionsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    val wideListScrollState = rememberScrollState()
    val narrowListScrollState = rememberScrollState()
    // Cache-busting version for "Refresh Cover": re-requests the server cover after incrementing
    var coverVersion by remember { mutableStateOf(0) }
    // Book long-press menu (mirrors the read group of iOS BookContextMenu)
    var menuBook by remember { mutableStateOf<Book?>(null) }

    // Prefer real data; fall back to fake data when the endpoint/session is unavailable
    val display = smartList.copy(
        name = state.name ?: smartList.name,
        summary = state.summary ?: smartList.summary,
        target = state.target ?: smartList.target,
        modifiedLabel = state.modifiedLabel ?: smartList.modifiedLabel,
    )
    val books = state.books ?: emptyList()
    val series = state.series ?: emptyList()

    // The frosted bar floats inside the page box instead of occupying a Scaffold slot, so the page
    // scrolls underneath it and whatever passes under the row is frosted (same as the tab roots).
    val barHazeState = rememberHazeState()
    val railScrollState = rememberScrollState()

    Scaffold(
        modifier = modifier,
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
                                text = { Text(stringResource(R.string.common_edit)) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    editOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_refresh_cover)) },
                                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    coverVersion++
                                },
                            )
                            // Sharing (kmrs extension, admin-only): visibility + sharee picker.
                            // Only server-side real smart lists have a body to update.
                            if (state.hasRealSmartList) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.smartlist_share_title)) },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        shareOpen = true
                                    },
                                )
                            }
                            // Only server-side real smart lists can be deleted; the local fallback
                            // data has nothing to delete, so the item is hidden
                            if (state.hasRealSmartList) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.smartlist_delete), color = MaterialTheme.colorScheme.error) },
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
                    }
                },
            )
            val hitCount = if (display.target == SmartListTarget.Book) books.size else series.size
            val coverHue = if (display.target == SmartListTarget.Book) {
                books.firstOrNull()?.hue
            } else {
                series.firstOrNull()?.hue
            } ?: 210f
            if (maxWidth >= WideLayoutBreakpoint) {
                // Wide layout: left info column (maxOf(maxWidth*0.382f, 340.dp)) + right
                // hit grid, scrolling independently
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
                        SmartListHero(
                            display,
                            hitCount,
                            coverHue,
                            stableId = smartList.id,
                            centered = false,
                            createdLabel = state.createdLabel,
                            coverVersion = coverVersion,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(wideListScrollState)
                            .padding(top = glassTopBarTopPadding() + 8.dp, bottom = 8.dp),
                    ) {
                        SmartListToolbar(
                            options = options,
                            layout = layout,
                            onLayoutChange = { setLayout(it) },
                            onOpenOptions = { optionsOpen = true },
                            onApplyPreset = { options = it.options },
                        )
                        SmartListContent(
                            smartList = display,
                            books = books,
                            series = series,
                            loading = state.loading,
                            layout = layout,
                            onReadBook = onRead,
                            onBookLongClick = { menuBook = it },
                            onOpenSeries = onOpenSeries,
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
                        SmartListHero(
                            display,
                            hitCount,
                            coverHue,
                            stableId = smartList.id,
                            centered = true,
                            createdLabel = state.createdLabel,
                            coverVersion = coverVersion,
                        )
                    }
                    SmartListToolbar(
                        options = options,
                        layout = layout,
                        onLayoutChange = { setLayout(it) },
                        onOpenOptions = { optionsOpen = true },
                        onApplyPreset = { options = it.options },
                    )
                    SmartListContent(
                        smartList = display,
                        books = books,
                        series = series,
                        loading = state.loading,
                        layout = layout,
                        onReadBook = onRead,
                        onBookLongClick = { menuBook = it },
                        onOpenSeries = onOpenSeries,
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
        )
    }
    // Book long-press menu (mirrors the read group of iOS BookContextMenu: Read / Read
    // Incognito / View Details)
    menuBook?.let { book ->
        ContextMenuSheet(
            items = listOf(
                ContextMenuItem(
                    stringResource(R.string.lists_read),
                    Icons.AutoMirrored.Outlined.MenuBook,
                    onClick = { onRead(book) },
                ),
                ContextMenuItem(
                    stringResource(R.string.lists_read_incognito),
                    Icons.Outlined.Visibility,
                    onClick = { onReadIncognito(book) },
                ),
                ContextMenuItem(
                    stringResource(R.string.lists_view_details),
                    Icons.Outlined.Info,
                    onClick = { onOpenBook(book) },
                ),
            ),
            onDismiss = { menuBook = null },
        )
    }
    if (deleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.lists_delete_item_title, stringResource(R.string.smartlist_title)),
            message = stringResource(R.string.smartlist_delete_message, display.name),
            onConfirm = {
                deleteConfirm = false
                viewModel.delete { onBack() }
            },
            onDismiss = { deleteConfirm = false },
        )
    }
    if (shareOpen) {
        SmartListShareSheet(
            stableId = smartList.id,
            onDismiss = { shareOpen = false },
            onSaved = { shareOpen = false },
        )
    }
    if (editOpen) {
        // The editor does not reverse-parse the server search document: rules are built
        // from scratch and overwrite on save (name/summary keep their current values)
        SmartListEditSheet(
            onSave = { name, _, matchAll, rules ->
                viewModel.save(
                    name = name,
                    summary = display.summary ?: "",
                    search = buildSmartListSearchDocument(matchAll, rules),
                ) { ok ->
                    // Keep the editor open when saving fails, to avoid silently losing changes
                    if (ok) editOpen = false
                }
            },
            onDismiss = { editOpen = false },
            initialName = display.name,
            initialTarget = display.target,
            isEdit = true,
            onDelete = if (state.hasRealSmartList) {
                {
                    editOpen = false
                    deleteConfirm = true
                }
            } else {
                null
            },
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

/** Hero: cover thumbnail + name + target type/hit count + summary, then one
 *  created/updated timestamps line (mirrors SmartListHeroInfoView + DetailTimestampsView). */
@Composable
private fun SmartListHero(
    display: SmartListDetail,
    hitCount: Int,
    coverHue: Float,
    centered: Boolean,
    stableId: Int? = null,
    createdLabel: String? = null,
    coverVersion: Int = 0,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        // Cover = /api/v1/smart-lists/{id}/thumbnail (kmrs endpoint)
        CoverArt(
            title = display.name,
            hue = coverHue,
            stableId = stableId,
            coverVersion = coverVersion,
            modifier = Modifier
                .width(168.dp)
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = display.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (display.target == SmartListTarget.Book) Icons.AutoMirrored.Outlined.MenuBook
                else Icons.Outlined.CollectionsBookmark,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    R.string.smartlist_target_meta,
                    stringResource(if (display.target == SmartListTarget.Book) R.string.common_book else R.string.common_series),
                    hitCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        display.summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        // One timestamps line for both dates: the updated date used to also sit in the meta row
        // above, which printed "更新于 …" twice on this page.
        val timestamps = listOfNotNull(
            createdLabel?.takeIf { it.isNotBlank() },
            display.modifiedLabel.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (timestamps.isNotBlank()) {
            Text(
                text = timestamps,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Toolbar row: filter chip flow (mirrors the filter row of SmartListBooksListView /
 *  SmartListSeriesListView). A smart list is a server-side saved filter whose members
 *  cannot be added or removed, so there are no batch actions. */
@Composable
private fun SmartListToolbar(
    options: BookBrowseOptions,
    layout: BrowseLayout,
    onLayoutChange: (BrowseLayout) -> Unit,
    onOpenOptions: () -> Unit,
    onApplyPreset: (com.kmworks.kmreader.data.SavedFilter) -> Unit,
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
    }
}

/** Hit content grid: renders book or series cards by target type (mirrors the hit
 *  area of the Smart List detail). */
@Composable
private fun SmartListContent(
    smartList: SmartListDetail,
    books: List<Book>,
    series: List<Series>,
    loading: Boolean,
    layout: BrowseLayout,
    onReadBook: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onOpenSeries: (Series) -> Unit,
) {
    val itemsEmpty = if (smartList.target == SmartListTarget.Book) books.isEmpty() else series.isEmpty()
    if (loading && itemsEmpty) {
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
    val minCell = if (layout == BrowseLayout.LargeGrid) 160 else 108
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        val columns = maxOf(2, ((maxWidth + 12.dp) / (minCell.dp + 12.dp)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (smartList.target == SmartListTarget.Book) {
                books.chunked(columns).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { book ->
                            BookGridCard(
                                book = book,
                                onClick = { onReadBook(book) },
                                onLongClick = { onBookLongClick(book) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else {
                series.chunked(columns).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { item ->
                            SeriesGridCard(
                                series = item,
                                onClick = { onOpenSeries(item) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
