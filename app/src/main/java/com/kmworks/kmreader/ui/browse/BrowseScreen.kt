package com.kmworks.kmreader.ui.browse

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.SeriesGridCard

/** 搜索标签：搜索框 + 范围 chips + 分组结果。对齐 KMReader browse(search role) 标签。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onOpenCollection: (Int) -> Unit = {},
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(SearchScope.All) }
    // 库范围切换（全部/单库），对应 LibraryScopeMenu；过滤搜索结果
    var selectedLibraryId by remember { mutableStateOf<String?>(null) }
    var scopeMenuOpen by remember { mutableStateOf(false) }
    val selectedLibraryName = state.libraries.firstOrNull { it.id == selectedLibraryId }?.name ?: "全部书库"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                actions = {
                    // 与书库/列表页一致的可见范围切换：文字按钮 + 下拉
                    Box {
                        TextButton(onClick = { scopeMenuOpen = true }) {
                            Icon(
                                Icons.Filled.Checklist,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(selectedLibraryName)
                        }
                        DropdownMenu(
                            expanded = scopeMenuOpen,
                            onDismissRequest = { scopeMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部书库") },
                                onClick = {
                                    selectedLibraryId = null
                                    viewModel.onLibraryChange(null)
                                    scopeMenuOpen = false
                                },
                            )
                            state.libraries.forEach { library ->
                                DropdownMenuItem(
                                    text = { Text(library.name) },
                                    onClick = {
                                        selectedLibraryId = library.id
                                        viewModel.onLibraryChange(library.id)
                                        scopeMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.onQueryChange(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("搜索系列、书籍、作者…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchScope.entries.forEach { s ->
                    FilterChip(
                        selected = s == scope,
                        onClick = {
                            scope = s
                            viewModel.onScopeChange(s)
                        },
                        label = { Text(s.label) },
                    )
                }
            }
            SearchResults(
                state = state,
                query = query,
                onOpenSeries = onOpenSeries,
                onOpenBook = onOpenBook,
                onOpenReadList = onOpenReadList,
                onOpenCollection = onOpenCollection,
            )
        }
    }
}

/** 分组结果区：全部时按 系列/书籍/阅读列表/收藏 分段，保持原有自适应网格。 */
@Composable
private fun SearchResults(
    state: BrowseUiState,
    query: String,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenReadList: (Int) -> Unit,
    onOpenCollection: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
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
            if (state.hasSession && query.isBlank()) {
                item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "输入关键词开始搜索",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else if (state.isEmpty) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "未找到匹配结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else {
                if (state.series.isNotEmpty()) {
                    item(key = "header-series", span = { GridItemSpan(maxLineSpan) }) {
                        ResultSectionHeader("系列")
                    }
                    items(state.series, key = { "series-${it.id}" }) { series ->
                        SeriesGridCard(
                            series = series,
                            onClick = { onOpenSeries(series) },
                            modifier = Modifier.fillMaxWidth(),
                            imageUrl = state.thumbnails[series.id],
                        )
                    }
                }
                if (state.books.isNotEmpty()) {
                    item(key = "header-books", span = { GridItemSpan(maxLineSpan) }) {
                        ResultSectionHeader("书籍")
                    }
                    items(state.books, key = { "book-${it.id}" }) { book ->
                        BookGridCard(
                            book = book,
                            onClick = { onOpenBook(book) },
                            modifier = Modifier.fillMaxWidth(),
                            imageUrl = state.thumbnails[book.id],
                        )
                    }
                }
                if (state.readLists.isNotEmpty()) {
                    item(key = "header-readlists", span = { GridItemSpan(maxLineSpan) }) {
                        ResultSectionHeader("阅读列表")
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
                if (state.collections.isNotEmpty()) {
                    item(key = "header-collections", span = { GridItemSpan(maxLineSpan) }) {
                        ResultSectionHeader("收藏")
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
            }
        }
    }
}

/** 段头（参考 dashboard SectionHeader 风格，仅文字）。 */
@Composable
private fun ResultSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 阅读列表/收藏结果行：图标 + 标题 + 数量，可点进入详情。 */
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
                text = "${entry.count} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
