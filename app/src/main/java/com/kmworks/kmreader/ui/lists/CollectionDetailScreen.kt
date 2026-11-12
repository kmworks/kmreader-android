package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.CollectionDetail
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BrowseFilterChipFlow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.SelectionToolbar
import com.kmworks.kmreader.ui.components.SeriesGridCard

private val WideLayoutBreakpoint = 960.dp
private val HeroCoverWidth = 168.dp

/** 收藏详情页（对齐 CollectionDetailView.swift + CollectionDetailWideLayoutView.swift）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    collection: CollectionDetail,
    onBack: () -> Unit,
    onOpenSeries: (Series) -> Unit,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    viewModel: CollectionDetailViewModel = hiltViewModel(),
) {
    // 真实数据：反查收藏 DTO 并拉其系列；无会话/未注册回退假数据
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(collection.id) { viewModel.load(collection.id) }
    val displayCollection = collection.copy(
        name = state.name ?: collection.name,
        ordered = state.ordered ?: collection.ordered,
    )
    val sourceSeries = state.series ?: collection.series
    var layout by remember { mutableStateOf(BrowseLayout.Grid) }
    var options by remember { mutableStateOf(BookBrowseOptions()) }
    var optionsOpen by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var removedIds by remember { mutableStateOf(setOf<Int>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    val visible = sourceSeries.filter { it.id !in removedIds }
    val series = sortCollectionSeries(visible, options)
    // hero 封面：服务端无收藏缩略图端点，用首个系列的缩略图
    val heroImageUrl = state.heroThumbnail ?: visible.firstOrNull()?.let { state.thumbnails[it.id] }
    val heroHue = visible.firstOrNull()?.hue ?: 210f

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
                                text = { Text("删除收藏", color = MaterialTheme.colorScheme.error) },
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
            if (maxWidth >= WideLayoutBreakpoint) {
                // 宽版：左栏信息（maxOf(maxWidth*0.382f, 340.dp)）+ 右栏系列列表，独立滚动
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(maxOf(this@BoxWithConstraints.maxWidth * 0.382f, 340.dp))
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        CollectionHero(
                            displayCollection,
                            visible.size,
                            centered = false,
                            coverHue = heroHue,
                            imageUrl = heroImageUrl,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        SeriesToolbar(
                            selecting = selecting,
                            selectedCount = selected.size,
                            totalCount = series.size,
                            options = options,
                            layout = layout,
                            onLayoutChange = { layout = it },
                            onOpenOptions = { optionsOpen = true },
                            onApplyPreset = { options = it.options },
                            onStartSelecting = { selecting = true },
                            onSelectAll = {
                                selected = if (selected.size == series.size) emptySet()
                                else series.map { it.id }.toSet()
                            },
                            onDeleteSelected = {
                                removedIds = removedIds + selected
                                selected = emptySet()
                                selecting = false
                            },
                            onDoneSelecting = {
                                selecting = false
                                selected = emptySet()
                            },
                        )
                        SeriesContent(
                            series = series,
                            layout = layout,
                            selecting = selecting,
                            selected = selected,
                            onOpenSeries = onOpenSeries,
                            onToggleSelect = { id ->
                                selected = if (id in selected) selected - id else selected + id
                            },
                            thumbnails = state.thumbnails,
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
                        CollectionHero(
                            displayCollection,
                            visible.size,
                            centered = true,
                            coverHue = heroHue,
                            imageUrl = heroImageUrl,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SeriesToolbar(
                        selecting = selecting,
                        selectedCount = selected.size,
                        totalCount = series.size,
                        options = options,
                        layout = layout,
                        onLayoutChange = { layout = it },
                        onOpenOptions = { optionsOpen = true },
                        onApplyPreset = { options = it.options },
                        onStartSelecting = { selecting = true },
                        onSelectAll = {
                            selected = if (selected.size == series.size) emptySet()
                            else series.map { it.id }.toSet()
                        },
                        onDeleteSelected = {
                            removedIds = removedIds + selected
                            selected = emptySet()
                            selecting = false
                        },
                        onDoneSelecting = {
                            selecting = false
                            selected = emptySet()
                        },
                    )
                    SeriesContent(
                        series = series,
                        layout = layout,
                        selecting = selecting,
                        selected = selected,
                        onOpenSeries = onOpenSeries,
                        onToggleSelect = { id ->
                            selected = if (id in selected) selected - id else selected + id
                        },
                        thumbnails = state.thumbnails,
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
            title = "删除收藏？",
            message = "「${collection.name}」将被删除，系列本身保留在书库中。",
            onConfirm = onDelete,
            onDismiss = { deleteConfirm = false },
        )
    }
}

/** Hero：封面拼贴占位 + 名称 + 系列计数 + 简介（对齐 CollectionDetailContentView）。 */
@Composable
private fun CollectionHero(
    collection: CollectionDetail,
    seriesCount: Int,
    centered: Boolean,
    coverHue: Float,
    imageUrl: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        // 服务端无收藏缩略图端点，用收藏内首个系列的缩略图
        CoverArt(
            title = collection.name,
            hue = coverHue,
            imageUrl = imageUrl,
            modifier = Modifier
                .width(HeroCoverWidth)
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = collection.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$seriesCount 部系列",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (collection.ordered) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Filled.FormatListNumbered,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "有序",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        collection.summary?.let {
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

/** 工具行：筛选 chip 流 + 进入多选；多选态切换为 SelectionToolbar（对齐 CollectionSeriesListView）。 */
@Composable
private fun SeriesToolbar(
    selecting: Boolean,
    selectedCount: Int,
    totalCount: Int,
    options: BookBrowseOptions,
    layout: BrowseLayout,
    onLayoutChange: (BrowseLayout) -> Unit,
    onOpenOptions: () -> Unit,
    onApplyPreset: (com.kmworks.kmreader.data.SavedFilter) -> Unit,
    onStartSelecting: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
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
            onDelete = onDeleteSelected,
            onDone = onDoneSelecting,
        )
    } else {
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
}

/** 系列内容区：列表/网格两档（对齐 CollectionSeriesListView 的 layoutMode 切换）。 */
@Composable
private fun SeriesContent(
    series: List<Series>,
    layout: BrowseLayout,
    selecting: Boolean,
    selected: Set<Int>,
    onOpenSeries: (Series) -> Unit,
    onToggleSelect: (Int) -> Unit,
    thumbnails: Map<Int, String> = emptyMap(),
) {
    when (layout) {
        BrowseLayout.List -> Column {
            series.forEach { item ->
                SeriesListRow(
                    series = item,
                    selected = if (selecting) item.id in selected else null,
                    imageUrl = thumbnails[item.id],
                    onClick = {
                        if (selecting) onToggleSelect(item.id) else onOpenSeries(item)
                    },
                )
            }
        }

        else -> {
            val minCell = if (layout == BrowseLayout.LargeGrid) 160 else 108
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                val columns = maxOf(2, ((maxWidth + 12.dp) / (minCell.dp + 12.dp)).toInt())
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    series.chunked(columns).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems.forEach { item ->
                                SeriesGridCard(
                                    series = item,
                                    onClick = {
                                        if (selecting) onToggleSelect(item.id) else onOpenSeries(item)
                                    },
                                    modifier = Modifier.weight(1f),
                                    imageUrl = thumbnails[item.id],
                                )
                            }
                            repeat(columns - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 系列列表行：封面小图 + 标题 + 副标题；多选态前置选中圈。 */
@Composable
private fun SeriesListRow(
    series: Series,
    selected: Boolean?,
    imageUrl: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
        }
        CoverArt(
            title = series.title,
            hue = series.hue,
            imageUrl = imageUrl,
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
            )
            Text(
                text = series.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun sortCollectionSeries(series: List<Series>, options: BookBrowseOptions): List<Series> {
    val sorted = when (options.sortField) {
        "标题" -> series.sortedBy { it.title }
        "发布日期" -> series.sortedBy { it.releaseYear ?: 0 }
        "文件大小", "页数" -> series.sortedBy { it.bookCount }
        else -> series
    }
    return if (options.sortAscending) sorted else sorted.reversed()
}
