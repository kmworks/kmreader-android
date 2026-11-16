package com.kmworks.kmreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.ui.components.EmptyState
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BookListRow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.rememberBrowseFilterSuggestions
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.SeriesGridCard
import com.kmworks.kmreader.ui.components.SeriesListRow
import com.kmworks.kmreader.ui.components.rememberPersistentBrowseLayout
import com.kmworks.kmreader.ui.components.bookContextMenuItems
import com.kmworks.kmreader.ui.components.seriesContextMenuItems
import com.kmworks.kmreader.ui.lists.AddToListSheet

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
    onVisitSeries: (Book) -> Unit = {},
    onReadBook: (Book, incognito: Boolean) -> Unit = { _, _ -> },
) {
    var contentType by remember { mutableStateOf(ContentType.Series) }
    // 布局逐内容类型持久化（对齐 iOS seriesBrowseLayout / bookBrowseLayout，默认 grid）
    val (seriesLayout, setSeriesLayout) = rememberPersistentBrowseLayout("librarySeries", BrowseLayout.Grid)
    val (booksLayout, setBooksLayout) = rememberPersistentBrowseLayout("libraryBooks", BrowseLayout.Grid)
    val layout = if (contentType == ContentType.Series) seriesLayout else booksLayout
    val setLayout = if (contentType == ContentType.Series) setSeriesLayout else setBooksLayout
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var showPinSheet by remember { mutableStateOf(false) }
    // 「加入收藏/阅读列表」sheet 目标
    var addTarget by remember { mutableStateOf<Any?>(null) }
    var addToCollectionOpen by remember { mutableStateOf(false) }
    var addToReadListOpen by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    // 长按上下文菜单目标（对齐原作卡片 contextMenu）
    var menuSeries by remember { mutableStateOf<Series?>(null) }
    var menuBook by remember { mutableStateOf<Book?>(null) }

    val viewModel: LibraryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    // real content only; empty state when there is no session or nothing loaded
    val series = state.series ?: emptyList()
    val allBooks = state.books ?: emptyList()
    // 当前范围显示名（对齐 iOS LibraryScopeMenu）
    val scopeLabel = when {
        state.scope == AppSettingsStore.SCOPE_ALL -> "全部书库"
        state.scope == AppSettingsStore.SCOPE_PINNED -> "已钉选"
        state.scope.startsWith("library:") -> {
            val id = state.scope.removePrefix("library:")
            state.libraries.find { it.id == id }?.name ?: "单个书库"
        }
        else -> "全部书库"
    }

    // 详情页元数据 chip 跳转：把「作者 · X」「标签 · Y」翻译成真实浏览筛选并落到查询里
    var appliedFilter by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(activeFilter) {
        val applied = appliedFilter
        if (activeFilter == null) {
            // 清除筛选时同步移除 chip 带来的作者/标签条件
            if (applied != null) {
                val current = state.browseOptions
                viewModel.setBrowseOptions(
                    when (applied.first) {
                        "作者" -> current.copy(authors = current.authors - applied.second)
                        "标签" -> current.copy(tags = current.tags - applied.second)
                        else -> current
                    },
                )
                appliedFilter = null
            }
            return@LaunchedEffect
        }
        val parts = activeFilter.split("·", limit = 2).map { it.trim() }
        if (parts.size != 2 || parts[1].isBlank()) return@LaunchedEffect
        val (kind, value) = parts
        val current = state.browseOptions
        when (kind) {
            "作者" -> if (value !in current.authors) {
                viewModel.setBrowseOptions(current.copy(authors = current.authors + value))
            }
            "标签" -> if (value !in current.tags) {
                viewModel.setBrowseOptions(current.copy(tags = current.tags + value))
            }
            else -> Unit
        }
        appliedFilter = kind to value
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书库") },
                actions = {
                    TextButton(onClick = { scopeMenuOpen = true }) {
                        Text(scopeLabel)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换书库范围")
                    }
                    DropdownMenu(
                        expanded = scopeMenuOpen,
                        onDismissRequest = { scopeMenuOpen = false },
                    ) {
                        // 对齐 iOS LibraryScopeMenu：已钉选（有钉选时）/全部书库/单库/钉选编辑
                        if (state.pinnedIds.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("已钉选") },
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
                            text = { Text("全部书库") },
                            onClick = {
                                viewModel.setScope(AppSettingsStore.SCOPE_ALL)
                                scopeMenuOpen = false
                            },
                        )
                        state.libraries.forEach { library ->
                            DropdownMenuItem(
                                text = { Text(library.name) },
                                onClick = {
                                    viewModel.setScope(AppSettingsStore.scopeForLibrary(library.id))
                                    scopeMenuOpen = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("钉选书库…") },
                            onClick = {
                                scopeMenuOpen = false
                                showPinSheet = true
                            },
                        )
                    }
                    IconButton(onClick = { setLayout(layout.next) }) {
                        Icon(Icons.Filled.GridView, contentDescription = "布局：${layout.label}")
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
                    // 对齐 iOS 书库标题：显示服务端总量，无分页数据时回退已加载数
                    val count = if (type == ContentType.Series) {
                        state.seriesTotal ?: series.size.toLong()
                    } else {
                        state.booksTotal ?: allBooks.size.toLong()
                    }
                    FilterChip(
                        selected = contentType == type,
                        onClick = { contentType = type },
                        label = { Text("${type.label} $count") },
                    )
                }
            }
            Text(
                text = if (state.hasSession) {
                    "$scopeLabel · ${state.libraryCount} 个库"
                } else {
                    scopeLabel
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
            if (!state.hasSession) {
                EmptyState(
                    title = "未连接服务器",
                    subtitle = "登录后浏览书库",
                    modifier = Modifier.weight(1f),
                )
            } else {
                // 系列/书籍各自独立滚动位置；触底自动加载下一页
                val seriesGridState = rememberLazyGridState()
                val booksGridState = rememberLazyGridState()
                val gridState = if (contentType == ContentType.Series) seriesGridState else booksGridState
                val seriesListState = rememberLazyListState()
                val booksListState = rememberLazyListState()
                val listState = if (contentType == ContentType.Series) seriesListState else booksListState
                val gridShouldLoadMore by remember(contentType, gridState) {
                    derivedStateOf {
                        val info = gridState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                            ?: return@derivedStateOf false
                        info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
                    }
                }
                val listShouldLoadMore by remember(contentType, listState) {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                            ?: return@derivedStateOf false
                        info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
                    }
                }
                val shouldLoadMore = if (layout == BrowseLayout.List) listShouldLoadMore else gridShouldLoadMore
                val hasMore = when (contentType) {
                    ContentType.Series -> state.seriesHasMore
                    ContentType.Books -> state.booksHasMore
                }
                // loadedCount 进入 key：追加一页后仍停在底部时继续请求下一页
                val loadedCount = if (contentType == ContentType.Series) series.size else allBooks.size
                LaunchedEffect(shouldLoadMore, contentType, hasMore, loadedCount) {
                    if (!shouldLoadMore || !hasMore) return@LaunchedEffect
                    when (contentType) {
                        ContentType.Series -> viewModel.loadMoreSeries()
                        ContentType.Books -> viewModel.loadMoreBooks()
                    }
                }
                when (layout) {
                    BrowseLayout.List -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        when (contentType) {
                            ContentType.Series -> items(series.size, key = { series[it].id }) { index ->
                                val item = series[index]
                                SeriesListRow(
                                    series = item,
                                    onClick = { onOpenSeries(item) },
                                    onLongClick = { menuSeries = item },
                                )
                            }
                            ContentType.Books -> items(allBooks.size, key = { allBooks[it].id }) { index ->
                                val book = allBooks[index]
                                BookListRow(
                                    book = book,
                                    onClick = { onOpenBook(book) },
                                    onLongClick = { menuBook = book },
                                )
                            }
                        }
                        if (hasMore) {
                            item(key = "load-more") {
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

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (layout == BrowseLayout.LargeGrid) 152.dp else 110.dp),
                        state = gridState,
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
                                )
                            }
                            ContentType.Books -> items(allBooks, key = { it.id }) { book ->
                                BookGridCard(
                                    book = book,
                                    onClick = { onOpenBook(book) },
                                    modifier = Modifier.fillMaxWidth(),
                                    onLongClick = { menuBook = book },
                                )
                            }
                        }
                        if (hasMore) {
                            item(
                                key = "load-more",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
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
            }
        }
    }

    if (showOptionsSheet) {
        val browseSuggestions = rememberBrowseFilterSuggestions()
        BrowseOptionsSheet(
            options = state.browseOptions,
            onOptionsChange = viewModel::setBrowseOptions,
            onDismiss = { showOptionsSheet = false },
            authorSuggestions = browseSuggestions.authors,
            tagSuggestions = browseSuggestions.tags,
        )
    }
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
    menuSeries?.let { series ->
        ContextMenuSheet(
            items = seriesContextMenuItems(
                pinned = false,
                hasStarted = series.progress != null,
                completed = series.progress == 1f,
                downloaded = false,
                onMarkRead = { viewModel.markSeriesRead(series, true) },
                onMarkUnread = { viewModel.markSeriesRead(series, false) },
                onAddToCollection = {
                    addTarget = series
                    addToCollectionOpen = true
                },
                onAddToReadList = {
                    addTarget = series
                    addToReadListOpen = true
                },
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
                onOpenSeries = { onVisitSeries(book) },
                onRead = { onReadBook(book, false) },
                onReadIncognito = { onReadBook(book, true) },
                onMarkRead = { viewModel.markBookRead(book, true) },
                onMarkUnread = { viewModel.markBookRead(book, false) },
                onAddToReadList = {
                    addTarget = book
                    addToReadListOpen = true
                },
            ),
            onDismiss = { menuBook = null },
        )
    }
    // 「加入收藏/阅读列表」选择器：真实列表数据（无会话时为空）
    if (addToCollectionOpen) {
        (addTarget as? Series)?.let { series ->
            AddToListSheet(
                collections = state.collections ?: emptyList(),
                readLists = emptyList(),
                onSelect = { entry ->
                    viewModel.addSeriesToCollection(entry, series)
                    addToCollectionOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToCollectionOpen = false
                    addTarget = null
                },
            )
        }
    }
    if (addToReadListOpen) {
        when (val target = addTarget) {
            is Series -> AddToListSheet(
                collections = emptyList(),
                readLists = state.readLists ?: emptyList(),
                onSelect = { entry ->
                    // Komga 阅读列表只收书籍：系列加入时逐本追加
                    viewModel.addSeriesToReadList(entry, target)
                    addToReadListOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToReadListOpen = false
                    addTarget = null
                },
            )
            is Book -> AddToListSheet(
                collections = emptyList(),
                readLists = state.readLists ?: emptyList(),
                onSelect = { entry ->
                    viewModel.addBookToReadList(entry, target)
                    addToReadListOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToReadListOpen = false
                    addTarget = null
                },
            )
            else -> Unit
        }
    }
}

/** 钉选编辑 sheet（对齐 iOS LibraryPickerSheet：勾选要钉选的书库，保存后「已钉选」范围生效）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryPinSheet(
    libraries: List<LibraryDto>,
    pinnedIds: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember { mutableStateOf(pinnedIds) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                "钉选书库",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "被钉选的书库会出现在「已钉选」范围",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            libraries.forEach { library ->
                val checked = library.id in selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selection = if (checked) selection - library.id else selection + library.id
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        library.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    if (checked) {
                        Icon(Icons.Filled.Check, contentDescription = "已钉选")
                    }
                }
            }
            TextButton(
                onClick = { onSave(selection) },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(vertical = 8.dp),
            ) {
                Text("完成")
            }
        }
    }
}
