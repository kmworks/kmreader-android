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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Checklist
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

private val scopeOptions = listOf("全部", "已钉选", "漫画", "画集")

/**
 * Section detail page ("More" destination): adaptive grid/list with three-way layout
 * cycling, library scope menu and browse-options filter sheet.
 * Aligned with DashboardSectionDetailView (+ dashboardSectionDetailLayout).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSectionDetailScreen(
    section: DashboardSection<out Any>,
    onBack: () -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit = {},
) {
    var layout by rememberSaveable { mutableStateOf(BrowseLayout.Grid) }
    var scope by rememberSaveable { mutableStateOf(scopeOptions.first()) }
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(BookBrowseOptions()) }

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
                            scopeOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        scope = option
                                        scopeMenuOpen = false
                                    },
                                    trailingIcon = {
                                        if (option == scope) {
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
        val books = section.items.filterIsInstance<Book>()
        val seriesItems = section.items.filterIsInstance<Series>()
        when (layout) {
            BrowseLayout.List -> LazyColumn(
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
            }

            else -> LazyVerticalGrid(
                // grid / largeGrid: adaptive columns sized from the layout card width
                columns = GridCells.Adaptive(minSize = if (layout == BrowseLayout.LargeGrid) 152.dp else 110.dp),
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
