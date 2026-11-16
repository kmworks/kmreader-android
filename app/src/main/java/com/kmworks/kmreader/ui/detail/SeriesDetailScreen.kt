package com.kmworks.kmreader.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BookListRow
import com.kmworks.kmreader.ui.components.BrowseFilterChipFlow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.rememberPersistentBrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.rememberBrowseFilterSuggestions
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.SelectionToolbar
import com.kmworks.kmreader.ui.lists.AddToListSheet
import com.kmworks.kmreader.ui.tracker.TrackerSection
import com.kmworks.kmreader.ui.tracker.TrackerSeriesSheet
import kotlinx.coroutines.launch

// 对齐 LayoutConfig.detailWideLayoutMinimumWidth = 960（宽版详情启用阈值）
private val WideLayoutBreakpoint = 960.dp
private val HeroCoverWidth = 168.dp
private val ActionCardMaxWidth = 480.dp
private val ActionCardShape = RoundedCornerShape(16.dp)

/** 系列详情，对齐 KMReader SeriesDetailView / SeriesDetailContentView：
 *  Hero（大封面+居中信息）→ DetailActionCard（册数行+继续阅读胶囊+下载区）→
 *  时间戳 → 简介 → 分类/标签 chips → 别称 → 书籍列表（筛选/排序/布局/多选行）。
 *  宽屏（≥840dp）为左信息栏右书籍列表，对应 SeriesDetailWideLayoutView。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    series: Series,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    onBrowseFilter: (String) -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onOpenCollection: (Int) -> Unit = {},
    viewModel: SeriesDetailViewModel = hiltViewModel(),
) {
    // 真实数据：进入时按注册表反查 DTO id 拉单系列与书籍；无会话/未注册回退假数据
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(series.id) { viewModel.load(series.id) }
    // 演示数据（无会话）下的「标记为已读」本地即时反馈
    var demoMarkedRead by remember(series.id) { mutableStateOf(false) }
    val collections by viewModel.collections.collectAsState()
    val readLists by viewModel.readLists.collectAsState()
    val scope = rememberCoroutineScope()
    val displaySeries = (state.series ?: series).let {
        if (demoMarkedRead && state.series == null) it.copy(progress = 1f, unreadCount = 0) else it
    }
    val rawBooks = state.books ?: emptyList()
    val books = if (demoMarkedRead && state.books == null) rawBooks.map { it.copy(progress = 1f) } else rawBooks
    // reading target: first in-progress, else first unread, else first book (mirrors SeriesContinueReadingResolver)
    val continueBook = books.firstOrNull { it.progress != null && it.progress < 1f }
        ?: books.firstOrNull { it.progress == null }
        ?: books.firstOrNull()
    // 对齐 seriesDetailLayout：列表/中网格/大网格三档，默认列表，逐页持久化
    val (booksLayout, setBooksLayout) = rememberPersistentBrowseLayout("seriesDetail", BrowseLayout.List)
    // 筛选状态（对齐 BookBrowseOptions；真实实现逐页 AppStorage 持久化）
    var browseOptions by remember { mutableStateOf(BookBrowseOptions()) }
    // 多选状态：null = 未进入多选；进入后书籍点击切换选中（对齐 BooksListViewForSeries 选择模式）
    var selectedIds by remember { mutableStateOf<Set<Int>?>(null) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var showKomfSheet by remember { mutableStateOf(false) }
    var showTrackerSheet by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var addToCollectionOpen by remember { mutableStateOf(false) }
    var addSelectedToReadListOpen by remember { mutableStateOf(false) }
    var deleteSelectedOpen by remember { mutableStateOf(false) }
    // 下载状态：真实文件扫描结果（stableId -> 完整）+ 演示本地反馈
    val downloadedMap by viewModel.downloaded.collectAsState()
    var demoDownloaded by remember { mutableStateOf(false) }
    val seriesDownloaded = (
        books.isNotEmpty() && downloadedMap.isNotEmpty() &&
            books.all { downloadedMap[it.id] == true }
        ) || demoDownloaded
    val onDownloadSeries = {
        if (viewModel.downloadSeries()) {
            // 已入队；完成后 downloadedMap 经文件扫描更新
        } else {
            demoDownloaded = true
        }
    }

    // 页内 snackbar（komf 任务与多选失败通知共用）
    val snackbarHostState = remember { SnackbarHostState() }

    // 多选未开启时长按书籍 = 带该书进入多选（对齐原作长按手势）
    val toggleSelect: (Book) -> Unit = { book ->
        selectedIds = selectedIds?.let { if (book.id in it) it - book.id else it + book.id }
            ?: setOf(book.id)
    }

    val selectionFailure: suspend (String) -> Unit = { action ->
        snackbarHostState.showSnackbar("${action}失败：没有可写入的真实书籍数据")
    }
    val onSelectedMarkRead: (Boolean) -> Unit = { read ->
        val ids = selectedIds.orEmpty()
        if (ids.isNotEmpty()) {
            if (viewModel.markBooksRead(ids, read)) {
                selectedIds = null
            } else {
                scope.launch { selectionFailure(if (read) "标记已读" else "标记未读") }
            }
        }
    }
    val onSelectedDownload: () -> Unit = {
        val ids = selectedIds.orEmpty()
        if (ids.isNotEmpty()) {
            if (viewModel.downloadBooks(ids)) {
                selectedIds = null
            } else {
                scope.launch { selectionFailure("下载") }
            }
        }
    }
    val onSelectedAddToReadList: () -> Unit = {
        if (!selectedIds.isNullOrEmpty()) {
            viewModel.ensureReadListsLoaded()
            addSelectedToReadListOpen = true
        }
    }
    val onSelectedDelete: () -> Unit = {
        if (!selectedIds.isNullOrEmpty()) deleteSelectedOpen = true
    }

    // komf 任务一次性通知（页内 snackbar，对齐 ErrorManager 通知）
    LaunchedEffect(viewModel) {
        viewModel.komfEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(displaySeries.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 对应原作详情 toolbar 单一 more 菜单（编辑/komf/重置 仅管理员可见）
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(text = { Text("分享") }, onClick = { moreOpen = false })
                            DropdownMenuItem(
                                text = { Text("编辑系列") },
                                onClick = {
                                    moreOpen = false
                                    showEditSheet = true
                                },
                            )
                            DropdownMenuItem(text = { Text("分析") }, onClick = { moreOpen = false })
                            DropdownMenuItem(text = { Text("刷新元数据") }, onClick = { moreOpen = false })
                            // komf 三项：kmrs 特性 + 管理员门控（对齐 KomfIntegrationStore）
                            if (state.komfAvailable) {
                                DropdownMenuItem(
                                    text = { Text("用 komf 识别…") },
                                    onClick = {
                                        moreOpen = false
                                        showKomfSheet = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("匹配 komf") },
                                    onClick = {
                                        moreOpen = false
                                        viewModel.komfMatch()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("重置 komf") },
                                    onClick = {
                                        moreOpen = false
                                        viewModel.komfReset()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("加入收藏…") },
                                onClick = {
                                    moreOpen = false
                                    viewModel.ensureCollectionsLoaded()
                                    addToCollectionOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("标记为已读") },
                                onClick = {
                                    moreOpen = false
                                    // 真实数据走 API（VM 内更新）；演示数据本地反馈
                                    if (!viewModel.markSeriesRead()) demoMarkedRead = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除…", color = MaterialTheme.colorScheme.error) },
                                onClick = { moreOpen = false },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (maxWidth >= WideLayoutBreakpoint) {
                // 宽版：左侧信息栏 + 右侧书籍列表，两栏独立滚动
                // 信息栏宽度 = max(可用宽 × 0.382, 340)，对齐原作 DetailWideLayoutView
                // 的黄金分割小份（width / φ² ≈ 38.2%，340 为窄栏可用下限）
                val railWidth = maxOf(maxWidth * 0.382f, 340.dp)
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(railWidth)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        DetailHero(
                            series = displaySeries,
                            centered = true,
                            onBrowseFilter = onBrowseFilter,
                        )
                        DetailActionCard(
                            series = displaySeries,
                            continueBook = continueBook,
                            centered = true,
                            downloaded = seriesDownloaded,
                            onRead = { continueBook?.let(onRead) },
                            onDownload = onDownloadSeries,
                        )
                        DetailSummary(displaySeries)
                        DetailChips(series = displaySeries, onBrowseFilter = onBrowseFilter)
                        DetailMembershipChips(state.collections, onOpen = onOpenCollection)
                        if (state.hasSession && state.trackerSupported) {
                            TrackerSection(
                                links = state.trackerLinks,
                                onManage = { showTrackerSheet = true },
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        item(key = "books-toolbar") {
                            BooksToolbar(
                                options = browseOptions,
                                layout = booksLayout,
                                onOptionsChange = { browseOptions = it },
                                onOpenOptions = { showOptionsSheet = true },
                                onCycleLayout = { setBooksLayout(booksLayout.next) },
                                selection = selectedIds,
                                totalCount = books.size,
                                onEnterSelection = { selectedIds = emptySet() },
                                onToggleSelectAll = {
                                    selectedIds = if (selectedIds?.size == books.size) emptySet() else books.map { it.id }.toSet()
                                },
                                onDoneSelection = { selectedIds = null },
                                onMarkRead = { onSelectedMarkRead(true) },
                                onMarkUnread = { onSelectedMarkRead(false) },
                                onDownload = onSelectedDownload,
                                onAddToReadList = onSelectedAddToReadList,
                                onDelete = onSelectedDelete,
                            )
                        }
                        item(key = "books") {
                            BooksSection(
                                books = books,
                                layout = booksLayout,
                                onOpenBook = onOpenBook,
                                selection = selectedIds,
                                onToggleSelect = toggleSelect,
                            )
                        }
                    }
                }
            } else {
                // 手机：单列滚动，内容居中（对齐原作 DetailHeroView 居中态：
                // 操作卡 maxWidth 480 水平居中，时间戳居中，chips 流居中）
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DetailHero(
                            series = displaySeries,
                            centered = true,
                            onBrowseFilter = onBrowseFilter,
                        )
                        DetailActionCard(
                            series = displaySeries,
                            continueBook = continueBook,
                            centered = true,
                            downloaded = seriesDownloaded,
                            onRead = { continueBook?.let(onRead) },
                            onDownload = onDownloadSeries,
                        )
                        DetailSummary(displaySeries)
                        DetailChips(displaySeries, centered = true, onBrowseFilter = onBrowseFilter)
                        DetailMembershipChips(state.collections, centered = true, onOpen = onOpenCollection)
                        if (state.hasSession && state.trackerSupported) {
                            TrackerSection(
                                links = state.trackerLinks,
                                onManage = { showTrackerSheet = true },
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    BooksToolbar(
                        options = browseOptions,
                        layout = booksLayout,
                        onOptionsChange = { browseOptions = it },
                        onOpenOptions = { showOptionsSheet = true },
                        onCycleLayout = { setBooksLayout(booksLayout.next) },
                        selection = selectedIds,
                        totalCount = books.size,
                        onEnterSelection = { selectedIds = emptySet() },
                        onToggleSelectAll = {
                            selectedIds = if (selectedIds?.size == books.size) emptySet() else books.map { it.id }.toSet()
                        },
                        onDoneSelection = { selectedIds = null },
                        onMarkRead = { onSelectedMarkRead(true) },
                        onMarkUnread = { onSelectedMarkRead(false) },
                        onDownload = onSelectedDownload,
                        onAddToReadList = onSelectedAddToReadList,
                        onDelete = onSelectedDelete,
                    )
                    BooksSection(
                        books = books,
                        layout = booksLayout,
                        onOpenBook = onOpenBook,
                        selection = selectedIds,
                        onToggleSelect = toggleSelect,
                    )
                }
            }
        }
    }

    if (showOptionsSheet) {
        val browseSuggestions = rememberBrowseFilterSuggestions()
        BrowseOptionsSheet(
            options = browseOptions,
            onOptionsChange = { browseOptions = it },
            onDismiss = { showOptionsSheet = false },
            authorSuggestions = browseSuggestions.authors,
            tagSuggestions = browseSuggestions.tags,
        )
    }
    if (showEditSheet) {
        var editSaving by remember { mutableStateOf(false) }
        SeriesEditSheet(
            series = displaySeries,
            values = state.editable,
            saving = editSaving,
            onSave = { dto ->
                editSaving = true
                viewModel.saveMetadata(dto) { ok ->
                    editSaving = false
                    if (ok) showEditSheet = false
                }
            },
            onDismiss = { if (!editSaving) showEditSheet = false },
        )
    }
    if (showKomfSheet) {
        KomfIdentifySheet(
            series = displaySeries,
            viewModel = viewModel,
            onDismiss = { showKomfSheet = false },
        )
    }
    if (showTrackerSheet) {
        // TrackerSeriesSheet 内部经 Hilt 自接真实数据（TrackerSeriesViewModel）
        TrackerSeriesSheet(
            series = displaySeries,
            alternateTitles = state.editable?.alternateTitles.orEmpty().map { it.second },
            onDismiss = {
                showTrackerSheet = false
                viewModel.refreshTrackerLinks()
            },
        )
    }
    // 「加入收藏」选择器：真实收藏数据，选择后调 API（无会话时为空）
    if (addToCollectionOpen) {
        AddToListSheet(
            collections = collections ?: emptyList(),
            readLists = emptyList(),
            onSelect = { entry ->
                viewModel.addToCollection(entry)
                addToCollectionOpen = false
            },
            onDismiss = { addToCollectionOpen = false },
        )
    }
    if (addSelectedToReadListOpen) {
        val ids = selectedIds.orEmpty()
        AddToListSheet(
            collections = emptyList(),
            readLists = readLists ?: emptyList(),
            onSelect = { entry ->
                if (viewModel.addBooksToReadList(entry, ids)) {
                    selectedIds = null
                } else {
                    scope.launch { selectionFailure("加入阅读列表") }
                }
                addSelectedToReadListOpen = false
            },
            onDismiss = { addSelectedToReadListOpen = false },
        )
    }
    if (deleteSelectedOpen) {
        val ids = selectedIds.orEmpty()
        ConfirmDeleteDialog(
            title = "删除所选书籍",
            message = "将删除 ${ids.size} 本书籍的文件。此操作不可撤销。",
            onConfirm = {
                viewModel.deleteBooks(ids) { ok ->
                    if (ok) {
                        selectedIds = null
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("删除失败，请稍后重试") }
                    }
                }
            },
            onDismiss = { deleteSelectedOpen = false },
        )
    }
}

/** Hero：居中大封面在上、信息居中在下（窄布局），对应 DetailHeroView + SeriesHeroInfoView。 */
@Composable
private fun DetailHero(
    series: Series,
    centered: Boolean,
    onBrowseFilter: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        CoverArt(
            title = series.title,
            hue = series.hue,
            stableId = series.id,
            modifier = Modifier
                .width(HeroCoverWidth)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            series.ageRating?.let { rating ->
                Text(
                    text = " $rating+",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // SeriesHeroInfoView.creatorItems：出版社(building.2) + 按角色排序的作者(role.icon)，
        // 均可点击跳转对应元数据筛选的系列浏览页；长流折叠在 "+N" 后原地展开（limit 4）
        val creatorChips = buildList {
            series.publisher?.let { add(Icons.Outlined.Business to ("出版社" to it)) }
            series.creators.sortedBy { it.role.ordinal }.forEach { creator ->
                add(creator.role.icon() to ("${creator.role.label}" to creator.name))
            }
        }
        CollapsibleMetaChipFlow(
            items = creatorChips,
            limit = 4,
            centered = centered,
            onClick = { (kind, value) -> onBrowseFilter("$kind · $value") },
        )
        Spacer(Modifier.height(12.dp))
        // DetailHeroMetadataGroup：居中 Hero 用水平换行流（一行排不下才折行），
        // 非居中（宽版信息栏）每条元信息单独一行。
        if (centered) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeroMetaItems(series)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeroMetaItems(series)
            }
        }
    }
}

@Composable
private fun HeroMetaItems(series: Series) {
    series.releaseYear?.let { MetaRow(icon = Icons.Filled.DateRange, text = "$it 年开始连载") }
    series.status?.let {
        MetaRow(icon = null, text = it, tint = if (it == "已完结") Color(0xFF4CAF50) else Color(0xFFFFA726))
    }
    series.language?.let { MetaRow(icon = Icons.Filled.Public, text = it) }
    MetaRow(
        icon = Icons.AutoMirrored.Filled.FormatTextdirectionRToL,
        text = if (series.directionRtl) "从右到左" else "从左到右",
    )
}

/** 操作卡：圆角 16 中性底容器（maxWidth 480），册数行 + 继续阅读胶囊 + 下载本系列。
 *  对应 DetailActionCard + SeriesBookCountView + SeriesReadingActionButton + SeriesDownloadActionsSection。 */
@Composable
private fun DetailActionCard(
    series: Series,
    continueBook: Book?,
    centered: Boolean,
    downloaded: Boolean,
    onRead: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .widthIn(max = ActionCardMaxWidth)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, ActionCardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = series.bookCountLine,
                style = MaterialTheme.typography.titleSmall,
            )
            val statusLabel = when {
                series.unreadCount > 0 && series.unreadCount < series.bookCount -> "${series.unreadCount} 册未读"
                series.progress != null && series.progress < 1f -> "阅读中"
                series.unreadCount == 0 && series.bookCount > 0 -> "全部已读"
                else -> "未读"
            }
            Text(
                text = "  ·  $statusLabel",
                style = MaterialTheme.typography.bodySmall,
                color = when (statusLabel) {
                    "全部已读" -> Color(0xFF4CAF50)
                    "阅读中" -> Color(0xFFFFA726)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (continueBook != null) {
            ReadingActionButton(continueBook = continueBook, onRead = onRead)
        }
        OutlinedButton(
            onClick = onDownload,
            enabled = !downloaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (downloaded) Icons.Outlined.CheckCircle else Icons.Filled.Download,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (downloaded) "已下载" else "下载本系列")
        }
    }
}

/** 阅读胶囊：白底深色字，两行（动作行 + 目标书籍行）。对应 SeriesReadingActionButton。 */
@Composable
private fun ReadingActionButton(continueBook: Book, onRead: () -> Unit) {
    // caption: resume with page/percent when in progress, otherwise start (mirrors readLabel)
    val caption = continueBook.progress?.let { p ->
        if (p > 0f && p < 1f) {
            val page = (p * continueBook.pageCount).toInt().coerceAtLeast(1)
            val percent = (p * 100).toInt()
            "继续阅读 · 第 $page 页 · $percent%"
        } else {
            "开始阅读"
        }
    } ?: "开始阅读"
    Button(
        onClick = onRead,
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = caption,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "#${continueBook.number.removePrefix("第 ").removeSuffix(" 卷")} - ${continueBook.title}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailSummary(series: Series) {
    series.summary?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** 知名实体 / 分类 / 标签 / 链接 chips，对应 SeriesDetailChipsView。
 *  知名实体(角色 person/团队 person.2/地点 mappin)、分类(theatermasks)、标签(tag) 均可点击
 *  跳转对应元数据筛选的系列浏览页；链接(link) 打开外部 URL；长流折叠在 "+N" 后原地展开（limit 6）。 */
@Composable
private fun DetailChips(
    series: Series,
    centered: Boolean = false,
    onBrowseFilter: (String) -> Unit,
) {
    if (series.notableEntities.isEmpty() && series.genres.isEmpty() &&
        series.tags.isEmpty() && series.links.isEmpty()
    ) return
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (series.notableEntities.isNotEmpty()) {
            CollapsibleMetaChipFlow(
                items = series.notableEntities.map { e -> e.icon() to (e.kindLabel to e.name) },
                limit = 6,
                centered = centered,
                onClick = { (kind, value) -> onBrowseFilter("$kind · $value") },
            )
        }
        if (series.genres.isNotEmpty()) {
            CollapsibleMetaChipFlow(
                items = series.genres.map { Icons.Outlined.TheaterComedy to ("分类" to it) },
                limit = 6,
                centered = centered,
                onClick = { (kind, value) -> onBrowseFilter("$kind · $value") },
            )
        }
        if (series.tags.isNotEmpty()) {
            CollapsibleMetaChipFlow(
                items = series.tags.map { Icons.Outlined.Sell to ("标签" to it) },
                limit = 6,
                centered = centered,
                onClick = { (kind, value) -> onBrowseFilter("$kind · $value") },
            )
        }
        if (series.links.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(
                    8.dp,
                    if (centered) Alignment.CenterHorizontally else Alignment.Start,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                series.links.forEach { (label, url) ->
                    MetaChip(
                        text = label,
                        icon = Icons.Outlined.Link,
                        onClick = { uriHandler.openUri(url) },
                    )
                }
            }
        }
    }
}

/** 归属收藏 chips，对应 SeriesDetailCollectionsView。 */
@Composable
private fun DetailMembershipChips(
    entries: List<ListEntry>,
    centered: Boolean = false,
    onOpen: (Int) -> Unit,
) {
    if (entries.isEmpty()) return
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "收藏",
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
                MetaChip(
                    text = entry.title,
                    icon = Icons.Outlined.CollectionsBookmark,
                    onClick = { onOpen(entry.id) },
                )
            }
        }
    }
}

/** 可折叠的元数据 chip 流，对应 DetailChipFlow（"+N" chip 原地展开）。 */
@Composable
private fun CollapsibleMetaChipFlow(
    items: List<Pair<ImageVector, Pair<String, String>>>,
    limit: Int,
    centered: Boolean = false,
    onClick: (Pair<String, String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(
            8.dp,
            if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val visible = if (expanded) items else items.take(limit)
        visible.forEach { (icon, target) ->
            MetaChip(text = target.second, icon = icon, onClick = { onClick(target) })
        }
        if (!expanded && items.size > limit) {
            MetaChip(text = "+${items.size - limit}", onClick = { expanded = true })
        }
    }
}

/** 作者角色图标，对齐 AuthorRole.icon（writer=pencil / penciller=paintbrush / …）。 */
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

/** 知名实体图标/标签，对齐 NotableEntityChipFlows（character=person / team=person.2 / location=mappin / 其他=tag）。 */
private fun NotableEntity.icon(): ImageVector = when (kind) {
    "character" -> Icons.Outlined.Person
    "team" -> Icons.Outlined.Group
    "location" -> Icons.Outlined.Place
    else -> Icons.Outlined.Sell
}

private val NotableEntity.kindLabel: String
    get() = when (kind) {
        "character" -> "角色"
        "team" -> "团队"
        "location" -> "地点"
        else -> "实体"
    }

/** 书籍区：列表行或自适应网格（中网格卡宽 108 / 大网格 160，对齐 LayoutConfig）。
 *  点击进入书籍详情（对齐原作 BookListRow 落点 BookDetailView）；多选态点击切换选中。 */
@Composable
private fun BooksSection(
    books: List<Book>,
    layout: BrowseLayout,
    onOpenBook: (Book) -> Unit,
    selection: Set<Int>?,
    onToggleSelect: (Book) -> Unit,
) {
    when (layout) {
        BrowseLayout.List -> Column {
            books.forEach { book ->
                if (selection == null) {
                    BookListRow(
                        book = book,
                        onClick = { onOpenBook(book) },
                        onLongClick = { onToggleSelect(book) },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BookListRow(
                            book = book,
                            onClick = { onToggleSelect(book) },
                            modifier = Modifier.weight(1f),
                        )
                        Checkbox(
                            checked = book.id in selection,
                            onCheckedChange = { onToggleSelect(book) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        }

        BrowseLayout.Grid, BrowseLayout.LargeGrid -> BooksGrid(
            books = books,
            large = layout == BrowseLayout.LargeGrid,
            onOpenBook = onOpenBook,
            selection = selection,
            onToggleSelect = onToggleSelect,
        )
    }
}

@Composable
private fun BooksGrid(
    books: List<Book>,
    large: Boolean,
    onOpenBook: (Book) -> Unit,
    selection: Set<Int>?,
    onToggleSelect: (Book) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        val minCell = if (large) 160.dp else 108.dp
        val columns = maxOf(2, ((maxWidth + 12.dp) / (minCell + 12.dp)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            books.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { book ->
                        Box(modifier = Modifier.weight(1f)) {
                            val inSelection = selection != null
                            BookGridCard(
                                book = book,
                                onClick = { if (inSelection) onToggleSelect(book) else onOpenBook(book) },
                                onLongClick = { if (!inSelection) onToggleSelect(book) },
                            )
                            if (selection != null && book.id in selection) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "已选中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(22.dp)
                                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                                )
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 书籍区工具行：多选态显示 SelectionToolbar（对齐 SelectionActionsToolbar），
 *  否则为筛选 chip 流（布局/预设/排序/激活条件，对应 BookFilterView）+ 进入多选。 */
@Composable
private fun BooksToolbar(
    options: BookBrowseOptions,
    layout: BrowseLayout,
    onOptionsChange: (BookBrowseOptions) -> Unit,
    onOpenOptions: () -> Unit,
    onCycleLayout: () -> Unit,
    selection: Set<Int>?,
    totalCount: Int,
    onEnterSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onDoneSelection: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDownload: () -> Unit,
    onAddToReadList: () -> Unit,
    onDelete: () -> Unit,
) {
    if (selection != null) {
        SelectionToolbar(
            selectedCount = selection.size,
            totalCount = totalCount,
            onSelectAll = onToggleSelectAll,
            onMarkRead = onMarkRead,
            onMarkUnread = onMarkUnread,
            onDownload = onDownload,
            onAddToReadList = onAddToReadList,
            onDelete = onDelete,
            onDone = onDoneSelection,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BrowseFilterChipFlow(
                options = options,
                layout = layout,
                onLayoutChange = { onCycleLayout() },
                onOpenOptions = onOpenOptions,
                onApplyPreset = { onOptionsChange(it.options) },
            )
        }
        IconButton(onClick = onEnterSelection) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "多选")
        }
    }
}

@Composable
private fun MetaChip(text: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MetaRow(icon: ImageVector?, text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(tint, CircleShape),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}
