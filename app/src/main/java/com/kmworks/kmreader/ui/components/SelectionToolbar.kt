package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 多选完成态工具条（对齐 SelectionActionsToolbar：全选/批量操作/完成）。 */
@Composable
fun SelectionToolbar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDownload: () -> Unit,
    onAddToReadList: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionsEnabled = selectedCount > 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSelectAll) {
            Icon(Icons.Outlined.SelectAll, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (selectedCount == totalCount) "取消全选" else "全选")
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "已选 $selectedCount / $totalCount",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onMarkRead, enabled = actionsEnabled) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = "标记为已读")
        }
        IconButton(onClick = onMarkUnread, enabled = actionsEnabled) {
            Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = "标记为未读")
        }
        IconButton(onClick = onDownload, enabled = actionsEnabled) {
            Icon(Icons.Outlined.Download, contentDescription = "下载")
        }
        IconButton(onClick = onAddToReadList, enabled = actionsEnabled) {
            Icon(Icons.Outlined.PlaylistAdd, contentDescription = "加入阅读列表")
        }
        IconButton(onClick = onDelete, enabled = actionsEnabled) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onDone) { Text("完成") }
    }
}
