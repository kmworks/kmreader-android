package com.kmworks.kmreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.SeriesGridCard
import com.kmworks.kmreader.ui.components.bookContextMenuItems
import com.kmworks.kmreader.ui.components.seriesContextMenuItems

private enum class ContentType(val label: String) { Series("系列"), Books("书籍") }

/** 书库：内容优先浏览（Apple Books 式）。顶部 = 内容类型 chips（系列/书籍，带计数），
 *  其下为范围说明（范围标题 + 体积 caption）；范围（全部/已钉选/单库）在工具栏菜单原位切换，
 *  默认「已钉选」。对齐 KMReader LibraryBrowseView + BrowseContentView。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    activeFilter: String? = null,
    onClearFilter: () -> Unit = {},
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit = {},
) {
    var contentType by remember { mutableStateOf(ContentType.Series) }
    var scope by remember { mutableStateOf("已钉选") }
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    var browseOptions by remember { mutableStateOf(BookBrowseOptions()) }
    // 长按上下文菜单目标（对齐原作卡片 contextMenu）
    var menuSeries by remember { mutableStateOf<Series?>(null) }
    var menuBook by remember { mutableStateOf<Book?>(null) }

    val viewModel: LibraryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    // 有会话且拉到数据用真实内容；否则保持 SampleData 演示
    val series = state.series ?: SampleData.series
    val allBooks = state.books ?: SampleData.series.flatMap { SampleData.booksOf(it).take(2) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书库") },
                actions = {
                    TextButton(onClick = { scopeMenuOpen = true }) {
                        Text(scope)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换书库范围")
                    }
                    DropdownMenu(
                        expanded = scopeMenuOpen,
                        onDismissRequest = { scopeMenuOpen = false },
                    ) {
                        listOf("全部书库", "已钉选", "漫画", "日漫", "国漫").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    scope = option
                                    scopeMenuOpen = false
                                },
                            )
                        }
                    }
                    IconButton(onClick = { showOptionsSheet = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "筛选")
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
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContentType.entries.forEach { type ->
                    val count = if (type == ContentType.Series) series.size else allBooks.size
                    FilterChip(
                        selected = contentType == type,
                        onClick = { contentType = type },
                        label = { Text("${type.label} $count") },
                    )
                }
            }
            Text(
                text = if (state.hasSession) {
                    "$scope · ${state.libraryCount} 个库"
                } else {
                    "$scope · 3 个库 · 共 86 GB"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // 详情页元数据 chip 跳转的目标：显示激活的元数据筛选（出版社/作者/分类/标签/知名实体）
            activeFilter?.let { filter ->
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilterChip(
                        selected = true,
                        onClick = onClearFilter,
                        label = { Text(filter) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清除筛选",
                                modifier = Modifier.padding(2.dp),
                            )
                        },
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (contentType) {
                    ContentType.Series -> items(series, key = { it.id }) { item ->
                        SeriesGridCard(
                            series = item,
                            onClick = { onOpenSeries(item) },
                            modifier = Modifier.fillMaxWidth(),
                            onLongClick = { menuSeries = item },
                            imageUrl = state.thumbnails[item.id],
                        )
                    }
                    ContentType.Books -> items(allBooks, key = { it.id }) { book ->
                        BookGridCard(
                            book = book,
                            onClick = { onOpenBook(book) },
                            modifier = Modifier.fillMaxWidth(),
                            onLongClick = { menuBook = book },
                            imageUrl = state.thumbnails[book.id],
                        )
                    }
                }
            }
        }
    }

    if (showOptionsSheet) {
        BrowseOptionsSheet(
            options = browseOptions,
            onOptionsChange = { browseOptions = it },
            onDismiss = { showOptionsSheet = false },
        )
    }
    menuSeries?.let { series ->
        ContextMenuSheet(
            items = seriesContextMenuItems(
                pinned = false,
                hasStarted = series.progress != null,
                completed = series.progress == 1f,
                downloaded = false,
            ),
            onDismiss = { menuSeries = null },
        )
    }
    menuBook?.let { book ->
        ContextMenuSheet(
            items = bookContextMenuItems(
                hasStarted = book.progress != null,
                completed = book.progress == 1f,
                downloaded = false,
                onOpenSeries = {
                    SampleData.seriesById(book.id / 1000)?.let(onOpenSeries)
                },
            ),
            onDismiss = { menuBook = null },
        )
    }
}
