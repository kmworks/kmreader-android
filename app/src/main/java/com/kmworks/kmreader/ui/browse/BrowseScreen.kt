package com.kmworks.kmreader.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Checklist
import com.kmworks.kmreader.ui.components.rememberBrowseFilterSuggestions
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.BrowseSortChip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.BrowseContentType
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.activeFilterLabels
import com.kmworks.kmreader.ui.components.BookListRow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.ContextMenuItem
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.LibraryPinSheet
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
import com.kmworks.kmreader.ui.components.SeriesGridCard
import com.kmworks.kmreader.ui.components.SeriesListRow
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.libraryScopeLabel
import com.kmworks.kmreader.ui.components.deviceBrowseLayoutDefault
import com.kmworks.kmreader.ui.components.rememberPersistentBrowseLayout

/** Search tab: search field + scope chips + grouped results. Mirrors the KMReader browse (search role) tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onOpenCollection: (Int) -> Unit = {},
    onReadBook: (Book, incognito: Boolean) -> Unit = { _, _ -> },
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(SearchScope.All) }
    // Library scope switch (pinned/all/single library + pin editing), mirroring iOS LibraryScopeMenu;
    // the search tab scope is persisted per server and defaults to all libraries
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var showPinSheet by remember { mutableStateOf(false) }
    var menuBook by remember { mutableStateOf<Book?>(null) }
    // Results grid layout (list/grid/large grid, persisted per page, mirroring iOS per-page layout memory):
    // large grid on tablets, the normal grid on phones
    val (resultsLayout, setResultsLayout) = rememberPersistentBrowseLayout(
        "search",
        deviceBrowseLayoutDefault(tablet = BrowseLayout.LargeGrid),
    )
    var showFilterSheet by remember { mutableStateOf(false) }
    val seriesBrowseOptions by viewModel.seriesBrowseOptionsFlow.collectAsStateWithLifecycle()
    val booksBrowseOptions by viewModel.booksBrowseOptionsFlow.collectAsStateWithLifecycle()
    // Filters belong to the dedicated series/books scopes. In the mixed All scope (whose series/books
    // results would otherwise be narrowed behind the user's back) and in the collections/read lists scopes
    // (whose endpoints take no filter conditions) the chip is disabled and nothing is applied.
    val filtersEnabled = scope == SearchScope.Series || scope == SearchScope.Books
    val filterContentType =
        if (scope == SearchScope.Books) BrowseContentType.Books else BrowseContentType.Series
    val filterOptions =
        if (filterContentType == BrowseContentType.Books) booksBrowseOptions else seriesBrowseOptions

    // Same floating frosted bar as the other tab roots (dashboard / library / lists): a stock M3 TopAppBar
    // painted its own surface-coloured strip above the page, which read as a separate band instead of
    // belonging to the content.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The glass bar draws over the page instead of reserving layout space, so the search field
                // has to start below it explicitly.
                .padding(top = glassTopBarTopPadding() + 4.dp),
        ) {
            SearchField(
                query = query,
                onQueryChange = {
                    query = it
                    viewModel.onQueryChange(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Layout toggle chip (mirrors the BrowseFilterChipFlow layout chip: cycles list → grid → large grid)
                AssistChip(
                    onClick = { setResultsLayout(resultsLayout.next) },
                    label = { Text(stringResource(resultsLayout.labelRes)) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                    },
                )
                // Filter chip (extra conditions layered on series/books full-text search, mirroring the iOS
                // search tab filters). It carries the selected state, so an applied filter is visible in the
                // chip row instead of only inside the panel. Disabled outside the series/books scopes.
                val filterCount =
                    if (filtersEnabled) filterOptions.activeFilterLabels(filterContentType).size else 0
                FilterChip(
                    selected = filtersEnabled && filterCount > 0,
                    enabled = filtersEnabled,
                    onClick = { if (filtersEnabled) showFilterSheet = true },
                    label = {
                        Text(
                            if (filterCount > 0) {
                                stringResource(R.string.browse_filter_count, filterCount)
                            } else {
                                stringResource(R.string.browse_filter)
                            },
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.FilterList, contentDescription = null)
                    },
                )
                // Current sort only: the panel keeps the conditions, the chip row shows how the results
                // are ordered. Only the series/books scopes have a filter panel at all.
                if (filtersEnabled) {
                    BrowseSortChip(
                        options = filterOptions,
                        contentType = filterContentType,
                        onOpenOptions = { showFilterSheet = true },
                    )
                }
                SearchScope.entries.forEach { s ->
                    FilterChip(
                        selected = s == scope,
                        onClick = {
                            scope = s
                            viewModel.onScopeChange(s)
                        },
                        label = { Text(stringResource(s.labelRes)) },
                    )
                }
            }
            SearchResults(
                state = state,
                query = query,
                scope = scope,
                layout = resultsLayout,
                onShowMore = { target ->
                    scope = target
                    viewModel.onScopeChange(target)
                },
                onOpenSeries = onOpenSeries,
                onOpenBook = onOpenBook,
                onOpenReadList = onOpenReadList,
                onOpenCollection = onOpenCollection,
                onLoadMore = viewModel::loadMore,
                onLoadMoreKind = viewModel::loadMoreKind,
                onReadBook = { book -> onReadBook(book, false) },
                onBookLongClick = { menuBook = it },
            )

            if (showFilterSheet && filtersEnabled) {
                BrowseOptionsSheet(
                    initialOptions = filterOptions,
                    onConfirm = { options -> viewModel.setBrowseOptions(filterContentType, options) },
                    onDismiss = { showFilterSheet = false },
                    // Each dedicated scope edits its own set: Series edits the series filters, Books the
                    // book filters. Both stay stored while the chip is disabled in the other scopes.
                    contentType = filterContentType,
                    suggestionLibraryIds = resolveLibraryScopeIds(state.libraryScope, state.pinnedIds),
                )
            }
        }

        GlassTopBar(
            title = stringResource(R.string.common_search),
            // The query field and filter chips are a fixed header here, so nothing ever slides under the
            // bar: there would be nothing to frost, so this page keeps the bare title floating over the
            // page background.
            sheetVisible = false,
            modifier = Modifier.zIndex(1f),
            actions = {
                // Same visibility-scope switch as the library/list pages: text button + dropdown
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
                        // Mirrors iOS LibraryScopeMenu: Pinned (when pins exist) / All Libraries / single library / pin editing
                        if (state.pinnedIds.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_scope_pinned)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.PushPin, contentDescription = null)
                                },
                                onClick = {
                                    viewModel.onLibraryScopeChange(AppSettingsStore.SCOPE_PINNED)
                                    scopeMenuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_all_libraries)) },
                            onClick = {
                                viewModel.onLibraryScopeChange(AppSettingsStore.SCOPE_ALL)
                                scopeMenuOpen = false
                            },
                        )
                        state.libraries.forEach { library ->
                            DropdownMenuItem(
                                text = { Text(library.name) },
                                onClick = {
                                    viewModel.onLibraryScopeChange(
                                        AppSettingsStore.scopeForLibrary(library.id),
                                    )
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
            },
        )
    }
    // Book long-press menu (mirrors the read group of iOS BookContextMenu: Read/Peek/View Details)
    menuBook?.let { book ->
        ContextMenuSheet(
            items = listOf(
                ContextMenuItem(
                    stringResource(R.string.book_read),
                    Icons.AutoMirrored.Outlined.MenuBook,
                    onClick = { onReadBook(book, false) },
                ),
                ContextMenuItem(
                    stringResource(R.string.book_peek),
                    Icons.Outlined.Visibility,
                    onClick = { onReadBook(book, true) },
                ),
                ContextMenuItem(
                    stringResource(R.string.book_view_details),
                    Icons.Outlined.Info,
                    onClick = { onOpenBook(book) },
                ),
            ),
            onDismiss = { menuBook = null },
        )
    }
    // Pin-editing sheet (mirrors iOS LibraryPickerSheet)
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
}

/** Grouped results area: a single scope is an adaptive grid with bottom paging; "All" is a dashboard-style
 *  grouped horizontal-scroll preview with a "More" entry per group. */
@Composable
private fun SearchResults(
    state: BrowseUiState,
    query: String,
    scope: SearchScope,
    layout: BrowseLayout,
    onShowMore: (SearchScope) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenReadList: (Int) -> Unit,
    onOpenCollection: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onLoadMoreKind: (SearchScope) -> Unit,
    onReadBook: (Book) -> Unit = {},
    onBookLongClick: (Book) -> Unit = {},
) {
    if (scope == SearchScope.All) {
        GroupedSearchResults(
            state = state,
            query = query,
            layout = layout,
            onShowMore = onShowMore,
            onLoadMoreKind = onLoadMoreKind,
            onOpenSeries = onOpenSeries,
            onOpenReadList = onOpenReadList,
            onOpenCollection = onOpenCollection,
            onReadBook = onReadBook,
            onBookLongClick = onBookLongClick,
        )
        return
    }
    val gridState = rememberLazyGridState()
    // Bottom detection: request the next page once the last visible item enters the final 6;
    // the VM guards against loading/no-more itself.
    val shouldLoadMore by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, state.hasMore, state.loading, state.loadingMore) {
        if (shouldLoadMore && state.hasMore && !state.loading && !state.loadingMore) onLoadMore()
    }
    val gridColumns = when (layout) {
        BrowseLayout.List -> GridCells.Fixed(1)
        BrowseLayout.Grid -> GridCells.Adaptive(minSize = 110.dp)
        BrowseLayout.LargeGrid -> GridCells.Adaptive(minSize = 160.dp)
    }
    LazyVerticalGrid(
        columns = gridColumns,
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 16.dp + LocalNavOverlaySpace.current,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.loading) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }
        state.error?.let { error ->
            item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        if (!state.loading && state.error == null) {
            if (!state.hasSession) {
                item(key = "hint-offline", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.browse_hint_offline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else if (query.isBlank()) {
                item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.browse_hint_enter_query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else if (state.isEmpty) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.browse_hint_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else {
                if (state.series.isNotEmpty()) {
                    item(key = "header-series", span = { GridItemSpan(maxLineSpan) }) {
                        ResultSectionHeader(stringResource(R.string.common_series))
                    }
                    items(
                        state.series,
                        key = { "series-${it.id}" },
                        span = if (layout == BrowseLayout.List) {
                            { GridItemSpan(maxLineSpan) }
                        } else {
                            null
                        },
                    ) { series ->
                        if (layout == BrowseLayout.List) {
                            SeriesListRow(
                                series = series,
                                onClick = { onOpenSeries(series) },
                            )
                        } else {
                            SeriesGridCard(
                                series = series,
                                onClick = { onOpenSeries(series) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (state.books.isNotEmpty()) {
                    item(key = "header-books", span = { GridItemSpan(maxLineSpan) }) {
                        ResultSectionHeader(stringResource(R.string.browse_scope_books))
                    }
                    items(
                        state.books,
                        key = { "book-${it.id}" },
                        span = if (layout == BrowseLayout.List) {
                            { GridItemSpan(maxLineSpan) }
                        } else {
                            null
                        },
                    ) { book ->
                        if (layout == BrowseLayout.List) {
                            BookListRow(
                                book = book,
                                onClick = { onReadBook(book) },
                                onLongClick = { onBookLongClick(book) },
                            )
                        } else {
                            BookGridCard(
                                book = book,
                                onClick = { onReadBook(book) },
                                onLongClick = { onBookLongClick(book) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                // Collections before read lists, matching the lists tab's group order.
                if (state.collections.isNotEmpty()) {
                    item(key = "header-collections", span = { GridItemSpan(maxLineSpan) }) {
                        ResultSectionHeader(stringResource(R.string.browse_scope_collections))
                    }
                    items(
                        state.collections,
                        key = { "collection-${it.id}" },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { entry ->
                        SearchListRow(
                            entry = entry,
                            icon = Icons.Filled.CollectionsBookmark,
                            onClick = { onOpenCollection(entry.id) },
                        )
                    }
                }
                if (state.readLists.isNotEmpty()) {
                    item(key = "header-readlists", span = { GridItemSpan(maxLineSpan) }) {
                        ResultSectionHeader(stringResource(R.string.browse_scope_readlists))
                    }
                    items(
                        state.readLists,
                        key = { "readlist-${it.id}" },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { entry ->
                        SearchListRow(
                            entry = entry,
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            onClick = { onOpenReadList(entry.id) },
                        )
                    }
                }
            }
        }
        // Bottom loading row: shown while another page exists; reaching the bottom auto-requests it
        if (!state.loading && state.hasMore) {
            item(key = "load-more", span = { GridItemSpan(maxLineSpan) }) {
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

/** Section header (dashboard SectionHeader style, text only). */
@Composable
private fun ResultSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** "All" results: dashboard-style groups — a section header (title + "More ›") plus a horizontally
 *  scrolling preview per group. Previews always use horizontal cards (list layout does not apply);
 *  card width follows the grid setting: grid = medium card / large grid = large card. */
@Composable
private fun GroupedSearchResults(
    state: BrowseUiState,
    query: String,
    layout: BrowseLayout,
    onShowMore: (SearchScope) -> Unit,
    onLoadMoreKind: (SearchScope) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenReadList: (Int) -> Unit,
    onOpenCollection: (Int) -> Unit,
    onReadBook: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
) {
    // Group preview card width matches the dashboard card tiers (large grid uses large cards)
    val previewCardWidth = if (layout == BrowseLayout.LargeGrid) {
        DashboardCardKind.Large.widthDp.dp
    } else {
        DashboardCardKind.Medium.widthDp.dp
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalNavOverlaySpace.current),
    ) {
        if (state.loading) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }
        state.error?.let { error ->
            item(key = "error") {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp),
                )
            }
        }
        if (!state.loading && state.error == null) {
            when {
                !state.hasSession -> item(key = "hint-offline") {
                    SearchHint(stringResource(R.string.browse_hint_offline))
                }
                query.isBlank() -> item(key = "hint") {
                    SearchHint(stringResource(R.string.browse_hint_enter_query))
                }
                state.isEmpty -> item(key = "empty") {
                    SearchHint(stringResource(R.string.browse_hint_no_results))
                }
                else -> {
                    if (state.series.isNotEmpty()) {
                        item(key = "section-series") {
                            SearchGroup(
                                title = stringResource(R.string.common_series),
                                count = state.seriesTotal,
                                showMore = state.seriesHasMore,
                                onShowMore = { onShowMore(SearchScope.Series) },
                                hasMore = state.seriesHasMore,
                                loadingMore = state.loadingMore,
                                onLoadMore = { onLoadMoreKind(SearchScope.Series) },
                            ) {
                                items(state.series, key = { "series-${it.id}" }) { series ->
                                    SeriesGridCard(
                                        series = series,
                                        onClick = { onOpenSeries(series) },
                                        modifier = Modifier.width(previewCardWidth),
                                        reserveProgressSpace = true,
                                    )
                                }
                            }
                        }
                    }
                    if (state.books.isNotEmpty()) {
                        item(key = "section-books") {
                            SearchGroup(
                                title = stringResource(R.string.browse_scope_books),
                                count = state.booksTotal,
                                showMore = state.booksHasMore,
                                onShowMore = { onShowMore(SearchScope.Books) },
                                hasMore = state.booksHasMore,
                                loadingMore = state.loadingMore,
                                onLoadMore = { onLoadMoreKind(SearchScope.Books) },
                            ) {
                                items(state.books, key = { "book-${it.id}" }) { book ->
                                    BookGridCard(
                                        book = book,
                                        onClick = { onReadBook(book) },
                                        onLongClick = { onBookLongClick(book) },
                                        modifier = Modifier.width(previewCardWidth),
                                        reserveProgressSpace = true,
                                    )
                                }
                            }
                        }
                    }
                    // Collections before read lists, matching the lists tab's group order.
                    if (state.collections.isNotEmpty()) {
                        item(key = "section-collections") {
                            SearchGroup(
                                title = stringResource(R.string.browse_scope_collections),
                                count = state.collectionsTotal,
                                showMore = state.collectionsHasMore,
                                onShowMore = { onShowMore(SearchScope.Collections) },
                                hasMore = state.collectionsHasMore,
                                loadingMore = state.loadingMore,
                                onLoadMore = { onLoadMoreKind(SearchScope.Collections) },
                            ) {
                                items(state.collections, key = { "collection-${it.id}" }) { entry ->
                                    SearchListCard(
                                        entry = entry,
                                        icon = Icons.Filled.CollectionsBookmark,
                                        onClick = { onOpenCollection(entry.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (state.readLists.isNotEmpty()) {
                        item(key = "section-readlists") {
                            SearchGroup(
                                title = stringResource(R.string.browse_scope_readlists),
                                count = state.readListsTotal,
                                showMore = state.readListsHasMore,
                                onShowMore = { onShowMore(SearchScope.ReadLists) },
                                hasMore = state.readListsHasMore,
                                loadingMore = state.loadingMore,
                                onLoadMore = { onLoadMoreKind(SearchScope.ReadLists) },
                            ) {
                                items(state.readLists, key = { "readlist-${it.id}" }) { entry ->
                                    SearchListCard(
                                        entry = entry,
                                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                                        onClick = { onOpenReadList(entry.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Search input: a filled pill instead of the outlined M3 field. The stock OutlinedTextField drew a 1dp
 * white outline around a full-width square, which read as a bare box on the dark page; the pill fills with
 * a surface role, keeps a hairline only for definition (brighter while focused) and carries a trailing
 * clear button.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)
    val container =
        if (focused) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val outline =
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(container, shape)
            .border(1.dp, outline, shape),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.browse_search_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    // Stock M3 tonal icon button: the circular container marks the hit target, and the
                    // standard Close glyph keeps the control consistent with the rest of the app.
                    FilledIconButton(
                        onClick = { onQueryChange("") },
                        // 28dp keeps the button at ~60% of the 48dp pill, like the reference: at 32dp the
                        // circle filled the bar and crowded the query text.
                        modifier = Modifier.size(28.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            // Tonal container from the palette: readable on both the resting (#1C1C1F) and
                            // focused (#242428) pill, where surfaceVariant would sink into the fill.
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.browse_search_clear),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        },
    )
}

/** Group container: section header (title · total + "More ›") + horizontal LazyRow (content slot).
 *  "More" is hidden when there are no further results; the strip itself pages when scrolled to
 *  its end, so reaching the dedicated page is no longer the only way to see further results. */
@Composable
private fun SearchGroup(
    title: String,
    count: Long? = null,
    showMore: Boolean,
    onShowMore: () -> Unit,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    // Strip end detection: request this kind's next page once the last visible card is within the
    // final 3, and keep requesting while still parked at the end after an append.
    val stripState = rememberLazyListState()
    val shouldLoadMore by remember(stripState) {
        derivedStateOf {
            val info = stripState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, loadingMore, stripState.layoutInfo.totalItemsCount) {
        if (shouldLoadMore && hasMore && !loadingMore) onLoadMore()
    }
    Column(modifier = Modifier.padding(top = 12.dp)) {
        // Fixed height: the title-to-cards gap must not depend on whether "More" is drawn (a TextButton
        // brought its own 48dp touch height, so groups without further results sat tighter).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (count != null) {
                // Server-side total ("系列 · 65"), the muted number of the reference search UI.
                Text(
                    text = " · $count",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (showMore) {
                // Plain clickable row instead of a TextButton: its content padding kept "More" inset from
                // the 16dp card edge. Bottom padding keeps a 44dp touch target inside the fixed header.
                Row(
                    modifier = Modifier
                        .clickable(onClick = onShowMore)
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.common_more),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        LazyRow(
            state = stripState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
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

/** Hint row (empty-state / guidance for grouped results). */
@Composable
private fun SearchHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp),
    )
}

/** Read list / collection horizontal-scroll card: icon + title + count (rounded neutral background
 *  matching the dashboard card style). */
@Composable
private fun SearchListCard(entry: SampleData.ListEntry, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .width(200.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pluralStringResource(R.plurals.browse_entry_count, entry.count, entry.count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Read list / collection result row: icon + title + count; tapping opens the detail page. */
@Composable
private fun SearchListRow(entry: SampleData.ListEntry, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = entry.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = pluralStringResource(R.plurals.browse_entry_count, entry.count, entry.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
