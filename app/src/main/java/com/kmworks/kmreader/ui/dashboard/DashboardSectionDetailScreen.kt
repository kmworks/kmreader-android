package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BookListRow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.SeriesGridCard

/**
 * Section detail page ("More" destination): adaptive grid/list with three-way layout
 * cycling, library scope menu and browse-options filter sheet. Feed items are re-fetched
 * page by page (pageSize 50) and appended on scroll, aligned with DashboardSectionDetailView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSectionDetailScreen(
    section: DashboardSection<out Any>,
    onBack: () -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit = {},
    viewModel: DashboardSectionDetailViewModel = hiltViewModel(),
) {
    var layout by rememberSaveable { mutableStateOf(BrowseLayout.Grid) }
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(BookBrowseOptions()) }

    val detail by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(section.id) { viewModel.start(section) }

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
    LaunchedEffect(shouldLoadMore, detail.hasMore, detail.loading, detail.loadingMore) {
        if (shouldLoadMore && detail.hasMore && !detail.loading && !detail.loadingMore) {
            viewModel.loadMore()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // three-way layout cycle (list/grid/largeGrid)
                    IconButton(onClick = { layout = layout.next }) {
                        Icon(Icons.Filled.GridView, contentDescription = "布局：${layout.label}")
                    }
                    // library scope menu (aligned with LibraryScopeMenu)
                    Box {
                        IconButton(onClick = { scopeMenuOpen = true }) {
                            Icon(Icons.Outlined.Checklist, contentDescription = "库范围")
                        }
                        DropdownMenu(
                            expanded = scopeMenuOpen,
                            onDismissRequest = { scopeMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部") },
                                onClick = {
                                    viewModel.setLibraryScope(null)
                                    scopeMenuOpen = false
                                },
                                trailingIcon = {
                                    if (detail.libraryScope == null) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                            )
                            detail.libraries.forEach { library ->
                                DropdownMenuItem(
                                    text = { Text(library.name) },
                                    onClick = {
                                        viewModel.setLibraryScope(library.id)
                                        scopeMenuOpen = false
                                    },
                                    trailingIcon = {
                                        if (library.id == detail.libraryScope) {
                                            Icon(Icons.Filled.Check, contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showOptions = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "筛选")
                    }
                },
            )
        },
    ) { padding ->
        val books = detail.items.filterIsInstance<Book>()
        val seriesItems = detail.items.filterIsInstance<Series>()
        when (layout) {
            BrowseLayout.List -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    BookListRow(book = book, onClick = { onOpenBook(book) })
                }
                items(seriesItems, key = { it.id }) { series ->
                    SeriesListRow(series = series, onClick = { onOpenSeries(series) })
                }
                if (detail.hasMore) {
                    item(key = "load-more") { LoadMoreRow() }
                }
            }

            else -> LazyVerticalGrid(
                // grid / largeGrid: adaptive columns sized from the layout card width
                columns = GridCells.Adaptive(minSize = if (layout == BrowseLayout.LargeGrid) 152.dp else 110.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // horizontal sections degrade to grid cards on this page
                items(books, key = { it.id }) { book ->
                    BookGridCard(
                        book = book,
                        onClick = { onOpenBook(book) },
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
                if (detail.hasMore) {
                    item(key = "load-more", span = { GridItemSpan(maxLineSpan) }) { LoadMoreRow() }
                }
            }
        }
        // initial page spinner / error hint (both layouts)
        if (detail.loading && detail.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
        detail.error?.let { message ->
            if (detail.items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showOptions) {
        BrowseOptionsSheet(
            options = options,
            onOptionsChange = { options = it },
            onDismiss = { showOptions = false },
        )
    }
}

/** 底部加载行：还有下一页时显示，触底会自动请求。 */
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
                text = series.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
