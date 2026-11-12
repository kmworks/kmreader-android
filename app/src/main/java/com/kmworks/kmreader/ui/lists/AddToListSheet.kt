package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.SampleData.ListEntry

/** 「加入收藏/阅读列表」picker（对齐 CollectionPickerSheet/ReadListPickerSheet，点选即完成）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToListSheet(
    collections: List<ListEntry>,
    readLists: List<ListEntry>,
    onSelect: (ListEntry) -> Unit,
    onDismiss: () -> Unit,
    alreadyInIds: Set<Int> = emptySet(),
    onCreateNew: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "加入列表",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            if (collections.isNotEmpty()) {
                GroupLabel("收藏")
                collections.forEach { entry ->
                    ListTargetRow(
                        entry = entry,
                        icon = { tint ->
                            Icon(
                                Icons.Outlined.CollectionsBookmark,
                                contentDescription = null,
                                tint = tint,
                            )
                        },
                        countLabel = "${entry.count} 部系列",
                        alreadyIn = entry.id in alreadyInIds,
                        onClick = {
                            onSelect(entry)
                            onDismiss()
                        },
                    )
                }
            }
            if (readLists.isNotEmpty()) {
                GroupLabel("阅读列表")
                readLists.forEach { entry ->
                    ListTargetRow(
                        entry = entry,
                        icon = { tint ->
                            Icon(
                                Icons.AutoMirrored.Outlined.ListAlt,
                                contentDescription = null,
                                tint = tint,
                            )
                        },
                        countLabel = "${entry.count} 本",
                        alreadyIn = entry.id in alreadyInIds,
                        onClick = {
                            onSelect(entry)
                            onDismiss()
                        },
                    )
                }
            }

            OutlinedButton(
                onClick = onCreateNew,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("新建列表…")
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** 可选目标行：图标 + 名称/计数；已在其中时禁用并打勾（对齐 EntityPickerSheet 行）。 */
@Composable
private fun ListTargetRow(
    entry: ListEntry,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    countLabel: String,
    alreadyIn: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (alreadyIn) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !alreadyIn, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(tint)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (alreadyIn) tint else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = countLabel,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
            )
        }
        if (alreadyIn) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已在列表中",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
