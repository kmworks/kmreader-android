package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.SampleData.ListGroup
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.EmptyState
import com.kmworks.kmreader.ui.components.ContextMenuItem
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.collectionContextMenuItems
import com.kmworks.kmreader.ui.components.readListContextMenuItems
import com.kmworks.kmreader.ui.components.smartListContextMenuItems

/** 列表 tab：对齐 KMReader ListsBrowseView —— 收藏 / 阅读列表 / 智能列表三条横向条带，
 *  pinned 置顶，条带头可进入对应类型全页；工具栏范围筛选（LibraryScopeMenu）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onOpenCollection: (Int) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onOpenSmartList: (Int) -> Unit = {},
) {
    var scope by remember { mutableStateOf("已钉选") }
    var scopeMenuOpen by remember { mutableStateOf(false) }
    // 长按上下文菜单目标（分组名 + 条目）
    var menuTarget by remember { mutableStateOf<Pair<String, ListEntry>?>(null) }
    // 编辑 sheet 目标（从上下文菜单「编辑」进入）
    var editTarget by remember { mutableStateOf<Pair<String, ListEntry>?>(null) }

    val viewModel: ListsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    // real groups only (capability-gated: smart lists hidden when unsupported/empty)
    val groups = buildList {
        state.collections?.let { if (it.isNotEmpty()) add(ListGroup("收藏", it)) }
        state.readLists?.let { if (it.isNotEmpty()) add(ListGroup("阅读列表", it)) }
        state.smartLists?.let { if (it.isNotEmpty()) add(ListGroup("智能列表", it)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("列表") },
                actions = {
                    // LibraryScopeMenu：全部书库 / 已钉选 / 单库
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
                        subtitle = "登录后查看列表",
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            }
            items(groups, key = { it.title }) { group ->
                val onOpenEntry: (ListEntry) -> Unit = when (group.title) {
                    "收藏" -> ({ e -> onOpenCollection(e.id) })
                    "阅读列表" -> ({ e -> onOpenReadList(e.id) })
                    else -> ({ e -> onOpenSmartList(e.id) })
                }
                ListStrip(
                    group = group,
                    hasMore = when (group.title) {
                        "收藏" -> state.collectionsHasMore
                        "阅读列表" -> state.readListsHasMore
                        else -> false
                    },
                    onLoadMore = when (group.title) {
                        "收藏" -> viewModel::loadMoreCollections
                        "阅读列表" -> viewModel::loadMoreReadLists
                        else -> ({})
                    },
                    onOpenEntry = onOpenEntry,
                    onEntryMenu = { entry -> menuTarget = group.title to entry },
                )
            }
        }
    }

    menuTarget?.let { (groupTitle, entry) ->
        val items: List<ContextMenuItem> = when (groupTitle) {
            "收藏" -> collectionContextMenuItems(
                pinned = entry.pinned,
                onEdit = { editTarget = groupTitle to entry },
            )
            "阅读列表" -> readListContextMenuItems(
                pinned = entry.pinned,
                onEdit = { editTarget = groupTitle to entry },
            )
            else -> smartListContextMenuItems(
                pinned = entry.pinned,
                onEdit = { editTarget = groupTitle to entry },
            )
        }
        ContextMenuSheet(items = items, onDismiss = { menuTarget = null })
    }

    editTarget?.let { (groupTitle, entry) ->
        when (groupTitle) {
            "收藏" -> CollectionEditSheet(
                onSave = { _, _ -> },
                onDismiss = { editTarget = null },
                initialName = entry.title,
                initialOrdered = false,
                isEdit = true,
                onDelete = { },
            )
            "阅读列表" -> ReadListEditSheet(
                onSave = { _, _, _ -> },
                onDismiss = { editTarget = null },
                initialName = entry.title,
                initialSummary = "",
                initialOrdered = true,
                isEdit = true,
                onDelete = { },
            )
            else -> SmartListEditSheet(
                onSave = { _, _, _, _ -> },
                onDismiss = { editTarget = null },
                initialName = entry.title,
                isEdit = true,
                onDelete = { },
            )
        }
    }
}

/** 单条横向条带：分区头（可点进入全页）+ 卡片横排；触底加载下一页。 */
@Composable
private fun ListStrip(
    group: ListGroup,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onOpenEntry: (ListEntry) -> Unit,
    onEntryMenu: (ListEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    // 横向条带触底判定：最后可见项进入末尾 3 项内即请求下一页。
    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore) {
        if (shouldLoadMore && hasMore) onLoadMore()
    }
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "全部",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // pinned 置顶（对齐原作置顶排前）
            items(group.items.sortedByDescending { it.pinned }, key = { it.id }) { entry ->
                ListEntryCard(
                    entry = entry,
                    group = group,
                    onClick = { onOpenEntry(entry) },
                    onLongClick = { onEntryMenu(entry) },
                    modifier = Modifier.width(110.dp),
                )
            }
            // 底部加载行：还有下一页时显示，滑到末尾会自动请求
            if (hasMore) {
                item(key = "load-more") {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(164.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

/** 列表条目卡：封面占位 + 标题 + 数量；置顶条目带 pin 标记。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListEntryCard(
    entry: ListEntry,
    group: ListGroup,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            CoverArt(
                title = entry.title,
                hue = entry.hue,
                stableId = entry.id,
                modifier = Modifier.matchParentSize(),
            )
            if (entry.pinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "已置顶",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (group.title == "智能列表") {
                "${entry.count} 项 · ${entry.targetLabel} · ${entry.modifiedLabel}"
            } else {
                "${entry.count} 项"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
