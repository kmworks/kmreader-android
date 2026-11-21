package com.kmworks.kmreader.ui.offline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.download.DownloadTaskUi
import com.kmworks.kmreader.core.download.DownloadedBookUi
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.hasScrolled
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Downloads tab (mirrors OfflineView + OfflineTasksView + OfflineDownloadedBookGroupView + ReadListOfflinePolicyMenuItems). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(
    onOpenBook: (Book) -> Unit = {},
    onOpenSeries: (Series) -> Unit = {},
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

    // Downloaded rows only know the DTO ids, so the detail pages are opened from a resolved Book/Series
    // (mirrors the iOS offline cards' context menus); the screen consumes the event once it was pushed.
    val navigation by viewModel.navigation.collectAsState()
    LaunchedEffect(navigation) {
        when (val target = navigation) {
            is OfflineNavigation.OpenBook -> {
                onOpenBook(target.book)
                viewModel.consumeNavigation()
            }
            is OfflineNavigation.OpenSeries -> {
                onOpenSeries(target.series)
                viewModel.consumeNavigation()
            }
            null -> Unit
        }
    }

    // The frosted bar floats over the page: the download lists scroll underneath it.
    val singleListState = rememberLazyListState()
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()
    val hazeState = rememberHazeState()
    val sheetVisible = singleListState.hasScrolled() ||
        leftListState.hasScrolled() ||
        rightListState.hasScrolled()
    val onPauseResume: (DownloadTaskUi) -> Unit = { task ->
        when (task.status) {
            DownloadTaskUi.Status.Downloading,
            DownloadTaskUi.Status.Queued,
            -> viewModel.pause(task)
            DownloadTaskUi.Status.Paused -> viewModel.resume(task)
            DownloadTaskUi.Status.Failed -> viewModel.retry(task)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GlassTopBar(
            title = stringResource(R.string.offline_title),
            modifier = Modifier.zIndex(1f),
            sheetVisible = sheetVisible,
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val pagePadding = PaddingValues(
                top = glassTopBarTopPadding() + 16.dp,
                bottom = 16.dp + LocalNavOverlaySpace.current,
            )
            if (wide) {
                // Landscape/tablet: two aligned panes use the width instead of leaving a narrow
                // centered strip with a lot of empty space on both sides.
                val outerPadding = maxOf(24.dp, (maxWidth - 1200.dp) / 2)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState)
                        .padding(horizontal = outerPadding),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LazyColumn(
                        state = leftListState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding = pagePadding,
                    ) {
                        storageSection(used = storage.first, total = storage.second)
                        policySection(
                            autoDownloadUnread = autoDownloadUnread,
                            wifiOnly = wifiOnly,
                            onAutoDownloadUnreadChange = { viewModel.setAutoDownloadUnread(it) },
                            onWifiOnlyChange = { viewModel.setWifiOnly(it) },
                        )
                        item(key = "leftBottomSpacer") { Spacer(Modifier.height(16.dp)) }
                    }
                    LazyColumn(
                        state = rightListState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding = pagePadding,
                    ) {
                        queueSection(
                            tasks = tasks,
                            onPauseResume = onPauseResume,
                            onCancel = { viewModel.cancel(it) },
                        )
                        downloadedSection(
                            downloaded = downloaded,
                            onRemove = { viewModel.deleteBook(it) },
                            onOpenBook = viewModel::openBookDetails,
                            onOpenSeries = viewModel::openSeriesDetails,
                        )
                        item(key = "rightBottomSpacer") { Spacer(Modifier.height(16.dp)) }
                    }
                }
            } else {
                val sidePadding = maxOf(16.dp, (maxWidth - 760.dp) / 2)
                LazyColumn(
                    state = singleListState,
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    contentPadding = PaddingValues(
                        start = sidePadding,
                        top = glassTopBarTopPadding() + 16.dp,
                        end = sidePadding,
                        bottom = 16.dp + LocalNavOverlaySpace.current,
                    ),
                ) {
                    storageSection(used = storage.first, total = storage.second)
                    queueSection(
                        tasks = tasks,
                        onPauseResume = onPauseResume,
                        onCancel = { viewModel.cancel(it) },
                    )
                    policySection(
                        autoDownloadUnread = autoDownloadUnread,
                        wifiOnly = wifiOnly,
                        onAutoDownloadUnreadChange = { viewModel.setAutoDownloadUnread(it) },
                        onWifiOnlyChange = { viewModel.setWifiOnly(it) },
                    )
                    downloadedSection(
                        downloaded = downloaded,
                        onRemove = { viewModel.deleteBook(it) },
                        onOpenBook = viewModel::openBookDetails,
                        onOpenSeries = viewModel::openSeriesDetails,
                    )
                    item(key = "bottomSpacer") { Spacer(Modifier.height(16.dp)) }
                }
            }
        }

    }
}

/**
 * The page is split into shared sections so the portrait single column and the landscape
 * two-pane layout render exactly the same cards.
 */
private fun LazyListScope.storageSection(used: Long, total: Long) {
    item(key = "storageHeader") {
        OfflineSectionHeader(
            title = stringResource(R.string.offline_storage_usage),
            icon = Icons.Outlined.Storage,
        )
    }
    item(key = "storage") {
        StorageCard(used = used, total = total)
    }
}

private fun LazyListScope.queueSection(
    tasks: List<DownloadTaskUi>,
    onPauseResume: (DownloadTaskUi) -> Unit,
    onCancel: (DownloadTaskUi) -> Unit,
) {
    item(key = "queueHeader") {
        OfflineSectionHeader(
            title = stringResource(R.string.offline_queue),
            icon = Icons.Filled.Download,
        )
    }
    if (tasks.isEmpty()) {
        item(key = "queueEmpty") {
            OfflineCard {
                OfflineEmptyState(
                    icon = Icons.Filled.Download,
                    title = stringResource(R.string.offline_queue_empty),
                    caption = stringResource(R.string.offline_queue_empty_caption),
                )
            }
        }
    } else {
        itemsIndexed(tasks, key = { _, task -> task.bookDtoId }) { index, task ->
            OfflineGroupedRow(index = index, lastIndex = tasks.lastIndex) {
                DownloadTaskRow(
                    task = task,
                    onPauseResume = { onPauseResume(task) },
                    onCancel = { onCancel(task) },
                )
            }
        }
    }
}

private fun LazyListScope.policySection(
    autoDownloadUnread: Boolean,
    wifiOnly: Boolean,
    onAutoDownloadUnreadChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
) {
    item(key = "policyHeader") {
        OfflineSectionHeader(
            title = stringResource(R.string.offline_policy),
            icon = Icons.Outlined.Tune,
        )
    }
    item(key = "policy") {
        // Policy switches (aligned with ReadListOfflinePolicyMenuItems)
        OfflineCard {
            PolicySwitchRow(
                title = stringResource(R.string.offline_auto_download_unread),
                subtitle = stringResource(R.string.offline_auto_download_unread_caption),
                checked = autoDownloadUnread,
                onCheckedChange = onAutoDownloadUnreadChange,
            )
            OfflineRowDivider()
            PolicySwitchRow(
                title = stringResource(R.string.offline_wifi_only),
                subtitle = stringResource(R.string.offline_wifi_only_caption),
                checked = wifiOnly,
                onCheckedChange = onWifiOnlyChange,
            )
        }
    }
}

private fun LazyListScope.downloadedSection(
    downloaded: List<DownloadedBookUi>,
    onRemove: (DownloadedBookUi) -> Unit,
    onOpenBook: (DownloadedBookUi) -> Unit,
    onOpenSeries: (DownloadedBookUi) -> Unit,
) {
    item(key = "downloadedHeader") {
        OfflineSectionHeader(
            title = stringResource(R.string.offline_downloaded),
            icon = Icons.AutoMirrored.Filled.MenuBook,
        )
    }
    if (downloaded.isEmpty()) {
        item(key = "downloadedEmpty") {
            OfflineCard {
                OfflineEmptyState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = stringResource(R.string.offline_downloaded_empty),
                    caption = stringResource(R.string.offline_downloaded_empty_caption),
                )
            }
        }
    } else {
        itemsIndexed(downloaded, key = { _, item -> item.bookDtoId }) { index, item ->
            OfflineGroupedRow(index = index, lastIndex = downloaded.lastIndex) {
                DownloadedRow(
                    item = item,
                    onRemove = { onRemove(item) },
                    onOpenBook = { onOpenBook(item) },
                    onOpenSeries = { onOpenSeries(item) },
                )
            }
        }
    }
}

/** Section label above one rounded card (iOS inset-grouped Form header). */
@Composable
private fun OfflineSectionHeader(title: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One rounded card that holds a group of rows. */
@Composable
private fun OfflineCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/**
 * Row of a lazily rendered list card: the first/last rows carry the card's rounded corners,
 * so a long downloaded list never composes every row at once.
 */
@Composable
private fun OfflineGroupedRow(
    index: Int,
    lastIndex: Int,
    content: @Composable () -> Unit,
) {
    val corner = 16.dp
    Surface(
        shape = RoundedCornerShape(
            topStart = if (index == 0) corner else 0.dp,
            topEnd = if (index == 0) corner else 0.dp,
            bottomStart = if (index == lastIndex) corner else 0.dp,
            bottomEnd = if (index == lastIndex) corner else 0.dp,
        ),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (index > 0) OfflineRowDivider()
            content()
        }
    }
}

@Composable
private fun OfflineRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    )
}

@Composable
private fun OfflineEmptyState(icon: ImageVector, title: String, caption: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StorageCard(used: Long, total: Long) {
    val fraction = if (total > 0) (used / total.toFloat()).coerceIn(0f, 1f) else 0f
    OfflineCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatBytes(used),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (total > 0) {
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.offline_storage_total, formatBytes(total)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = task.title,
            hue = (task.bookDtoId.hashCode() and Int.MAX_VALUE % 360).toFloat(),
            stableId = StableIds.book(task.bookDtoId),
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (task.kind.isSingleFile) {
                    stringResource(R.string.offline_task_file)
                } else {
                    stringResource(R.string.offline_task_pages, task.pageDone, task.pageTotal)
                },
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
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(task.progress * 100).toInt()}% · ${stringResource(R.string.offline_status_downloading)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DownloadTaskUi.Status.Queued -> {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.offline_status_queued),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DownloadTaskUi.Status.Paused -> {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (task.kind.isSingleFile) {
                            stringResource(R.string.offline_task_paused_file)
                        } else {
                            stringResource(R.string.offline_task_paused_detail, task.pageDone)
                        },
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
        IconButton(onClick = onPauseResume) {
            Icon(
                imageVector = when (task.status) {
                    DownloadTaskUi.Status.Downloading,
                    DownloadTaskUi.Status.Queued,
                    -> Icons.Filled.Pause
                    DownloadTaskUi.Status.Paused -> Icons.Filled.PlayArrow
                    DownloadTaskUi.Status.Failed -> Icons.Filled.Refresh
                },
                contentDescription = stringResource(
                    when (task.status) {
                        DownloadTaskUi.Status.Downloading,
                        DownloadTaskUi.Status.Queued,
                        -> R.string.offline_pause
                        DownloadTaskUi.Status.Paused -> R.string.offline_resume
                        DownloadTaskUi.Status.Failed -> R.string.common_retry
                    },
                ),
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_cancel),
                tint = MaterialTheme.colorScheme.error,
            )
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
            // Whole row toggles, like the iOS Form switch rows.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedRow(
    item: DownloadedBookUi,
    onRemove: () -> Unit,
    onOpenBook: () -> Unit,
    onOpenSeries: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpenBook,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(
                title = item.title,
                hue = (item.bookDtoId.hashCode() and Int.MAX_VALUE % 360).toFloat(),
                stableId = StableIds.book(item.bookDtoId),
                modifier = Modifier
                    .width(44.dp)
                    .aspectRatio(2f / 3f),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            // Mirrors the iOS offline card context menus: open the book/series detail, or release the storage.
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.common_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_view_details)) },
                onClick = {
                    menuOpen = false
                    onOpenBook()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_open_series)) },
                onClick = {
                    menuOpen = false
                    onOpenSeries()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_remove_download)) },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
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
