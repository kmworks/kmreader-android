package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BrowseContentType
import com.kmworks.kmreader.data.BrowseOptions
import com.kmworks.kmreader.data.defaultBrowseOptions
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.ReadListsInProgressSectionId
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import com.kmworks.kmreader.ui.components.BookListRow
import com.kmworks.kmreader.ui.components.BrowseFilterButton
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.ContextMenuItem
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.LibraryPinSheet
import com.kmworks.kmreader.ui.components.deviceBrowseLayoutDefault
import com.kmworks.kmreader.ui.components.rememberPersistentBrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.SeriesGridCard
import com.kmworks.kmreader.ui.components.subtitleText

/**
 * Section detail page ("More" destination): adaptive grid/list with three-way layout
 * cycling, library scope menu and browse-options filter sheet. Shares the dashboard's view
 * model (AppRoot hands both the tab's instance), so the page paints the items the strip
 * already loaded and either destination grows the same feed — no page-0 refetch on entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSectionDetailScreen(
    section: DashboardSection<out Any>,
    onBack: () -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onReadBook: (Book, incognito: Boolean) -> Unit = { _, _ -> },
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    // Large grid on tablets, the normal grid on phones
    val (layout, setLayout) = rememberPersistentBrowseLayout(
        "dashboardSection",
        deviceBrowseLayoutDefault(tablet = BrowseLayout.LargeGrid),
    )
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var showPinSheet by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val detail by viewModel.state.collectAsStateWithLifecycle()
    // Follow the live section (the dashboard reloads it); the route's snapshot is the fallback.
    val liveSection = detail.sections?.firstOrNull { it.id == section.id } ?: section
    val items = liveSection.items
    val paging = detail.sectionPaging[section.id]
    val hasMore = paging?.hasMore == true
    val loadingMore = paging?.loadingMore == true
    // The continuations section has no paged feed; its "More" page says so instead of spinning.
    val unsupported = section.id == ReadListsInProgressSectionId
    // A dashboard section is either books or series (its id says which, the loaded items confirm it),
    // so its panel — and its default sort vocabulary — is the matching one.
    val sectionContentType = when {
        items.firstOrNull() is Series -> BrowseContentType.Series
        items.firstOrNull() is Book -> BrowseContentType.Books
        section.id.contains("Series", ignoreCase = true) -> BrowseContentType.Series
        else -> BrowseContentType.Books
    }
    // Keyed on the type: the first frame guesses from the section id, and the options must follow
    // that guess when the loaded items confirm (or correct) it.
    var options by remember(sectionContentType) { mutableStateOf(defaultBrowseOptions(sectionContentType)) }
    var menuBook by remember { mutableStateOf<Book?>(null) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val listShouldLoadMore by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    val gridShouldLoadMore by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    val shouldLoadMore = if (layout == BrowseLayout.List) listShouldLoadMore else gridShouldLoadMore
    LaunchedEffect(shouldLoadMore, hasMore, detail.loading, loadingMore) {
        if (shouldLoadMore && hasMore && !detail.loading && !loadingMore) {
            viewModel.loadMoreSection(section.id)
        }
    }

    // The frosted bar floats inside the page box instead of occupying a Scaffold slot, so the page
    // scrolls underneath it and whatever passes under the row is frosted (same as the tab roots).
    val barHazeState = rememberHazeState()

    // The content box has to start under the status bar: if the Scaffold insets it instead, the
    // scrollers stop at the status bar and the bar has no backdrop for that strip (hard seam).
    Scaffold(contentWindowInsets = glassPageContentWindowInsets()) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            GlassTopBar(
                title = section.title,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
                sheetVisible = listState.hasScrolled() || gridState.hasScrolled(),
                hazeState = barHazeState,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    // three-way layout cycle (list/grid/largeGrid)
                    IconButton(onClick = { setLayout(layout.next) }) {
                        Icon(
                            Icons.Filled.GridView,
                            contentDescription = stringResource(
                                R.string.dashboard_layout,
                                stringResource(layout.labelRes),
                            ),
                        )
                    }
                    // library scope menu (aligned with LibraryScopeMenu)
                    Box {
                        IconButton(onClick = { scopeMenuOpen = true }) {
                            Icon(
                                Icons.Outlined.Checklist,
                                contentDescription = stringResource(R.string.dashboard_library_scope),
                            )
                        }
                        DropdownMenu(
                            expanded = scopeMenuOpen,
                            onDismissRequest = { scopeMenuOpen = false },
                        ) {
                            // Aligned with iOS LibraryScopeMenu: Pinned (when any pinned) / All Libraries / single library / pin editing
                            if (detail.pinnedIds.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.dashboard_scope_pinned)) },
                                    onClick = {
                                        viewModel.setScope(AppSettingsStore.SCOPE_PINNED)
                                        scopeMenuOpen = false
                                    },
                                    trailingIcon = {
                                        if (detail.libraryScope == AppSettingsStore.SCOPE_PINNED) {
                                            Icon(Icons.Filled.Check, contentDescription = null)
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_all_libraries)) },
                                onClick = {
                                    viewModel.setScope(AppSettingsStore.SCOPE_ALL)
                                    scopeMenuOpen = false
                                },
                                trailingIcon = {
                                    if (detail.libraryScope == AppSettingsStore.SCOPE_ALL) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                            )
                            detail.libraries.forEach { library ->
                                val libraryScope = AppSettingsStore.scopeForLibrary(library.id)
                                DropdownMenuItem(
                                    text = { Text(library.name) },
                                    onClick = {
                                        viewModel.setScope(libraryScope)
                                        scopeMenuOpen = false
                                    },
                                    trailingIcon = {
                                        if (detail.libraryScope == libraryScope) {
                                            Icon(Icons.Filled.Check, contentDescription = null)
                                        }
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
                    BrowseFilterButton(
                        options = options,
                        contentType = sectionContentType,
                        onClick = { showOptions = true },
                    )
                },
            )
            val books = items.filterIsInstance<Book>()
            val seriesItems = items.filterIsInstance<Series>()
            when (layout) {
                BrowseLayout.List -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(barHazeState),
                    contentPadding = PaddingValues(top = glassTopBarTopPadding() + 8.dp, bottom = 8.dp),
                ) {
                    items(books, key = { it.id }) { book ->
                        BookListRow(
                            book = book,
                            onClick = { onReadBook(book, false) },
                            onLongClick = { menuBook = book },
                        )
                    }
                    items(seriesItems, key = { it.id }) { series ->
                        SeriesListRow(series = series, onClick = { onOpenSeries(series) })
                    }
                    if (hasMore) {
                        item(key = "load-more") { LoadMoreRow() }
                    }
                }

                else -> LazyVerticalGrid(
                    // grid / largeGrid: adaptive columns sized from the layout card width
                    columns = GridCells.Adaptive(minSize = if (layout == BrowseLayout.LargeGrid) 152.dp else 110.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(barHazeState),
                    contentPadding = PaddingValues(start = 16.dp, top = glassTopBarTopPadding() + 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // horizontal sections degrade to grid cards on this page
                    items(books, key = { it.id }) { book ->
                        BookGridCard(
                            book = book,
                            onClick = { onReadBook(book, false) },
                            onLongClick = { menuBook = book },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(seriesItems, key = { it.id }) { series ->
                        SeriesGridCard(
                            series = series,
                            onClick = { onOpenSeries(series) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (hasMore) {
                        item(key = "load-more", span = { GridItemSpan(maxLineSpan) }) { LoadMoreRow() }
                    }
                }
            }
            // initial page spinner / error hint (both layouts)
            if (detail.loading && items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
            val emptyMessage = when {
                unsupported -> stringResource(R.string.dashboard_error_pagination_unsupported)
                else -> detail.error
            }
            emptyMessage?.let { message ->
                if (items.isEmpty() && !detail.loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showOptions) {
        BrowseOptionsSheet(
            initialOptions = options,
            onConfirm = { options = it },
            onDismiss = { showOptions = false },
            contentType = sectionContentType,
            suggestionLibraryIds = resolveLibraryScopeIds(detail.libraryScope, detail.pinnedIds),
        )
    }
    // Book long-press menu (aligned with the read group of iOS BookContextMenu: Read / Read Incognito / View Details)
    menuBook?.let { book ->
        ContextMenuSheet(
            items = listOf(
                ContextMenuItem(
                    stringResource(R.string.dashboard_action_read),
                    Icons.AutoMirrored.Outlined.MenuBook,
                    onClick = { onReadBook(book, false) },
                ),
                ContextMenuItem(
                    stringResource(R.string.dashboard_action_read_incognito),
                    Icons.Outlined.Visibility,
                    onClick = { onReadBook(book, true) },
                ),
                ContextMenuItem(
                    stringResource(R.string.dashboard_action_view_details),
                    Icons.Outlined.Info,
                    onClick = { onOpenBook(book) },
                ),
            ),
            onDismiss = { menuBook = null },
        )
    }
    // Pin-editing sheet (aligned with iOS LibraryPickerSheet)
    if (showPinSheet) {
        LibraryPinSheet(
            libraries = detail.libraries,
            pinnedIds = detail.pinnedIds,
            onSave = { ids ->
                viewModel.setPinnedLibraries(ids)
                showPinSheet = false
            },
            onDismiss = { showPinSheet = false },
        )
    }
}

/** Bottom loading row: shown while there is a next page, which is requested automatically on reaching it. */
@Composable
private fun LoadMoreRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

/** Series row for list layout (Components has no shared series row). */
@Composable
private fun SeriesListRow(series: Series, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = series.title,
            hue = series.hue,
            stableId = series.id,
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = series.subtitleText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
