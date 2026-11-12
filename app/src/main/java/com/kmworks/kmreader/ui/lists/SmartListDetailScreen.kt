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
import androidx.compose.runtime.LaunchedEffect
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
import com.kmworks.kmreader.ui.components.SeriesGridCard

private val WideLayoutBreakpoint = 960.dp

/** 智能列表详情页（对齐 SmartListDetailView.swift + SmartListDetailWideLayoutView.swift）。
 *  数据来自 kmrs 私有端点 /api/v1/smart-lists（stock Komga 无此端点，未注册到
 *  DetailIdRegistry 的演示数据自动回退假数据）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListDetailScreen(
    smartList: SmartListDetail,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (Series) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SmartListDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(smartList.id) { viewModel.load(smartList.id) }

    var layout by remember { mutableStateOf(BrowseLayout.Grid) }
    var options by remember { mutableStateOf(BookBrowseOptions()) }
    var optionsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    // 「刷新封面」的缓存键版本：自增后重新请求服务端封面
    var coverVersion by remember { mutableStateOf(0) }

    // 真实数据优先，未接端点/无会话时回退假数据
    val display = smartList.copy(
        name = state.name ?: smartList.name,
        summary = state.summary ?: smartList.summary,
        target = state.target ?: smartList.target,
        modifiedLabel = state.modifiedLabel ?: smartList.modifiedLabel,
    )
    val books = state.books ?: emptyList()
    val series = state.series ?: emptyList()

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
                                text = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    editOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新封面") },
                                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    coverVersion++
                                },
                            )
                            // 只有服务端真实智能列表才可删除；本地回退数据没有可删对象，隐藏该项
                            if (state.hasRealSmartList) {
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
            val hitCount = if (display.target == SmartListTarget.Book) books.size else series.size
            val coverHue = if (display.target == SmartListTarget.Book) {
                books.firstOrNull()?.hue
            } else {
                series.firstOrNull()?.hue
            } ?: 210f
            if (maxWidth >= WideLayoutBreakpoint) {
                // 宽版：左栏信息（maxOf(maxWidth*0.382f, 340.dp)）+ 右栏命中网格，独立滚动
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(maxOf(this@BoxWithConstraints.maxWidth * 0.382f, 340.dp))
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        SmartListHero(
                            display,
                            hitCount,
                            coverHue,
                            stableId = smartList.id,
                            centered = false,
                            createdLabel = state.createdLabel,
                            coverVersion = coverVersion,
                        )
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
                        )
                        SmartListContent(
                            smartList = display,
                            books = books,
                            series = series,
                            layout = layout,
                            onOpenBook = onOpenBook,
                            onOpenSeries = onOpenSeries,
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
                        SmartListHero(
                            display,
                            hitCount,
                            coverHue,
                            stableId = smartList.id,
                            centered = true,
                            createdLabel = state.createdLabel,
                            coverVersion = coverVersion,
                        )
                    }
                    SmartListToolbar(
                        options = options,
                        layout = layout,
                        onLayoutChange = { layout = it },
                        onOpenOptions = { optionsOpen = true },
                        onApplyPreset = { options = it.options },
                    )
                    SmartListContent(
                        smartList = display,
                        books = books,
                        series = series,
                        layout = layout,
                        onOpenBook = onOpenBook,
                        onOpenSeries = onOpenSeries,
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
            message = "「${display.name}」将被删除，命中内容保留在书库中。",
            onConfirm = {
                deleteConfirm = false
                viewModel.delete { onBack() }
            },
            onDismiss = { deleteConfirm = false },
        )
    }
    if (editOpen) {
        // 编辑器不反解服务端 search 文档：规则从空白构建，保存时覆盖（name/summary 保留现值）
        SmartListEditSheet(
            onSave = { name, _, matchAll, rules ->
                viewModel.save(
                    name = name,
                    summary = display.summary ?: "",
                    search = buildSmartListSearchDocument(matchAll, rules),
                ) { ok ->
                    // 保存失败时保留编辑器，避免静默丢修改
                    if (ok) editOpen = false
                }
            },
            onDismiss = { editOpen = false },
            initialName = display.name,
            initialTarget = display.target,
            isEdit = true,
            onDelete = if (state.hasRealSmartList) {
                {
                    editOpen = false
                    deleteConfirm = true
                }
            } else {
                null
            },
        )
    }
}

/** Hero：封面缩略图 + 名称 + 目标类型/更新时间 + 简介（对齐 SmartListHeroInfoView + DetailTimestampsView）。 */
@Composable
private fun SmartListHero(
    display: SmartListDetail,
    hitCount: Int,
    coverHue: Float,
    centered: Boolean,
    stableId: Int? = null,
    createdLabel: String? = null,
    coverVersion: Int = 0,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        // 封面 = /api/v1/smart-lists/{id}/thumbnail（kmrs 端点）
        CoverArt(
            title = display.name,
            hue = coverHue,
            stableId = stableId,
            coverVersion = coverVersion,
            modifier = Modifier
                .width(168.dp)
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = display.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (display.target == SmartListTarget.Book) Icons.AutoMirrored.Outlined.MenuBook
                else Icons.Outlined.CollectionsBookmark,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "目标：${display.target.label} · $hitCount 项",
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
                text = display.modifiedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        display.summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = listOfNotNull(createdLabel, display.modifiedLabel)
                .joinToString(" · ")
                .ifBlank { display.modifiedLabel },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** 工具行：筛选 chip 流（对齐 SmartListBooksListView/SmartListSeriesListView 的 filter 行）。
 *  智能列表是服务端保存的筛选条件，成员不可增删，因此没有批量操作。 */
@Composable
private fun SmartListToolbar(
    options: BookBrowseOptions,
    layout: BrowseLayout,
    onLayoutChange: (BrowseLayout) -> Unit,
    onOpenOptions: () -> Unit,
    onApplyPreset: (com.kmworks.kmreader.data.SavedFilter) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrowseFilterChipFlow(
            options = options,
            layout = layout,
            onLayoutChange = onLayoutChange,
            onOpenOptions = onOpenOptions,
            onApplyPreset = onApplyPreset,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 命中内容网格：按目标类型渲染书籍或系列卡（对齐 SmartList 详情的命中区）。 */
@Composable
private fun SmartListContent(
    smartList: SmartListDetail,
    books: List<Book>,
    series: List<Series>,
    layout: BrowseLayout,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (Series) -> Unit,
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
                                onClick = { onOpenBook(book) },
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
                                onClick = { onOpenSeries(item) },
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
