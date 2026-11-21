package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R

/** One batch action shown in [SelectionToolbar]. */
private class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Selection action bar: select all / batch actions / done.
 *
 * It belongs in the `bottomBar` slot of the page [androidx.compose.material3.Scaffold] (or any slot
 * pinned to the bottom edge) rather than inside the scrolling content: a long book list scrolls the
 * inline row away, and the user then has to scroll all the way back to the top to act on the
 * selection they just made. Pinned here, the count and every batch action stay reachable no matter
 * how far the list has been scrolled.
 *
 * The bar paints under the navigation bar strip (edge-to-edge) and keeps its own controls above it,
 * so it reads as part of the page instead of a floating strip. On compact widths the controls split
 * into two rows — count / select all / done above the batch actions — because the whole set does not
 * fit on one phone-width row, and an overflow menu would hide actions that the inline row used to
 * show in one tap.
 */
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
    val allSelected = totalCount > 0 && selectedCount == totalCount
    val selectAllLabel = stringResource(
        if (allSelected) R.string.selection_deselect_all else R.string.selection_select_all,
    )
    val doneLabel = stringResource(R.string.common_done)
    val countText = stringResource(R.string.selection_selected_count, selectedCount, totalCount)
    val actions = buildList {
        if (showMarkActions) {
            add(SelectionAction(Icons.Outlined.CheckCircle, stringResource(R.string.menu_mark_read), onClick = onMarkRead))
            add(
                SelectionAction(
                    Icons.Outlined.RadioButtonUnchecked,
                    stringResource(R.string.menu_mark_unread),
                    onClick = onMarkUnread,
                ),
            )
        }
        if (showDownload) {
            add(SelectionAction(Icons.Outlined.Download, stringResource(R.string.common_download), onClick = onDownload))
        }
        if (showAddToReadList) {
            add(
                SelectionAction(
                    Icons.AutoMirrored.Outlined.PlaylistAdd,
                    stringResource(R.string.menu_add_to_read_list),
                    onClick = onAddToReadList,
                ),
            )
        }
        if (showDelete) {
            add(
                SelectionAction(
                    Icons.Outlined.Delete,
                    stringResource(R.string.common_delete),
                    destructive = true,
                    onClick = onDelete,
                ),
            )
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < SelectionBarCompactWidth) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SelectionBarRowHeight)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onDone) {
                                Icon(Icons.Filled.Close, contentDescription = doneLabel)
                            }
                            Text(
                                text = countText,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            IconButton(onClick = onSelectAll) {
                                Icon(Icons.Outlined.SelectAll, contentDescription = selectAllLabel)
                            }
                        }
                        // The batch actions sit on the bottom row so the thumb reaches them first.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            actions.forEach { action ->
                                SelectionActionIcon(
                                    action = action,
                                    enabled = actionsEnabled,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SelectionBarRegularHeight)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDone) {
                            Icon(Icons.Filled.Close, contentDescription = doneLabel)
                        }
                        Text(
                            text = countText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onSelectAll) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(selectAllLabel)
                        }
                        actions.forEach { action ->
                            SelectionActionIcon(action = action, enabled = actionsEnabled)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionActionIcon(
    action: SelectionAction,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = action.onClick, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label,
            // LocalContentColor already carries the disabled alpha; the error tint does not.
            tint = if (action.destructive) {
                MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f)
            } else {
                LocalContentColor.current
            },
        )
    }
}

/** Row height shared by both layouts, matching the stock compact action bar. */
private val SelectionBarRowHeight = 48.dp

/** Height of the single-row layout on wide panes. */
private val SelectionBarRegularHeight = 56.dp

/**
 * Below this width the bar switches to the two-row layout. The single row needs roughly 550.dp for
 * close / count / select all plus five 48.dp actions, so phone widths cannot keep every action on one
 * row without dropping some into an overflow menu or shrinking the touch targets.
 */
private val SelectionBarCompactWidth = 600.dp
