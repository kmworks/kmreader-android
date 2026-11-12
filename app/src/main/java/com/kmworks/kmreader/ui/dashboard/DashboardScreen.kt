package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.ReadListContinuationUi
import com.kmworks.kmreader.data.ReadListsInProgressSectionId
import com.kmworks.kmreader.data.SampleDashboardData
import com.kmworks.kmreader.data.label
import com.kmworks.kmreader.ui.components.EmptyState
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BookHorizontalCard
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.SectionHeader
import com.kmworks.kmreader.ui.components.SeriesGridCard
import com.kmworks.kmreader.ui.components.bookContextMenuItems
import com.kmworks.kmreader.ui.components.seriesContextMenuItems
import com.kmworks.kmreader.ui.lists.AddToListSheet

/** 仪表盘：分区列表，每分区 = 标题头 + 横向卡片流。对齐 KMReader DashboardView。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSeries: (Series) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onOpenSectionDetail: (DashboardSection<out Any>) -> Unit = {},
    onOpenContinuation: (ReadListContinuationUi) -> Unit = {},
    onVisitSeries: (Book) -> Unit = {},
    onReadBook: (Book, incognito: Boolean) -> Unit = { _, _ -> },
) {
    val viewModel: DashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    // real sections only; empty state when there is no session or nothing loaded
    val sections = state.sections ?: emptyList()
    // 库范围切换（全部/已钉选/单库），对应 LibraryScopeMenu；仪表盘为独立会话内 scope
    var scope by remember { mutableStateOf("全部书库") }
    var scopeMenuOpen by remember { mutableStateOf(false) }
    // 长按上下文菜单（对齐原作仪表盘卡片的 contextMenu）
    var seriesMenuTarget by remember { mutableStateOf<Series?>(null) }
    var bookMenuTarget by remember { mutableStateOf<Book?>(null) }
    // 「加入收藏/阅读列表」sheet 目标
    var addTarget by remember { mutableStateOf<Any?>(null) }
    var addToCollectionOpen by remember { mutableStateOf(false) }
    var addToReadListOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("仪表盘") },
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
                            Text(scope)
                        }
                        DropdownMenu(
                            expanded = scopeMenuOpen,
                            onDismissRequest = { scopeMenuOpen = false },
                        ) {
                            listOf("全部书库", "已钉选", "漫画", "日漫", "国漫").forEach { s ->
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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
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
                        title = "未连接服务器",
                        subtitle = "登录后查看仪表盘",
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            }
            items(sections, key = { it.id }) { section ->
                DashboardSectionRow(
                    section = section,
                    onOpenSeries = onOpenSeries,
                    onOpenBook = onOpenBook,
                    onOpenSectionDetail = onOpenSectionDetail,
                    onOpenContinuation = onOpenContinuation,
                    onSeriesLongClick = { seriesMenuTarget = it },
                    onBookLongClick = { bookMenuTarget = it },
                    continuations = state.continuations,
                    kindMenuEnabled = state.kindMenuEnabled,
                    onKindChange = { kind -> viewModel.setSectionKind(section.id, kind) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // 长按菜单：系列 / 书籍（书籍含「访问系列」导航，对齐原作 BookContextMenu）
    seriesMenuTarget?.let { series ->
        ContextMenuSheet(
            items = seriesContextMenuItems(
                pinned = false,
                hasStarted = series.progress != null,
                completed = series.unreadCount == 0,
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
            onDismiss = { seriesMenuTarget = null },
        )
    }
    bookMenuTarget?.let { book ->
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
            onDismiss = { bookMenuTarget = null },
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

@Composable
private fun DashboardSectionRow(
    section: DashboardSection<out Any>,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSectionDetail: (DashboardSection<out Any>) -> Unit,
    onOpenContinuation: (ReadListContinuationUi) -> Unit,
    onSeriesLongClick: (Series) -> Unit = {},
    onBookLongClick: (Book) -> Unit = {},
    continuations: List<ReadListContinuationUi> = emptyList(),
    kindMenuEnabled: Boolean = false,
    onKindChange: (DashboardCardKind?) -> Unit = {},
) {
    val defaultKind = SampleDashboardData.section(section.id)?.defaultKind ?: section.kind
    val availableKinds = SampleDashboardData.section(section.id)?.availableKinds
        ?: DashboardCardKind.entries.toList()
    var kindMenuOpen by remember(section.id) { mutableStateOf(false) }
    // 分区标题右侧的卡片形态菜单（可在设置页关闭，对齐 SettingsDashboardView 的卡片形态菜单开关）
    val kindTrailing: (@Composable () -> Unit)? = if (kindMenuEnabled) {
        {
            Box {
                TextButton(onClick = { kindMenuOpen = true }) {
                    Icon(
                        Icons.Filled.ViewModule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(section.kind.label)
                }
                DropdownMenu(
                    expanded = kindMenuOpen,
                    onDismissRequest = { kindMenuOpen = false },
                ) {
                    availableKinds.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(if (kind == defaultKind) "${kind.label}（默认）" else kind.label) },
                            onClick = {
                                kindMenuOpen = false
                                onKindChange(if (kind == defaultKind) null else kind)
                            },
                        )
                    }
                }
            }
        }
    } else {
        null
    }
    SectionHeader(
        title = section.title,
        onMore = { onOpenSectionDetail(section) },
        modifier = Modifier.padding(horizontal = 16.dp),
        trailing = kindTrailing,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // read-list section renders continuation cards (aligned with ReadListsInProgressSectionView)
        if (section.id == ReadListsInProgressSectionId) {
            items(continuations, key = { it.readListId }) { continuation ->
                ReadListContinuationCard(
                    continuation = continuation,
                    onClick = { onOpenContinuation(continuation) },
                    stableId = continuation.readListId.hashCode(),
                    modifier = Modifier.width(DashboardCardKind.Horizontal.widthDp.dp),
                )
            }
        } else {
            items(section.items) { item ->
                when {
                    // 书籍卡片打开书籍详情（对齐原作仪表盘卡片落点 BookDetailView）
                    item is Book && section.kind == DashboardCardKind.Horizontal ->
                        BookHorizontalCard(
                            book = item,
                            onClick = { onOpenBook(item) },
                            onLongClick = { onBookLongClick(item) },
                            modifier = Modifier.width(DashboardCardKind.Horizontal.widthDp.dp),
                        )

                    item is Book ->
                        BookGridCard(
                            book = item,
                            onClick = { onOpenBook(item) },
                            onLongClick = { onBookLongClick(item) },
                            modifier = Modifier.width(section.kind.widthDp.dp),
                        )

                    item is Series ->
                        SeriesGridCard(
                            series = item,
                            onClick = { onOpenSeries(item) },
                            onLongClick = { onSeriesLongClick(item) },
                            modifier = Modifier.width(section.kind.widthDp.dp),
                        )
                }
            }
        }
    }
}
