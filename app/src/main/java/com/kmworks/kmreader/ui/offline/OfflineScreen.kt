package com.kmworks.kmreader.ui.offline

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.DownloadTask
import com.kmworks.kmreader.data.DownloadTaskStatus
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.SampleSystemData
import com.kmworks.kmreader.ui.components.CoverArt

/** Downloads tab (mirrors OfflineView + OfflineTasksView + OfflineDownloadedBookGroupView + ReadListOfflinePolicyMenuItems). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen() {
    val queue = remember { SampleSystemData.downloadQueue.toMutableStateList() }
    var autoDownloadUnread by remember { mutableStateOf(true) }
    var wifiOnly by remember { mutableStateOf(true) }
    val downloaded = remember { SampleData.downloadedSeries.toMutableStateList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载") },
                actions = {
                    // Library scope (mirrors LibraryScopeMenu)
                    IconButton(onClick = { }) {
                        Icon(Icons.Outlined.Checklist, contentDescription = "库范围")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item(key = "storage") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("存储占用", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = {
                                SampleData.storageUsedGb / SampleData.storageTotalGb
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "已下载 ${SampleData.storageUsedGb} GB，共 ${SampleData.storageTotalGb.toInt()} GB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                // Download queue (mirrors OfflineTasksView sections)
                Text("下载队列", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            if (queue.isEmpty()) {
                item(key = "queueEmpty") {
                    Text(
                        "暂无下载任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(queue, key = { it.id }) { task ->
                    DownloadTaskRow(
                        task = task,
                        onPauseResume = {
                            val index = queue.indexOfFirst { it.id == task.id }
                            if (index >= 0) {
                                queue[index] = queue[index].copy(
                                    status = when (queue[index].status) {
                                        DownloadTaskStatus.Downloading -> DownloadTaskStatus.Paused
                                        DownloadTaskStatus.Paused -> DownloadTaskStatus.Downloading
                                        DownloadTaskStatus.Failed -> DownloadTaskStatus.Downloading
                                    },
                                    error = null,
                                )
                            }
                        },
                        onCancel = { queue.removeAll { it.id == task.id } },
                    )
                    HorizontalDivider()
                }
            }

            item(key = "policy") {
                Spacer(Modifier.height(24.dp))
                Text("下载策略", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                // Mirrors ReadListOfflinePolicyMenuItems (unreadOnly / wifi-only)
                PolicySwitchRow(
                    title = "自动下载未读内容",
                    subtitle = "新内容入库后自动下载未读分册",
                    checked = autoDownloadUnread,
                    onCheckedChange = { autoDownloadUnread = it },
                )
                PolicySwitchRow(
                    title = "仅 Wi-Fi 下载",
                    subtitle = "使用蜂窝网络时暂停下载",
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it },
                )

                Spacer(Modifier.height(24.dp))
                Text("已下载系列", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            items(downloaded, key = { it.title }) { item ->
                DownloadedRow(
                    item = item,
                    onRemove = { downloaded.removeAll { it.title == item.title } },
                )
            }

            item(key = "bottomSpacer") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = task.seriesTitle,
            hue = task.hue,
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(task.seriesTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                task.bookLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            when (task.status) {
                DownloadTaskStatus.Downloading,
                DownloadTaskStatus.Paused,
                -> {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(task.progress * 100).toInt()}% · ${task.status.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DownloadTaskStatus.Failed -> {
                    Text(
                        task.error ?: "下载失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = onPauseResume) {
                Text(
                    when (task.status) {
                        DownloadTaskStatus.Downloading -> "暂停"
                        DownloadTaskStatus.Paused -> "继续"
                        DownloadTaskStatus.Failed -> "重试"
                    },
                )
            }
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun PolicySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DownloadedRow(item: SampleData.DownloadedSeries, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = item.title,
            hue = item.hue,
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "共 ${item.books} 册 · ${item.sizeGb} GB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "已完成",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        // Release storage (mirrors swipe-to-delete / delete-all)
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = "删除释放空间",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
