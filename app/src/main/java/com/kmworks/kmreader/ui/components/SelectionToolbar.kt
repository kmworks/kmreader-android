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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R

/** Selection-mode toolbar (mirrors SelectionActionsToolbar: select all / batch actions / done). */
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
    showMarkActions: Boolean = true,
    showDownload: Boolean = true,
    showAddToReadList: Boolean = true,
    showDelete: Boolean = true,
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
            Text(stringResource(if (selectedCount == totalCount) R.string.selection_deselect_all else R.string.selection_select_all))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.selection_selected_count, selectedCount, totalCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (showMarkActions) {
            IconButton(onClick = onMarkRead, enabled = actionsEnabled) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = stringResource(R.string.menu_mark_read))
            }
            IconButton(onClick = onMarkUnread, enabled = actionsEnabled) {
                Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = stringResource(R.string.menu_mark_unread))
            }
        }
        if (showDownload) {
            IconButton(onClick = onDownload, enabled = actionsEnabled) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.common_download))
            }
        }
        if (showAddToReadList) {
            IconButton(onClick = onAddToReadList, enabled = actionsEnabled) {
                Icon(Icons.Outlined.PlaylistAdd, contentDescription = stringResource(R.string.menu_add_to_read_list))
            }
        }
        if (showDelete) {
            IconButton(onClick = onDelete, enabled = actionsEnabled) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = onDone) { Text(stringResource(R.string.common_done)) }
    }
}
