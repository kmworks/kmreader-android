package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 上下文菜单条目（对齐原作各 ContextMenu 的 Label/Button；destructive 用 error 色）。 */
data class ContextMenuItem(
    val title: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** 通用长按上下文菜单（对齐 KMReader 各 ContextMenu，设计稿以 ModalBottomSheet 呈现）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(
    items: List<ContextMenuItem>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            items.forEach { item ->
                val tint = if (item.destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.enabled) {
                            item.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint)
                    Spacer(Modifier.width(16.dp))
                    Text(item.title, color = tint)
                }
            }
        }
    }
}

/** 系列上下文菜单（对齐 SeriesContextMenu）。 */
fun seriesContextMenuItems(
    pinned: Boolean,
    hasStarted: Boolean,
    completed: Boolean,
    downloaded: Boolean,
    onTogglePin: () -> Unit = {},
    onMarkRead: () -> Unit = {},
    onMarkUnread: () -> Unit = {},
    onDownload: () -> Unit = {},
    onAddToCollection: () -> Unit = {},
    onAddToReadList: () -> Unit = {},
    onEdit: () -> Unit = {},
    onRefreshCover: () -> Unit = {},
    onShare: () -> Unit = {},
    onDelete: () -> Unit = {},
): List<ContextMenuItem> = buildList {
    add(ContextMenuItem(if (pinned) "取消置顶" else "置顶", Icons.Outlined.PushPin, onClick = onTogglePin))
    if (!completed) {
        add(ContextMenuItem("标记为已读", Icons.Outlined.CheckCircle, onClick = onMarkRead))
    }
    if (hasStarted) {
        add(ContextMenuItem("标记为未读", Icons.Outlined.RadioButtonUnchecked, onClick = onMarkUnread))
    }
    add(
        ContextMenuItem(
            if (downloaded) "移除下载" else "下载",
            Icons.Outlined.Download,
            onClick = onDownload,
        )
    )
    add(ContextMenuItem("加入收藏", Icons.Outlined.CollectionsBookmark, onClick = onAddToCollection))
    add(ContextMenuItem("加入阅读列表", Icons.Outlined.PlaylistAdd, onClick = onAddToReadList))
    add(ContextMenuItem("编辑", Icons.Outlined.Edit, onClick = onEdit))
    add(ContextMenuItem("刷新封面", Icons.Outlined.Image, onClick = onRefreshCover))
    add(ContextMenuItem("分享", Icons.Outlined.Share, onClick = onShare))
    add(ContextMenuItem("删除", Icons.Outlined.Delete, destructive = true, onClick = onDelete))
}

/** 书籍上下文菜单（对齐 BookContextMenu；「访问系列」对应原作的系列导航入口）。 */
fun bookContextMenuItems(
    hasStarted: Boolean,
    completed: Boolean,
    downloaded: Boolean,
    onOpenSeries: () -> Unit = {},
    onMarkRead: () -> Unit = {},
    onMarkUnread: () -> Unit = {},
    onDownload: () -> Unit = {},
    onAddToReadList: () -> Unit = {},
    onEdit: () -> Unit = {},
    onAnalyze: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onShare: () -> Unit = {},
    onDelete: () -> Unit = {},
): List<ContextMenuItem> = buildList {
    add(ContextMenuItem("访问系列", Icons.Outlined.AutoStories, onClick = onOpenSeries))
    if (!completed) {
        add(ContextMenuItem("标记为已读", Icons.Outlined.CheckCircle, onClick = onMarkRead))
    }
    if (hasStarted) {
        add(ContextMenuItem("标记为未读", Icons.Outlined.RadioButtonUnchecked, onClick = onMarkUnread))
    }
    add(
        ContextMenuItem(
            if (downloaded) "移除下载" else "下载",
            Icons.Outlined.Download,
            onClick = onDownload,
        )
    )
    add(ContextMenuItem("加入阅读列表", Icons.Outlined.PlaylistAdd, onClick = onAddToReadList))
    add(ContextMenuItem("编辑元数据", Icons.Outlined.Edit, onClick = onEdit))
    add(ContextMenuItem("分析", Icons.Outlined.Analytics, onClick = onAnalyze))
    add(ContextMenuItem("刷新元数据", Icons.Outlined.Refresh, onClick = onRefresh))
    add(ContextMenuItem("分享", Icons.Outlined.Share, onClick = onShare))
    add(ContextMenuItem("删除", Icons.Outlined.Delete, destructive = true, onClick = onDelete))
}

/** 收藏上下文菜单（对齐 CollectionContextMenu）。 */
fun collectionContextMenuItems(
    pinned: Boolean,
    onTogglePin: () -> Unit = {},
    onEdit: () -> Unit = {},
    onRefreshCover: () -> Unit = {},
    onDelete: () -> Unit = {},
): List<ContextMenuItem> = listOf(
    ContextMenuItem(if (pinned) "取消置顶" else "置顶", Icons.Outlined.PushPin, onClick = onTogglePin),
    ContextMenuItem("编辑", Icons.Outlined.Edit, onClick = onEdit),
    ContextMenuItem("刷新封面", Icons.Outlined.Image, onClick = onRefreshCover),
    ContextMenuItem("删除收藏", Icons.Outlined.Delete, destructive = true, onClick = onDelete),
)

/** 阅读列表上下文菜单（对齐 ReadListContextMenu）。 */
fun readListContextMenuItems(
    pinned: Boolean,
    onTogglePin: () -> Unit = {},
    onEdit: () -> Unit = {},
    onRefreshCover: () -> Unit = {},
    onShare: () -> Unit = {},
    onDelete: () -> Unit = {},
): List<ContextMenuItem> = listOf(
    ContextMenuItem(if (pinned) "取消置顶" else "置顶", Icons.Outlined.PushPin, onClick = onTogglePin),
    ContextMenuItem("编辑", Icons.Outlined.Edit, onClick = onEdit),
    ContextMenuItem("刷新封面", Icons.Outlined.Image, onClick = onRefreshCover),
    ContextMenuItem("分享", Icons.Outlined.Share, onClick = onShare),
    ContextMenuItem("删除阅读列表", Icons.Outlined.Delete, destructive = true, onClick = onDelete),
)

/** 智能列表上下文菜单（对齐 SmartListContextMenu）。 */
fun smartListContextMenuItems(
    pinned: Boolean,
    onTogglePin: () -> Unit = {},
    onEdit: () -> Unit = {},
    onRefreshCover: () -> Unit = {},
    onDelete: () -> Unit = {},
): List<ContextMenuItem> = listOf(
    ContextMenuItem(if (pinned) "取消置顶" else "置顶", Icons.Outlined.PushPin, onClick = onTogglePin),
    ContextMenuItem("编辑", Icons.Outlined.Edit, onClick = onEdit),
    ContextMenuItem("刷新封面", Icons.Outlined.Image, onClick = onRefreshCover),
    ContextMenuItem("删除智能列表", Icons.Outlined.Delete, destructive = true, onClick = onDelete),
)
