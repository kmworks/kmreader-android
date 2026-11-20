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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.download.DownloadTaskUi
import com.kmworks.kmreader.core.download.DownloadedBookUi
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.hasScrolled
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

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
    val libraries by viewModel.libraries.collectAsState()
    val libraryScope by viewModel.libraryScope.collectAsState()
    var scopeMenuOpen by remember { mutableStateOf(false) }

    // The frosted bar floats over the page: the download list scrolls underneath it.
    val pageListState = rememberLazyListState()
    val hazeState = rememberHazeState()

    Box(modifier = Modifier.fillMaxSize()) {
        GlassTopBar(
            title = stringResource(R.string.offline_title),
            modifier = Modifier.zIndex(1f),
            sheetVisible = pageListState.hasScrolled(),
            hazeState = hazeState,
            actions = {
                // Library scope (mirrors LibraryScopeMenu): real library list filtering queue and downloads
                Box {
                    IconButton(onClick = { scopeMenuOpen = true }) {
                        Icon(
                            Icons.Outlined.Checklist,
                            contentDescription = stringResource(R.string.offline_library_scope),
                        )
                    }
                    DropdownMenu(
                        expanded = scopeMenuOpen,
                        onDismissRequest = { scopeMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_all_libraries)) },
                            onClick = {
                                viewModel.setLibraryScope(null)
                                scopeMenuOpen = false
                            },
                        )
                        libraries.forEach { library ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (library.id == libraryScope) "✓ ${library.name}"
                                        else library.name,
                                    )
                                },
                                onClick = {
                                    viewModel.setLibraryScope(library.id)
                                    scopeMenuOpen = false
                                },
                            )
                        }
                    }
                }
            },
        )
        LazyColumn(
            state = pageListState,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = glassTopBarTopPadding() + 16.dp,
                end = 16.dp,
                bottom = 16.dp + LocalNavOverlaySpace.current,
            ),
        ) {
            item(key = "storage") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.offline_storage_usage),
                            style = MaterialTheme.typography.titleSmall,
                        )
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
                            text = stringResource(
                                R.string.offline_storage_detail,
                                formatBytes(storage.first),
                                formatBytes(total),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                // Download queue (mirrors OfflineTasksView sections)
                Text(stringResource(R.string.offline_queue), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            if (tasks.isEmpty()) {
                item(key = "queueEmpty") {
                    Text(
                        stringResource(R.string.offline_queue_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(tasks, key = { it.bookDtoId }) { task ->
                    DownloadTaskRow(
                        task = task,
                        onPauseResume = {
                            when (task.status) {
                                DownloadTaskUi.Status.Downloading -> viewModel.pause(task)
                                DownloadTaskUi.Status.Paused -> viewModel.resume(task)
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
                Text(stringResource(R.string.offline_policy), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                // Policy switches (aligned with ReadListOfflinePolicyMenuItems)
                PolicySwitchRow(
                    title = stringResource(R.string.offline_auto_download_unread),
                    subtitle = stringResource(R.string.offline_auto_download_unread_caption),
                    checked = autoDownloadUnread,
                    onCheckedChange = { viewModel.setAutoDownloadUnread(it) },
                )
                PolicySwitchRow(
                    title = stringResource(R.string.offline_wifi_only),
                    subtitle = stringResource(R.string.offline_wifi_only_caption),
                    checked = wifiOnly,
                    onCheckedChange = { viewModel.setWifiOnly(it) },
                )

                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.offline_downloaded), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            if (downloaded.isEmpty()) {
                item(key = "downloadedEmpty") {
                    Text(
                        stringResource(R.string.offline_downloaded_empty),
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
                stringResource(R.string.offline_task_pages, task.pageDone, task.pageTotal),
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
                        "${(task.progress * 100).toInt()}% · ${stringResource(R.string.offline_status_downloading)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DownloadTaskUi.Status.Paused -> {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.offline_task_paused_detail, task.pageDone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DownloadTaskUi.Status.Failed -> {
                    Text(
                        stringResource(R.string.offline_task_failed_detail),
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
                        DownloadTaskUi.Status.Downloading -> stringResource(R.string.offline_pause)
                        DownloadTaskUi.Status.Paused -> stringResource(R.string.offline_resume)
                        DownloadTaskUi.Status.Failed -> stringResource(R.string.common_retry)
                    },
                )
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
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
                text = stringResource(R.string.offline_book_pages_size, item.pageCount, formatBytes(item.sizeBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = stringResource(R.string.offline_completed),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        // Release storage (mirrors swipe-to-delete / delete-all)
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = stringResource(R.string.offline_delete_free_space),
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
