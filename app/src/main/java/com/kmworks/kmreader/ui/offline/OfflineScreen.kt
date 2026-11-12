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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.core.download.DownloadTaskUi
import com.kmworks.kmreader.core.download.DownloadedBookUi
import com.kmworks.kmreader.ui.components.CoverArt

/** Downloads tab (mirrors OfflineView + OfflineTasksView + OfflineDownloadedBookGroupView + ReadListOfflinePolicyMenuItems). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(
    viewModel: DownloadViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsState()
    val downloaded by viewModel.downloaded.collectAsState()
    val storage by viewModel.storage.collectAsState()
    val autoDownloadUnread by viewModel.autoDownloadUnread.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()

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
                        val total = storage.second
                        LinearProgressIndicator(
                            progress = {
                                if (total > 0) (storage.first / total.toFloat()).coerceIn(0f, 1f) else 0f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "已下载 ${formatBytes(storage.first)}，共 ${formatBytes(total)}",
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

            if (tasks.isEmpty()) {
                item(key = "queueEmpty") {
                    Text(
                        "暂无下载任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(tasks, key = { it.bookDtoId }) { task ->
                    DownloadTaskRow(
                        task = task,
                        // MVP：暂停降级为取消（WorkManager 单请求不支持断点暂停）
                        onPauseResume = {
                            when (task.status) {
                                DownloadTaskUi.Status.Downloading -> viewModel.cancel(task)
                                DownloadTaskUi.Status.Failed -> viewModel.retry(task)
                            }
                        },
                        onCancel = { viewModel.cancel(task) },
                    )
                    HorizontalDivider()
                }
            }

            item(key = "policy") {
                Spacer(Modifier.height(24.dp))
                Text("下载策略", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                // 策略开关（对齐 ReadListOfflinePolicyMenuItems）
                PolicySwitchRow(
                    title = "自动下载未读内容",
                    subtitle = "将在后续版本自动下载（当前仅保存偏好）",
                    checked = autoDownloadUnread,
                    onCheckedChange = { viewModel.setAutoDownloadUnread(it) },
                )
                PolicySwitchRow(
                    title = "仅 Wi-Fi 下载",
                    subtitle = "使用蜂窝网络时暂停下载",
                    checked = wifiOnly,
                    onCheckedChange = { viewModel.setWifiOnly(it) },
                )

                Spacer(Modifier.height(24.dp))
                Text("已下载书籍", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            if (downloaded.isEmpty()) {
                item(key = "downloadedEmpty") {
                    Text(
                        "暂无已下载内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(downloaded, key = { it.bookDtoId }) { item ->
                    DownloadedRow(
                        item = item,
                        onRemove = { viewModel.deleteBook(item) },
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTaskUi,
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
            title = task.title,
            hue = (task.bookDtoId.hashCode() and Int.MAX_VALUE % 360).toFloat(),
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${task.pageDone}/${task.pageTotal} 页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            when (task.status) {
                DownloadTaskUi.Status.Downloading -> {
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

                DownloadTaskUi.Status.Failed -> {
                    Text(
                        "下载失败，可重试",
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
                        DownloadTaskUi.Status.Downloading -> "暂停"
                        DownloadTaskUi.Status.Failed -> "重试"
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
private fun DownloadedRow(item: DownloadedBookUi, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = item.title,
            hue = (item.bookDtoId.hashCode() and Int.MAX_VALUE % 360).toFloat(),
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${item.pageCount} 页 · ${formatBytes(item.sizeBytes)}",
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toFloat())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toFloat())
    else -> "$bytes B"
}
