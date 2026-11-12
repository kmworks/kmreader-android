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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.SampleListDetailData
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.data.SmartListDetail
import com.kmworks.kmreader.data.SmartListTarget
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BrowseFilterChipFlow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.SelectionToolbar
import com.kmworks.kmreader.ui.components.SeriesGridCard

private val WideLayoutBreakpoint = 960.dp

/** 智能列表详情页（对齐 SmartListDetailView.swift + SmartListDetailWideLayoutView.swift）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListDetailScreen(
    smartList: SmartListDetail,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (Series) -> Unit,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var layout by remember { mutableStateOf(BrowseLayout.Grid) }
    var options by remember { mutableStateOf(BookBrowseOptions()) }
    var optionsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Int>()) }

    val books = remember(smartList.id) { SampleListDetailData.smartListBooks(smartList) }
    val series = remember(smartList.id) { SampleListDetailData.smartListSeries(smartList) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("置顶") },
                                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onTogglePin()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新封面") },
                                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                onClick = { menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("删除智能列表", color = MaterialTheme.colorScheme.error) },
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
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val hitCount = if (smartList.target == SmartListTarget.Book) books.size else series.size
            val hitIds = if (smartList.target == SmartListTarget.Book) {
                books.map { it.id }
            } else {
                series.map { it.id }
            }
            val coverHue = if (smartList.target == SmartListTarget.Book) {
                books.firstOrNull()?.hue
            } else {
                series.firstOrNull()?.hue
            } ?: 210f
            if (maxWidth >= WideLayoutBreakpoint) {
                // 宽版：左栏信息（maxOf(maxWidth*0.382f, 340.dp)）+ 右栏命中网格，独立滚动
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(maxOf(maxWidth * 0.382f, 340.dp))
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        SmartListHero(smartList, hitCount, coverHue, centered = false)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        SmartListToolbar(
                            options = options,
                            layout = layout,
                            onLayoutChange = { layout = it },
                            onOpenOptions = { optionsOpen = true },
                            onApplyPreset = { options = it.options },
                            selecting = selecting,
                            selectedCount = selected.size,
                            totalCount = hitCount,
                            onStartSelecting = { selecting = true },
                            onSelectAll = {
                                selected = if (selected.size == hitCount) {
                                    emptySet()
                                } else {
                                    hitIds.toSet()
                                }
                            },
                            onDoneSelecting = {
                                selecting = false
                                selected = emptySet()
                            },
                        )
                        SmartListContent(
                            smartList = smartList,
                            books = books,
                            series = series,
                            layout = layout,
                            selecting = selecting,
                            selected = selected,
                            onOpenBook = onOpenBook,
                            onOpenSeries = onOpenSeries,
                            onToggleSelect = { id ->
                                selected = if (id in selected) selected - id else selected + id
                            },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SmartListHero(smartList, hitCount, coverHue, centered = true)
                    }
                    SmartListToolbar(
                        options = options,
                        layout = layout,
                        onLayoutChange = { layout = it },
                        onOpenOptions = { optionsOpen = true },
                        onApplyPreset = { options = it.options },
                        selecting = selecting,
                        selectedCount = selected.size,
                        totalCount = hitCount,
                        onStartSelecting = { selecting = true },
                        onSelectAll = {
                            selected = if (selected.size == hitCount) {
                                emptySet()
                            } else {
                                hitIds.toSet()
                            }
                        },
                        onDoneSelecting = {
                            selecting = false
                            selected = emptySet()
                        },
                    )
                    SmartListContent(
                        smartList = smartList,
                        books = books,
                        series = series,
                        layout = layout,
                        selecting = selecting,
                        selected = selected,
                        onOpenBook = onOpenBook,
                        onOpenSeries = onOpenSeries,
                        onToggleSelect = { id ->
                            selected = if (id in selected) selected - id else selected + id
                        },
                    )
                }
            }
        }
    }

    if (optionsOpen) {
        BrowseOptionsSheet(
            options = options,
            onOptionsChange = { options = it },
            onDismiss = { optionsOpen = false },
        )
    }
    if (deleteConfirm) {
        ConfirmDeleteDialog(
            title = "删除智能列表？",
            message = "「${smartList.name}」将被删除，命中内容保留在书库中。",
            onConfirm = onDelete,
            onDismiss = { deleteConfirm = false },
        )
    }
}

/** Hero：封面缩略图占位 + 名称 + 目标类型/更新时间 + 简介（对齐 SmartListHeroInfoView + DetailTimestampsView）。 */
@Composable
private fun SmartListHero(
    smartList: SmartListDetail,
    hitCount: Int,
    coverHue: Float,
    centered: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        // 封面 = 服务端缩略图 /api/v1/smartlists/{id}/thumbnail，此处为占位；实现时以 AsyncImage 加载
        CoverArt(
            title = smartList.name,
            hue = coverHue,
            modifier = Modifier
                .width(168.dp)
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = smartList.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (smartList.target == SmartListTarget.Book) Icons.AutoMirrored.Outlined.MenuBook
                else Icons.Outlined.CollectionsBookmark,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "目标：${smartList.target.label} · $hitCount 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = smartList.modifiedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        smartList.summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = "创建于 2024-06-12 · 更新于 2 小时前",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** 工具行：筛选 chip 流 + 多选入口；多选态切换为 SelectionToolbar（对齐 SmartListBooksListView/SmartListSeriesListView 的 filter 行）。 */
@Composable
private fun SmartListToolbar(
    options: BookBrowseOptions,
    layout: BrowseLayout,
    onLayoutChange: (BrowseLayout) -> Unit,
    onOpenOptions: () -> Unit,
    onApplyPreset: (com.kmworks.kmreader.data.SavedFilter) -> Unit,
    selecting: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onStartSelecting: () -> Unit,
    onSelectAll: () -> Unit,
    onDoneSelecting: () -> Unit,
) {
    if (selecting) {
        SelectionToolbar(
            selectedCount = selectedCount,
            totalCount = totalCount,
            onSelectAll = onSelectAll,
            onMarkRead = {},
            onMarkUnread = {},
            onDownload = {},
            onAddToReadList = {},
            onDelete = {},
            onDone = onDoneSelecting,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrowseFilterChipFlow(
            options = options,
            layout = layout,
            onLayoutChange = onLayoutChange,
            onOpenOptions = onOpenOptions,
            onApplyPreset = onApplyPreset,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onStartSelecting) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "多选")
        }
    }
}

/** 命中内容网格：按目标类型渲染书籍或系列卡（对齐 SmartList 详情的命中区）。 */
@Composable
private fun SmartListContent(
    smartList: SmartListDetail,
    books: List<Book>,
    series: List<Series>,
    layout: BrowseLayout,
    selecting: Boolean,
    selected: Set<Int>,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onToggleSelect: (Int) -> Unit,
) {
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
                                onClick = {
                                    if (selecting) onToggleSelect(book.id) else onOpenBook(book)
                                },
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
                                onClick = {
                                    if (selecting) onToggleSelect(item.id) else onOpenSeries(item)
                                },
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
