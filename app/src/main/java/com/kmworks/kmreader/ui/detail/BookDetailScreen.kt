package com.kmworks.kmreader.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.lists.AddToListSheet

private val WideLayoutBreakpoint = 960.dp
private val HeroCoverWidth = 168.dp
private val ActionCardMaxWidth = 480.dp
private val ActionCardShape = RoundedCornerShape(16.dp)

/** 详情页「更多」菜单动作（对齐 BookDetailView toolbar Menu 的条目）。 */
enum class BookMenuAction {
    Share, Edit, Analyze, Refresh, Delete
}

/** 书籍详情页（对齐 BookDetailView.swift）：封面 hero、元信息行、操作卡、简介与更多菜单。 */
@Composable
fun BookDetailScreen(
    book: Book,
    seriesTitle: String,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSeries: () -> Unit = {},
    onMarkReadToggle: (Book) -> Unit = {},
    onDownload: (Book) -> Unit = {},
    onAddToReadList: (Book) -> Unit = {},
    onMenuAction: (BookMenuAction) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onReadIncognito: () -> Unit = {},
) {
    BookDetailBody(
        book = book,
        seriesTitle = seriesTitle,
        onBack = onBack,
        onRead = onRead,
        onOpenSeries = onOpenSeries,
        onMarkReadToggle = onMarkReadToggle,
        onDownload = onDownload,
        onAddToReadList = onAddToReadList,
        onMenuAction = onMenuAction,
        onOpenReadList = onOpenReadList,
        onReadIncognito = onReadIncognito,
        modifier = modifier,
    )
}

/** 书籍/单发详情共用骨架（对齐 BookDetailContentView；seriesTitle 为空即为单发形态）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookDetailBody(
    book: Book,
    seriesTitle: String?,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    onOpenSeries: () -> Unit,
    onMarkReadToggle: (Book) -> Unit,
    onDownload: (Book) -> Unit,
    onAddToReadList: (Book) -> Unit,
    onMenuAction: (BookMenuAction) -> Unit,
    onOpenReadList: (Int) -> Unit = {},
    onReadIncognito: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    var addToReadListOpen by remember { mutableStateOf(false) }
    // real data: resolve DTO id from registry, fetch book; fall back to sample data
    val state by viewModel.uiState.collectAsState()
    val readLists by viewModel.readLists.collectAsState()
    LaunchedEffect(book.id) { viewModel.load(book.id) }
    val baseBook = state.book ?: book
    // design mock: mark-read / download toggle locally for instant feedback
    var progressToggled by remember(book.id) { mutableStateOf(false) }
    // 下载态：真实文件状态优先（VM 文件扫描），无会话时本地演示开关
    val vmDownloaded by viewModel.downloaded.collectAsState()
    var localDownloaded by remember(book.id) { mutableStateOf(false) }
    val downloaded = vmDownloaded ?: localDownloaded
    val meta = state.meta
    val progress = if (progressToggled) {
        if (baseBook.progress == 1f) null else 1f
    } else {
        baseBook.progress
    }
    val displayed = baseBook.copy(progress = progress)

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
                                text = { Text("分享") },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onMenuAction(BookMenuAction.Share)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑元数据") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onMenuAction(BookMenuAction.Edit)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("分析") },
                                leadingIcon = { Icon(Icons.Outlined.Analytics, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onMenuAction(BookMenuAction.Analyze)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新元数据") },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onMenuAction(BookMenuAction.Refresh)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onMenuAction(BookMenuAction.Delete)
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
                // 宽版黄金分割：信息栏 maxOf(maxWidth*0.382f, 340.dp)，两栏独立滚动
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(maxOf(this@BoxWithConstraints.maxWidth * 0.382f, 340.dp))
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        BookHero(
                            displayed,
                            seriesTitle,
                            meta,
                            centered = false,
                            imageUrl = state.thumbnail,
                            onOpenSeries = onOpenSeries,
                        )
                        BookActionCard(
                            book = displayed,
                            downloaded = downloaded,
                            showNumber = seriesTitle != null,
                            onRead = onRead,
                            onMarkReadToggle = {
                                // 真实数据走 API 并在 VM 内更新；演示数据本地翻转
                                if (!viewModel.setRead(displayed.progress != 1f)) {
                                    progressToggled = !progressToggled
                                }
                                onMarkReadToggle(displayed)
                            },
                            onDownloadToggle = {
                                // 真实数据入队下载；演示数据本地翻转
                                if (!viewModel.downloadBook()) {
                                    localDownloaded = !localDownloaded
                                }
                                onDownload(displayed)
                            },
                            onAddToReadList = {
                                viewModel.ensureReadListsLoaded()
                                addToReadListOpen = true
                                onAddToReadList(displayed)
                            },
                            onReadIncognito = onReadIncognito,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        meta?.let { BookSecondaryContent(displayed, it) }
                        if (state.readLists.isNotEmpty()) {
                            BookMembershipChips(
                                entries = state.readLists,
                                onOpen = onOpenReadList,
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    BookHero(
                        displayed,
                        seriesTitle,
                        meta,
                        centered = true,
                        imageUrl = state.thumbnail,
                        onOpenSeries = onOpenSeries,
                    )
                    BookActionCard(
                        book = displayed,
                        downloaded = downloaded,
                        showNumber = seriesTitle != null,
                        onRead = onRead,
                        onMarkReadToggle = {
                            if (!viewModel.setRead(displayed.progress != 1f)) {
                                progressToggled = !progressToggled
                            }
                            onMarkReadToggle(displayed)
                        },
                        onDownloadToggle = {
                            // 真实数据入队下载；演示数据本地翻转
                            if (!viewModel.downloadBook()) {
                                localDownloaded = !localDownloaded
                            }
                            onDownload(displayed)
                        },
                        onAddToReadList = {
                            viewModel.ensureReadListsLoaded()
                            addToReadListOpen = true
                            onAddToReadList(displayed)
                        },
                        onReadIncognito = onReadIncognito,
                    )
                    meta?.let { BookSecondaryContent(displayed, it) }
                    if (state.readLists.isNotEmpty()) {
                        BookMembershipChips(
                            entries = state.readLists,
                            centered = true,
                            onOpen = onOpenReadList,
                        )
                    }
                }
            }
        }
    }

    // 「加入阅读列表」选择器：真实列表数据，选择后调 API（无会话时为空）
    if (addToReadListOpen) {
        AddToListSheet(
            collections = emptyList(),
            readLists = readLists ?: emptyList(),
            onSelect = { entry ->
                viewModel.addToReadList(entry)
                addToReadListOpen = false
            },
            onDismiss = { addToReadListOpen = false },
        )
    }
}

/** Hero：封面、所属系列入口、标题、作者 chips、元信息行（对齐 DetailHeroView 书籍形态）。 */
@Composable
internal fun BookHero(
    book: Book,
    seriesTitle: String?,
    meta: BookDetailMeta?,
    centered: Boolean,
    imageUrl: String? = null,
    onOpenSeries: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        CoverArt(
            title = seriesTitle ?: book.title,
            hue = book.hue,
            imageUrl = imageUrl,
            modifier = Modifier
                .width(HeroCoverWidth)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.height(16.dp))
        if (seriesTitle != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenSeries)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CollectionsBookmark,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineSmall,
        )
        val authors = meta?.authors.orEmpty()
        if (authors.isNotEmpty()) {
            // creator chips: role-ordered with role icons (aligned with SeriesDetailScreen hero)
            authors.sortedBy { it.role.ordinal }.take(4).let { ordered ->
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ordered.forEach { creator ->
                        MetaChip(
                            text = "${creator.role.label} · ${creator.name}",
                            icon = creator.role.icon(),
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.padding(top = 12.dp)) {
            if (meta != null) {
                MetaRow(icon = Icons.Filled.DateRange, text = "发布于 ${meta.releaseDate}")
            }
            MetaRow(icon = Icons.AutoMirrored.Filled.MenuBook, text = book.pageLabel)
            if (meta != null) {
                MetaRow(icon = Icons.Outlined.Storage, text = meta.sizeLabel)
                MetaRow(icon = Icons.Outlined.Description, text = meta.format)
            }
        }
    }
}

/** 操作卡：编号/页数/阅读状态/下载态 + 主按钮与副操作（对齐 DetailActionCard + BookActionsSection）。 */
@Composable
internal fun BookActionCard(
    book: Book,
    downloaded: Boolean,
    showNumber: Boolean,
    onRead: (Book) -> Unit,
    onMarkReadToggle: () -> Unit,
    onDownloadToggle: () -> Unit,
    onAddToReadList: () -> Unit,
    onReadIncognito: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = ActionCardMaxWidth)
                .background(MaterialTheme.colorScheme.surface, ActionCardShape)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showNumber) {
                    Text(
                        text = "#${book.number}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = book.pageLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloaded) {
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = "已下载",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "已下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    book.progress == 1f -> {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "已读完",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    book.progress != null -> {
                        val currentPage = (book.progress * book.pageCount).toInt().coerceAtLeast(1)
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "读到第 $currentPage 页 / 共 ${book.pageCount} 页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Icon(
                            Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "未读",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ReadingActionButton(book = book, onRead = { onRead(book) })
            Spacer(Modifier.height(8.dp))
            // secondary row: peek (incognito read) + download toggle (mirrors BookActionsSection)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReadIncognito,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("隐私阅读")
                }
                OutlinedButton(
                    onClick = onDownloadToggle,
                    enabled = !downloaded,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (downloaded) "已下载" else "下载")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onMarkReadToggle,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (book.progress == 1f) Icons.Outlined.RadioButtonUnchecked
                        else Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (book.progress == 1f) "标记未读" else "标记已读")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onAddToReadList,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.PlaylistAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("加入阅读列表")
            }
        }
    }
}

/** 主按钮：胶囊主色，继续阅读带页码与百分比（对齐 ReadingActionButton）。 */
@Composable
private fun ReadingActionButton(book: Book, onRead: () -> Unit) {
    val started = book.progress != null && book.progress != 1f
    val percent = ((book.progress ?: 0f) * 100).toInt()
    val currentPage = ((book.progress ?: 0f) * book.pageCount).toInt().coerceAtLeast(1)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onRead),
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = if (started) "继续阅读 · 第 $currentPage 页 · $percent%"
                    else "开始阅读 · ${book.pageCount} 页",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                )
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 右栏/下部内容：简介、标签 chips、媒体信息（对齐 ExpandableSummaryView + BookMediaInfoSection）。 */
@Composable
internal fun BookSecondaryContent(book: Book, meta: BookDetailMeta) {
    Text(
        text = "简介",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp),
    )
    Text(
        text = meta.summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (meta.tags.isNotEmpty()) {
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            meta.tags.forEach { tag ->
                MetaChip(text = tag, icon = Icons.Outlined.Sell)
            }
        }
    }
    Text(
        text = "媒体信息",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp),
    )
    Column(modifier = Modifier.padding(top = 8.dp)) {
        MetaRow(icon = Icons.Outlined.Description, text = meta.format)
        MetaRow(icon = Icons.Outlined.Storage, text = meta.sizeLabel)
        MetaRow(icon = Icons.AutoMirrored.Filled.MenuBook, text = book.pageLabel)
        MetaRow(icon = Icons.Outlined.Folder, text = meta.filePath)
    }
}

/** 元信息 chip：图标 + 文案（与 SeriesDetailScreen 的 MetaChip 同款）。 */
@Composable
private fun MetaChip(text: String, icon: ImageVector? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 归属阅读列表 chips，对应 BookDetailReadListsView。 */
@Composable
private fun BookMembershipChips(
    entries: List<ListEntry>,
    centered: Boolean = false,
    onOpen: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "阅读列表",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(
                8.dp,
                if (centered) Alignment.CenterHorizontally else Alignment.Start,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onOpen(entry.id) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 作者角色图标，对齐 SeriesDetailScreen 的 AuthorRole.icon。 */
private fun AuthorRole.icon(): ImageVector = when (this) {
    AuthorRole.Writer -> Icons.Outlined.Create
    AuthorRole.Penciller -> Icons.Outlined.Brush
    AuthorRole.Inker -> Icons.Outlined.EditNote
    AuthorRole.Colorist -> Icons.Outlined.Palette
    AuthorRole.Letterer -> Icons.Outlined.TextFormat
    AuthorRole.Cover -> Icons.Outlined.Photo
    AuthorRole.Editor -> Icons.Outlined.ContentCut
    AuthorRole.Translator -> Icons.Filled.Public
    AuthorRole.Custom -> Icons.Outlined.Person
}

/** 元信息行：图标 + 一行文案（与 SeriesDetailScreen 的 MetaRow 同款）。 */
@Composable
private fun MetaRow(
    icon: ImageVector?,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}
