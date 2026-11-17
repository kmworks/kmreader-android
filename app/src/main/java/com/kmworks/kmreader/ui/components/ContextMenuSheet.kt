package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R

/** Context-menu entry (mirrors the Label/Button entries of the original ContextMenus; destructive uses the error color). */
data class ContextMenuItem(
    val title: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Generic long-press context menu (mirrors the KMReader ContextMenus, presented as a ModalBottomSheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(
    items: List<ContextMenuItem>,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(onDismissRequest = onDismiss) {
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

// Every action callback is nullable: null = this page does not support the action, so the
// menu entry is not rendered (no dead "nothing happens" items).

/** Series context menu (mirrors SeriesContextMenu). */
@Composable
fun seriesContextMenuItems(
    pinned: Boolean,
    hasStarted: Boolean,
    completed: Boolean,
    downloaded: Boolean,
    onTogglePin: (() -> Unit)? = null,
    onMarkRead: (() -> Unit)? = null,
    onMarkUnread: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onAddToCollection: (() -> Unit)? = null,
    onAddToReadList: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRefreshCover: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
): List<ContextMenuItem> = buildList {
    onTogglePin?.let {
        add(
            ContextMenuItem(
                stringResource(if (pinned) R.string.menu_unpin else R.string.menu_pin),
                Icons.Outlined.PushPin,
                onClick = it,
            )
        )
    }
    if (!completed) {
        onMarkRead?.let { add(ContextMenuItem(stringResource(R.string.menu_mark_read), Icons.Outlined.CheckCircle, onClick = it)) }
    }
    if (hasStarted) {
        onMarkUnread?.let { add(ContextMenuItem(stringResource(R.string.menu_mark_unread), Icons.Outlined.RadioButtonUnchecked, onClick = it)) }
    }
    onDownload?.let {
        add(
            ContextMenuItem(
                stringResource(if (downloaded) R.string.menu_remove_download else R.string.common_download),
                Icons.Outlined.Download,
                onClick = it,
            )
        )
    }
    onAddToCollection?.let { add(ContextMenuItem(stringResource(R.string.menu_add_to_collection), Icons.Outlined.CollectionsBookmark, onClick = it)) }
    onAddToReadList?.let { add(ContextMenuItem(stringResource(R.string.menu_add_to_read_list), Icons.Outlined.PlaylistAdd, onClick = it)) }
    onEdit?.let { add(ContextMenuItem(stringResource(R.string.common_edit), Icons.Outlined.Edit, onClick = it)) }
    onRefreshCover?.let { add(ContextMenuItem(stringResource(R.string.common_refresh_cover), Icons.Outlined.Image, onClick = it)) }
    onShare?.let { add(ContextMenuItem(stringResource(R.string.common_share), Icons.Outlined.Share, onClick = it)) }
    onDelete?.let { add(ContextMenuItem(stringResource(R.string.common_delete), Icons.Outlined.Delete, destructive = true, onClick = it)) }
}

/** Book context menu (mirrors BookContextMenu; "Go to Series" is the original series-navigation entry). */
@Composable
fun bookContextMenuItems(
    hasStarted: Boolean,
    completed: Boolean,
    downloaded: Boolean,
    /** One-shot: hides "Go to Series" (mirrors iOS BookContextMenu: showSeriesNavigation && !oneshot). */
    oneshot: Boolean = false,
    onOpenSeries: (() -> Unit)? = null,
    onRead: (() -> Unit)? = null,
    onReadIncognito: (() -> Unit)? = null,
    onOpenDetails: (() -> Unit)? = null,
    onMarkRead: (() -> Unit)? = null,
    onMarkUnread: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onAddToReadList: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onAnalyze: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
): List<ContextMenuItem> = buildList {
    // read actions first (mirrors BookContextMenu ControlGroup: read / peek / series)
    onRead?.let { add(ContextMenuItem(stringResource(R.string.menu_read), Icons.AutoMirrored.Outlined.MenuBook, onClick = it)) }
    onReadIncognito?.let { add(ContextMenuItem(stringResource(R.string.menu_read_incognito), Icons.Outlined.Visibility, onClick = it)) }
    onOpenDetails?.let { add(ContextMenuItem(stringResource(R.string.menu_view_details), Icons.Outlined.Info, onClick = it)) }
    if (!oneshot) {
        onOpenSeries?.let { add(ContextMenuItem(stringResource(R.string.menu_open_series), Icons.Outlined.AutoStories, onClick = it)) }
    }
    if (!completed) {
        onMarkRead?.let { add(ContextMenuItem(stringResource(R.string.menu_mark_read), Icons.Outlined.CheckCircle, onClick = it)) }
    }
    if (hasStarted) {
        onMarkUnread?.let { add(ContextMenuItem(stringResource(R.string.menu_mark_unread), Icons.Outlined.RadioButtonUnchecked, onClick = it)) }
    }
    onDownload?.let {
        add(
            ContextMenuItem(
                stringResource(if (downloaded) R.string.menu_remove_download else R.string.common_download),
                Icons.Outlined.Download,
                onClick = it,
            )
        )
    }
    onAddToReadList?.let { add(ContextMenuItem(stringResource(R.string.menu_add_to_read_list), Icons.Outlined.PlaylistAdd, onClick = it)) }
    onEdit?.let { add(ContextMenuItem(stringResource(R.string.menu_edit_metadata), Icons.Outlined.Edit, onClick = it)) }
    onAnalyze?.let { add(ContextMenuItem(stringResource(R.string.menu_analyze), Icons.Outlined.Analytics, onClick = it)) }
    onRefresh?.let { add(ContextMenuItem(stringResource(R.string.menu_refresh_metadata), Icons.Outlined.Refresh, onClick = it)) }
    onShare?.let { add(ContextMenuItem(stringResource(R.string.common_share), Icons.Outlined.Share, onClick = it)) }
    onDelete?.let { add(ContextMenuItem(stringResource(R.string.common_delete), Icons.Outlined.Delete, destructive = true, onClick = it)) }
}

/** Collection context menu (mirrors CollectionContextMenu). */
@Composable
fun collectionContextMenuItems(
    pinned: Boolean,
    onTogglePin: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRefreshCover: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
): List<ContextMenuItem> = buildList {
    onTogglePin?.let {
        add(
            ContextMenuItem(
                stringResource(if (pinned) R.string.menu_unpin else R.string.menu_pin),
                Icons.Outlined.PushPin,
                onClick = it,
            )
        )
    }
    onEdit?.let { add(ContextMenuItem(stringResource(R.string.common_edit), Icons.Outlined.Edit, onClick = it)) }
    onRefreshCover?.let { add(ContextMenuItem(stringResource(R.string.common_refresh_cover), Icons.Outlined.Image, onClick = it)) }
    onDelete?.let { add(ContextMenuItem(stringResource(R.string.menu_delete_collection), Icons.Outlined.Delete, destructive = true, onClick = it)) }
}

/** Read-list context menu (mirrors ReadListContextMenu). */
@Composable
fun readListContextMenuItems(
    pinned: Boolean,
    onTogglePin: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRefreshCover: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
): List<ContextMenuItem> = buildList {
    onTogglePin?.let {
        add(
            ContextMenuItem(
                stringResource(if (pinned) R.string.menu_unpin else R.string.menu_pin),
                Icons.Outlined.PushPin,
                onClick = it,
            )
        )
    }
    onEdit?.let { add(ContextMenuItem(stringResource(R.string.common_edit), Icons.Outlined.Edit, onClick = it)) }
    onRefreshCover?.let { add(ContextMenuItem(stringResource(R.string.common_refresh_cover), Icons.Outlined.Image, onClick = it)) }
    onShare?.let { add(ContextMenuItem(stringResource(R.string.common_share), Icons.Outlined.Share, onClick = it)) }
    onDelete?.let { add(ContextMenuItem(stringResource(R.string.menu_delete_readlist), Icons.Outlined.Delete, destructive = true, onClick = it)) }
}

/** Smart-list context menu (mirrors SmartListContextMenu). */
@Composable
fun smartListContextMenuItems(
    pinned: Boolean,
    onTogglePin: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRefreshCover: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
): List<ContextMenuItem> = buildList {
    onTogglePin?.let {
        add(
            ContextMenuItem(
                stringResource(if (pinned) R.string.menu_unpin else R.string.menu_pin),
                Icons.Outlined.PushPin,
                onClick = it,
            )
        )
    }
    onEdit?.let { add(ContextMenuItem(stringResource(R.string.common_edit), Icons.Outlined.Edit, onClick = it)) }
    onRefreshCover?.let { add(ContextMenuItem(stringResource(R.string.common_refresh_cover), Icons.Outlined.Image, onClick = it)) }
    onDelete?.let { add(ContextMenuItem(stringResource(R.string.menu_delete_smartlist), Icons.Outlined.Delete, destructive = true, onClick = it)) }
}
