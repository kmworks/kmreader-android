package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.ReadListDetail
import com.kmworks.kmreader.data.ReadStatus
import com.kmworks.kmreader.data.TriState
import com.kmworks.kmreader.ui.components.BrowseFilterChipFlow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.SelectionToolbar

private val WideLayoutBreakpoint = 960.dp

/** 阅读列表详情页（对齐 ReadListDetailView.swift + ReadListDetailWideLayoutView.swift）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadListDetailScreen(
    readList: ReadListDetail,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRemoveBooks: (List<Int>) -> Unit = {},
) {
    var layout by remember { mutableStateOf(BrowseLayout.List) }
    var options by remember { mutableStateOf(BookBrowseOptions()) }
    var optionsOpen by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var removedIds by remember { mutableStateOf(setOf<Int>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    val visible = readList.books.filter { it.id !in removedIds }
    val books = visible.filter { matchesReadStatus(it, options) }
    val continuation = visible.indexOfFirst { it.progress == null || it.progress < 1f }
        .let { if (it < 0) null else it }

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
                                text = { Text("删除阅读列表", color = MaterialTheme.colorScheme.error) },
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
                // 宽版：左栏信息（maxOf(maxWidth*0.382f, 340.dp)）+ 右栏书籍列表，独立滚动
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(maxOf(this@BoxWithConstraints.maxWidth * 0.382f, 340.dp))
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        ReadListHero(readList, visible.size, centered = false)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        if (continuation != null) {
                            ContinuationHintCard(
                                index = continuation,
                                book = visible[continuation],
                                onClick = { onRead(visible[continuation]) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        ReadListToolbar(
                            selecting = selecting,
                            selectedCount = selected.size,
                            totalCount = books.size,
                            options = options,
                            layout = layout,
                            onLayoutChange = { layout = it },
                            onOpenOptions = { optionsOpen = true },
                            onApplyPreset = { options = it.options },
                            onStartSelecting = { selecting = true },
                            onSelectAll = {
                                selected = if (selected.size == books.size) emptySet()
                                else books.map { it.id }.toSet()
                            },
                            onRemoveSelected = {
                                removedIds = removedIds + selected
                                onRemoveBooks(selected.toList())
                                selected = emptySet()
                                selecting = false
                            },
                            onDoneSelecting = {
                                selecting = false
                                selected = emptySet()
                            },
                        )
                        ReadListBookList(
                            books = books,
                            selecting = selecting,
                            selected = selected,
                            onRead = onRead,
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
                        ReadListHero(readList, visible.size, centered = true)
                    }
                    if (continuation != null) {
                        ContinuationHintCard(
                            index = continuation,
                            book = visible[continuation],
                            onClick = { onRead(visible[continuation]) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    ReadListToolbar(
                        selecting = selecting,
                        selectedCount = selected.size,
                        totalCount = books.size,
                        options = options,
                        layout = layout,
                        onLayoutChange = { layout = it },
                        onOpenOptions = { optionsOpen = true },
                        onApplyPreset = { options = it.options },
                        onStartSelecting = { selecting = true },
                        onSelectAll = {
                            selected = if (selected.size == books.size) emptySet()
                            else books.map { it.id }.toSet()
                        },
                        onRemoveSelected = {
                            removedIds = removedIds + selected
                            onRemoveBooks(selected.toList())
                            selected = emptySet()
                            selecting = false
                        },
                        onDoneSelecting = {
                            selecting = false
                            selected = emptySet()
                        },
                    )
                    ReadListBookList(
                        books = books,
                        selecting = selecting,
                        selected = selected,
                        onRead = onRead,
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
            title = "删除阅读列表？",
            message = "「${readList.name}」将被删除，书籍本身保留在书库中。",
            onConfirm = onDelete,
            onDismiss = { deleteConfirm = false },
        )
    }
}

/** Hero：封面 + 名称 + 本数/有序 + 简介 + 时间戳（对齐 ReadListDetailContentView）。 */
@Composable
private fun ReadListHero(readList: ReadListDetail, bookCount: Int, centered: Boolean) {
    val first = readList.books.firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (first != null) {
            CoverArt(
                title = first.seriesTitle,
                hue = first.hue,
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(2f / 3f),
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = readList.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$bookCount 本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (readList.ordered) {
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
        readList.summary?.let {
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

/** 续读提示卡：「继续阅读：第 N 本」（对齐 ReadListContinuationHintView）。 */
@Composable
private fun ContinuationHintCard(
    index: Int,
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.FormatListNumbered,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "继续阅读：第 ${index + 1} 本",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 工具行：筛选 chip 流 + 进入多选；多选态切换为 SelectionToolbar（对齐 BooksListViewForReadList）。 */
@Composable
private fun ReadListToolbar(
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
    onRemoveSelected: () -> Unit,
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
            onDelete = onRemoveSelected,
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

/** 有序书籍列表：序号体现顺序，行内显示阅读进度（对齐 ReadList 的书籍行）。 */
@Composable
private fun ReadListBookList(
    books: List<Book>,
    selecting: Boolean,
    selected: Set<Int>,
    onRead: (Book) -> Unit,
    onToggleSelect: (Int) -> Unit,
) {
    Column {
        books.forEachIndexed { index, book ->
            ReadListBookRow(
                index = index,
                book = book,
                selected = if (selecting) book.id in selected else null,
                onClick = {
                    if (selecting) onToggleSelect(book.id) else onRead(book)
                },
            )
        }
    }
}

/** 阅读列表书籍行：序号 + 封面 + 标题/页数/进度 + 已读标签。 */
@Composable
private fun ReadListBookRow(
    index: Int,
    book: Book,
    selected: Boolean?,
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
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        CoverArt(
            title = book.seriesTitle,
            hue = book.hue,
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.pageLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            book.progress?.let { p ->
                if (p < 1f) {
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(3.dp),
                    )
                }
            }
        }
        if (book.progress == 1f) {
            Text(
                text = "已读",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

private fun matchesReadStatus(book: Book, options: BookBrowseOptions): Boolean {
    val status = when {
        book.progress == 1f -> ReadStatus.Read
        book.progress != null -> ReadStatus.InProgress
        else -> ReadStatus.Unread
    }
    val states = options.readStatuses
    if (states.values.any { it == TriState.Include }) {
        return states[status] == TriState.Include
    }
    return states[status] != TriState.Exclude
}
