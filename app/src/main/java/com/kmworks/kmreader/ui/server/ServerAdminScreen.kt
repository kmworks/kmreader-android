package com.kmworks.kmreader.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.HistoricalEventDto
import com.kmworks.kmreader.core.server.ActuatorScheduledTaskDto
import com.kmworks.kmreader.core.server.LibraryStatsDto
import com.kmworks.kmreader.core.server.SessionEntry
import com.kmworks.kmreader.core.server.SessionScope
import com.kmworks.kmreader.core.server.TaskTypeStatsDto
import com.kmworks.kmreader.ui.components.relativeTimeLabel
import com.kmworks.kmreader.ui.settings.SettingsBackIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Server management screen (kmrs admin surface, mirrors iOS ServerInfoView and its sub-pages).
 *
 * The info page is the hub: resource/content snapshots from `/api/v1/stats/server` (kmrs) or the
 * actuator metric family (stock Komga), plus navigation rows to libraries / tasks / sessions /
 * scheduled tasks / history / filesystem, and the maintenance actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServerAdminScreen(
    onBack: () -> Unit,
    initialPage: ServerAdminPage = ServerAdminPage.Info,
    viewModel: ServerAdminViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmShutdown by remember { mutableStateOf(false) }
    var confirmCancelAll by remember { mutableStateOf(false) }

    LaunchedEffect(initialPage) { viewModel.open(initialPage) }

    LaunchedEffect(state.messageRes) {
        val res = state.messageRes ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(res))
        viewModel.consumeMessage()
    }

    BackHandler {
        if (state.page == ServerAdminPage.Info) onBack() else viewModel.open(ServerAdminPage.Info)
    }

    // Pages with their own screen + view model render standalone so the app bar is not doubled.
    when (state.page) {
        ServerAdminPage.LibrariesAdmin -> {
            LibraryAdminScreen(onBack = { viewModel.open(ServerAdminPage.Info) })
            return
        }
        ServerAdminPage.ApiKeys -> {
            ApiKeysScreen(onBack = { viewModel.open(ServerAdminPage.Info) })
            return
        }
        ServerAdminPage.AuthActivity -> {
            AuthenticationActivityScreen(onBack = { viewModel.open(ServerAdminPage.Info) })
            return
        }
        ServerAdminPage.Media -> {
            MediaManagementScreen(onBack = { viewModel.open(ServerAdminPage.Info) })
            return
        }
        else -> Unit
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(pageTitleRes(state.page))) },
                // Only the Info page is the root of the split's pane; pages opened from it keep their arrow
                // so they can return to Info.
                navigationIcon = SettingsBackIcon(paneRoot = state.page == ServerAdminPage.Info) {
                    IconButton(onClick = {
                        if (state.page == ServerAdminPage.Info) onBack()
                        else viewModel.open(ServerAdminPage.Info)
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    // Both the hub and its info sub-page show the same snapshot, so both can refresh it.
                    if (
                        (state.page == ServerAdminPage.Info || state.page == ServerAdminPage.ServerInfo) &&
                        state.isAdmin
                    ) {
                        IconButton(onClick = { viewModel.open(state.page) }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.server_admin_refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.isAdmin -> CenteredMessage(R.string.server_admin_admin_required)
                state.page == ServerAdminPage.Info -> InfoPage(
                    taskQueueCount = state.tasks.queueSize,
                    onOpen = viewModel::open,
                    onDownloadLog = { viewModel.open(ServerAdminPage.Log) },
                    onShutdown = { confirmShutdown = true },
                )
                state.page == ServerAdminPage.ServerInfo -> ServerInfoPage(state)
                state.page == ServerAdminPage.Libraries -> LibrariesPage(state)
                state.page == ServerAdminPage.Tasks -> TasksPage(
                    state = state,
                    onCancelAll = { confirmCancelAll = true },
                )
                state.page == ServerAdminPage.Sessions -> SessionsPage(
                    state = state,
                    onLoadMore = { viewModel.loadSessions(reset = false) },
                    onScope = { scope -> viewModel.loadSessions(reset = true, scope = scope) },
                    onKick = viewModel::kickSession,
                )
                state.page == ServerAdminPage.Scheduled -> ScheduledPage(state)
                state.page == ServerAdminPage.History -> HistoryPage(
                    state = state,
                    onLoadMore = { viewModel.loadHistory(reset = false) },
                )
                state.page == ServerAdminPage.Files -> FilesPage(
                    state = state,
                    onOpen = viewModel::loadDirectory,
                )
                else -> LogPage(state)
            }
        }
    }

    if (confirmShutdown) {
        AlertDialog(
            onDismissRequest = { confirmShutdown = false },
            title = { Text(stringResource(R.string.server_admin_shutdown)) },
            text = { Text(stringResource(R.string.server_admin_shutdown_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmShutdown = false
                    viewModel.shutdown()
                }) { Text(stringResource(R.string.server_admin_shutdown)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmShutdown = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (confirmCancelAll) {
        AlertDialog(
            onDismissRequest = { confirmCancelAll = false },
            title = { Text(stringResource(R.string.server_admin_tasks_cancel_all)) },
            text = { Text(stringResource(R.string.server_admin_tasks_cancel_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancelAll = false
                    viewModel.cancelAllTasks()
                }) { Text(stringResource(R.string.server_admin_tasks_cancel_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private fun pageTitleRes(page: ServerAdminPage): Int = when (page) {
    ServerAdminPage.Info -> R.string.server_admin_title
    ServerAdminPage.ServerInfo -> R.string.server_admin_info
    ServerAdminPage.Libraries -> R.string.server_admin_libraries
    ServerAdminPage.LibrariesAdmin -> R.string.server_admin_libraries_manage
    ServerAdminPage.ApiKeys -> R.string.server_admin_api_keys
    ServerAdminPage.AuthActivity -> R.string.server_admin_auth_activity
    ServerAdminPage.Media -> R.string.server_admin_media
    ServerAdminPage.Tasks -> R.string.server_admin_tasks
    ServerAdminPage.Sessions -> R.string.server_admin_sessions
    ServerAdminPage.Scheduled -> R.string.server_admin_scheduled
    ServerAdminPage.History -> R.string.server_admin_history
    ServerAdminPage.Files -> R.string.server_admin_files
    ServerAdminPage.Log -> R.string.server_admin_log
}

// region info page

/**
 * Hub page of the server screen: one row into the server snapshot, then the management and maintenance
 * actions. The snapshot itself is a sub-page ([ServerInfoPage]) so this page reads as a short list of
 * options, the same shape as the entries below it.
 */
@Composable
private fun InfoPage(
    taskQueueCount: Long?,
    onOpen: (ServerAdminPage) -> Unit,
    onDownloadLog: () -> Unit,
    onShutdown: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionTitle(stringResource(R.string.server_admin_section_server)) }
        item {
            NavRow(stringResource(R.string.server_admin_info), Icons.Outlined.Dns) {
                onOpen(ServerAdminPage.ServerInfo)
            }
        }

        item { SectionTitle(stringResource(R.string.server_admin_section_manage)) }
        item {
            NavRow(stringResource(R.string.server_admin_libraries), Icons.Outlined.CollectionsBookmark) {
                onOpen(ServerAdminPage.Libraries)
            }
        }
        item {
            NavRow(stringResource(R.string.server_admin_libraries_manage), Icons.Outlined.Tune) {
                onOpen(ServerAdminPage.LibrariesAdmin)
            }
        }
        item {
            NavRow(stringResource(R.string.server_admin_api_keys), Icons.Outlined.Key) {
                onOpen(ServerAdminPage.ApiKeys)
            }
        }
        item {
            NavRow(stringResource(R.string.server_admin_auth_activity), Icons.Outlined.ManageAccounts) {
                onOpen(ServerAdminPage.AuthActivity)
            }
        }
        item {
            NavRow(stringResource(R.string.server_admin_media), Icons.Outlined.DocumentScanner) {
                onOpen(ServerAdminPage.Media)
            }
        }
        item {
            NavRow(
                label = stringResource(R.string.server_admin_tasks),
                icon = Icons.Outlined.Assignment,
                // The queue count is the same badge the iOS tile carries.
                badge = taskQueueCount?.takeIf { it > 0L }?.toString(),
            ) { onOpen(ServerAdminPage.Tasks) }
        }
        item {
            NavRow(stringResource(R.string.server_admin_sessions), Icons.Outlined.People) {
                onOpen(ServerAdminPage.Sessions)
            }
        }
        item {
            NavRow(stringResource(R.string.server_admin_scheduled), Icons.Outlined.EventRepeat) {
                onOpen(ServerAdminPage.Scheduled)
            }
        }
        item {
            NavRow(stringResource(R.string.server_admin_history), Icons.Outlined.History) {
                onOpen(ServerAdminPage.History)
            }
        }
        item {
            NavRow(stringResource(R.string.server_admin_files), Icons.Outlined.Folder) {
                onOpen(ServerAdminPage.Files)
            }
        }

        item { SectionTitle(stringResource(R.string.server_admin_section_maintenance)) }
        item {
            NavRow(stringResource(R.string.server_admin_log), Icons.Outlined.Description) {
                onDownloadLog()
            }
        }
        item {
            OutlinedButton(
                onClick = onShutdown,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.server_admin_shutdown), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Server snapshot sub-page (version / system / uptime, update check, resources and content totals)
 * reached from the single [ServerAdminPage.ServerInfo] row of the hub.
 */
@Composable
private fun ServerInfoPage(state: ServerAdminUiState) {
    val info = state.info
    if (state.infoLoading && info.version == null && info.libraries == null) {
        Loading()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionTitle(stringResource(R.string.server_admin_section_server)) }
        item {
            info.version?.let {
                InfoRow(Icons.Outlined.Sell, stringResource(R.string.server_admin_version), it)
            }
        }
        item {
            info.commit?.let {
                InfoRow(
                    icon = Icons.Outlined.AccountTree,
                    label = stringResource(R.string.server_admin_commit),
                    value = it,
                    monospaced = true,
                )
            }
        }
        item {
            info.osLine?.let {
                InfoRow(Icons.Outlined.Computer, stringResource(R.string.server_admin_os), it)
            }
        }
        item {
            info.healthStatus?.let {
                InfoRow(Icons.Outlined.MonitorHeart, stringResource(R.string.server_admin_health), it)
            }
        }
        item {
            info.startedAtMs?.let {
                // Absolute date + time, matching the iOS info row (the relative label stays on updates/history).
                InfoRow(
                    Icons.Outlined.Schedule,
                    stringResource(R.string.server_admin_started),
                    dateTimeLabel(it),
                )
            }
        }
        item {
            info.uptimeSeconds?.let {
                InfoRow(Icons.Outlined.Timer, stringResource(R.string.server_admin_uptime), formatDuration(it))
            }
        }

        item { SectionTitle(stringResource(R.string.server_admin_section_updates)) }
        item { UpdateCheckRow(state) }
        if (!state.updateSupported) {
            item { HintText(stringResource(R.string.server_admin_update_unsupported)) }
        }

        if (info.cpuPercent != null || info.memoryBytes != null || info.diskFree != null) {
            item { SectionTitle(stringResource(R.string.server_admin_section_resources)) }
            item {
                info.cpuPercent?.let {
                    InfoRow(Icons.Outlined.Speed, stringResource(R.string.server_admin_cpu), formatPercent(it))
                }
            }
            item {
                info.memoryBytes?.let {
                    InfoRow(Icons.Outlined.Memory, stringResource(R.string.server_admin_memory), formatBytes(it))
                }
            }
            item {
                val free = info.diskFree
                if (free != null) {
                    val value = info.diskTotal?.let { total ->
                        stringResource(R.string.server_admin_disk_value, formatBytes(free), formatBytes(total))
                    } ?: stringResource(R.string.server_admin_disk_free_value, formatBytes(free))
                    InfoRow(Icons.Outlined.Storage, stringResource(R.string.server_admin_disk), value)
                }
            }
        }

        if (info.libraries != null || info.series != null || info.books != null) {
            item { SectionTitle(stringResource(R.string.server_admin_section_content)) }
            item {
                info.libraries?.let {
                    InfoRow(
                        Icons.Outlined.CollectionsBookmark,
                        stringResource(R.string.server_admin_libraries),
                        it.toString(),
                    )
                }
            }
            item {
                info.series?.let {
                    InfoRow(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.server_admin_series), it.toString())
                }
            }
            item {
                info.books?.let {
                    InfoRow(Icons.Outlined.Book, stringResource(R.string.server_admin_books), it.toString())
                }
            }
            item {
                info.collections?.let {
                    InfoRow(Icons.Outlined.Collections, stringResource(R.string.server_admin_collections), it.toString())
                }
            }
            item {
                info.readlists?.let {
                    InfoRow(
                        Icons.Outlined.FormatListBulleted,
                        stringResource(R.string.server_admin_readlists),
                        it.toString(),
                    )
                }
            }
            item {
                info.sidecars?.let {
                    InfoRow(Icons.Outlined.Description, stringResource(R.string.server_admin_sidecars), it.toString())
                }
            }
            item {
                info.fileSize?.let {
                    InfoRow(Icons.Outlined.Archive, stringResource(R.string.server_admin_total_size), formatBytes(it))
                }
            }
        }
    }
}

@Composable
private fun UpdateCheckRow(state: ServerAdminUiState) {
    val urlHandler = LocalUriHandler.current
    val check = state.updateCheck
    if (check == null) {
        InfoRow(
            Icons.Outlined.ArrowCircleDown,
            stringResource(R.string.server_admin_update_checks),
            stringResource(R.string.server_admin_update_unknown),
        )
        return
    }
    val update = check.update
    when {
        update != null && update.version.isNotBlank() -> {
            // The iOS row opens the release page; keep the same link affordance here.
            val releaseUrl = update.url?.takeIf { it.isNotBlank() }
            InfoRow(
                icon = Icons.Outlined.ArrowCircleDown,
                label = stringResource(R.string.server_admin_update_available),
                value = update.version,
                onClick = releaseUrl?.let { url -> { urlHandler.openUri(url) } },
            )
        }
        !check.enabled -> InfoRow(
            Icons.Outlined.ArrowCircleDown,
            stringResource(R.string.server_admin_update_checks),
            stringResource(R.string.server_admin_update_disabled),
        )
        check.checkedAt.isNullOrBlank() -> InfoRow(
            Icons.Outlined.ArrowCircleDown,
            stringResource(R.string.server_admin_update_checks),
            stringResource(R.string.server_admin_update_not_checked),
        )
        else -> InfoRow(
            Icons.Outlined.ArrowCircleDown,
            stringResource(R.string.server_admin_version),
            stringResource(R.string.server_admin_update_current),
        )
    }
    parseIsoMs(check.checkedAt)?.let { checkedAt ->
        InfoRow(
            Icons.Outlined.CalendarMonth,
            stringResource(R.string.server_admin_update_last_checked),
            relativeTimeLabel(checkedAt),
        )
    }
}

// endregion

// region libraries page

@Composable
private fun LibrariesPage(state: ServerAdminUiState) {
    val stats = state.libraries
    when {
        state.librariesLoading && stats == null -> Loading()
        stats == null -> CenteredMessage(R.string.server_admin_stats_unavailable)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(stats.libraries.orEmpty()) { library -> LibraryStatsCard(library) }
            item {
                val total = stats.total
                if (total != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTitle(stringResource(R.string.server_admin_total))
                        totalTile(R.string.server_admin_series, total.series)
                        totalTile(R.string.server_admin_books, total.books)
                        totalTile(R.string.server_admin_total_size, total.fileSize, bytes = true)
                        total.sidecars?.let { totalTile(R.string.server_admin_sidecars, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryStatsCard(library: LibraryStatsDto) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = library.name.ifBlank { library.libraryId },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.server_admin_library_counts,
                library.series ?: 0L,
                library.books ?: 0L,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatBytes(library.fileSize ?: 0L),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun totalTile(labelRes: Int, value: Long?, bytes: Boolean = false) {
    if (value == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (bytes) formatBytes(value) else value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// endregion

// region tasks page

@Composable
private fun TasksPage(state: ServerAdminUiState, onCancelAll: () -> Unit) {
    val tasks = state.tasks
    if (tasks.loading && tasks.types.isEmpty() && tasks.queueSize == null) {
        Loading()
        return
    }
    val queuedTypes = tasks.types
        .filter { (it.queued ?: 0L) > 0L }
        .sortedWith(compareByDescending<TaskTypeStatsDto> { it.queued ?: 0L }.thenBy { it.type })
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionTitle(stringResource(R.string.server_admin_tasks_queue)) }
        item {
            // iOS leads the queue card with the total, the per-type counts below it.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Assignment,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.server_admin_tasks_total),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = (tasks.queueSize ?: 0L).toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if ((tasks.queueSize ?: 0L) > 0L) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (queuedTypes.isNotEmpty()) {
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            items(queuedTypes, key = { it.type }) { type ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = humanizeTaskType(type.type),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = (type.queued ?: 0L).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        item {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        item {
            TextButton(
                onClick = onCancelAll,
                enabled = tasks.queueSize != 0L && !tasks.busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Outlined.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.server_admin_tasks_cancel_all))
            }
        }
        item { SectionTitle(stringResource(R.string.server_admin_tasks_executed)) }
        if (tasks.types.isNotEmpty()) {
            items(tasks.types) { type -> TaskTypeRow(type) }
        } else {
            item { HintText(stringResource(R.string.server_admin_tasks_none)) }
        }
    }
}

@Composable
private fun TaskTypeRow(type: TaskTypeStatsDto) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(humanizeTaskType(type.type), style = MaterialTheme.typography.bodyLarge)
        val failures = type.failures ?: 0L
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    R.string.server_admin_task_runs_meta,
                    type.executions ?: 0L,
                    formatDuration((type.totalTimeMs ?: 0L) / 1000),
                    formatDuration((type.maxTimeMs ?: 0L) / 1000),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failures > 0L) {
                Text(
                    text = stringResource(R.string.server_admin_task_failed, failures),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// endregion

// region sessions page

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionsPage(
    state: ServerAdminUiState,
    onLoadMore: () -> Unit,
    onScope: (SessionScope) -> Unit,
    onKick: (SessionEntry) -> Unit,
) {
    val sessions = state.sessions
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = sessions.scope == SessionScope.Own,
                onClick = { onScope(SessionScope.Own) },
                label = { Text(stringResource(R.string.server_admin_sessions_own)) },
            )
            FilterChip(
                selected = sessions.scope == SessionScope.All,
                onClick = { onScope(SessionScope.All) },
                label = { Text(stringResource(R.string.server_admin_sessions_all)) },
            )
        }
        when {
            sessions.loading && sessions.entries.isEmpty() -> Loading()
            sessions.entries.isEmpty() && !sessions.supported ->
                CenteredMessage(R.string.server_admin_sessions_unavailable)
            sessions.entries.isEmpty() -> CenteredMessage(R.string.server_admin_sessions_empty)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(sessions.entries, key = { _, entry -> entry.id }) { index, entry ->
                    SessionRow(
                        entry = entry,
                        kicking = sessions.kickingId == entry.id,
                        enabled = sessions.kickingId == null && !entry.current,
                        onKick = { onKick(entry) },
                    )
                    if (index >= sessions.entries.lastIndex - 2 && sessions.hasMore && !sessions.loading) {
                        LaunchedEffect(entry.id) { onLoadMore() }
                    }
                }
                if (sessions.loading) item { LoadingRow() }
                item {
                    Text(
                        text = stringResource(R.string.server_admin_sessions_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(entry: SessionEntry, kicking: Boolean, enabled: Boolean, onKick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The session id leads the row (monospaced, middle-truncated in the iOS row).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.id,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    when {
                        entry.current -> R.string.server_admin_sessions_current
                        entry.expired -> R.string.server_admin_sessions_expired
                        else -> R.string.server_admin_sessions_active
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    entry.current -> MaterialTheme.colorScheme.onSurfaceVariant
                    entry.expired -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
        Text(
            text = entry.email?.takeIf { it.isNotBlank() }
                ?: entry.userId?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.server_admin_sessions_anonymous),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val created = parseIsoMs(entry.creationTime)
            val accessed = parseIsoMs(entry.lastAccessedTime)
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (created != null) {
                    Text(
                        text = stringResource(
                            R.string.server_admin_session_created,
                            relativeTimeLabel(created),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (created != null && accessed != null) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (accessed != null) {
                    Text(
                        text = stringResource(
                            R.string.server_admin_session_accessed,
                            relativeTimeLabel(accessed),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (kicking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else if (!entry.current) {
                TextButton(
                    onClick = onKick,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.server_admin_sessions_kick))
                }
            }
        }
    }
}

// endregion

// region scheduled tasks page

@Composable
private fun ScheduledPage(state: ServerAdminUiState) {
    when {
        state.scheduledLoading && state.scheduled.isEmpty() -> Loading()
        state.scheduled.isEmpty() -> CenteredMessage(R.string.server_admin_scheduled_empty)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.scheduled) { task -> ScheduledRow(task) }
        }
    }
}

@Composable
private fun ScheduledRow(task: ActuatorScheduledTaskDto) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = scheduledTargetLabel(task.runnable?.target.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatDuration((task.interval ?: 0L) / 1000),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Java FQN/lambda targets: drop the `$` lambda suffix and keep the meaningful tail. */
private fun scheduledTargetLabel(target: String): String {
    val cleaned = target.substringBefore("$")
    val parts = cleaned.split(".").filter { it.isNotBlank() }
    return if (parts.size >= 3) parts.takeLast(2).joinToString(".") else cleaned
}

// endregion

// region history page

@Composable
private fun HistoryPage(state: ServerAdminUiState, onLoadMore: () -> Unit) {
    val history = state.history
    when {
        history.loading && history.events.isEmpty() -> Loading()
        history.events.isEmpty() -> CenteredMessage(R.string.server_admin_history_empty)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(history.events, key = { _, event -> event.id }) { index, event ->
                HistoryRow(event)
                if (index >= history.events.lastIndex - 2 && history.hasMore && !history.loading) {
                    LaunchedEffect(event.id) { onLoadMore() }
                }
            }
            if (history.loading) item { LoadingRow() }
        }
    }
}

@Composable
private fun HistoryRow(event: HistoricalEventDto) {
    val primary = historyPrimaryName(event)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HistoryTypeBadge(event.type)
            Spacer(Modifier.weight(1f))
            parseIsoMs(event.timestamp)?.let { timestamp ->
                Text(
                    text = relativeTimeLabel(timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = primary ?: stringResource(R.string.server_admin_history_no_file),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        event.properties["SERIES"]?.takeIf { it.isNotBlank() && it != primary }?.let { series ->
            HistoryDetailLine(Icons.AutoMirrored.Outlined.MenuBook, series)
        }
        event.properties["BOOK"]?.takeIf { it.isNotBlank() && it != primary }?.let { book ->
            HistoryDetailLine(Icons.Outlined.Book, book)
        }
        historyExtraLine(event)?.let { extra ->
            Text(
                text = extra,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HistoryTypeBadge(type: String) {
    val color = historyEventColor(type)
    Text(
        text = humanizeEventType(type),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun HistoryDetailLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun historyEventColor(type: String): Color = when (type) {
    "BookFileDeleted", "BookPurged", "SeriesPurged" -> MaterialTheme.colorScheme.error
    "SeriesFolderDeleted" -> MaterialTheme.colorScheme.tertiary
    "BookConverted" -> MaterialTheme.colorScheme.primary
    "BookImported" -> Color(0xFF43A047)
    "DuplicatePageDeleted" -> MaterialTheme.colorScheme.tertiary
    "BookTrashed" -> Color(0xFFEF6C00)
    "SeriesTrashed" -> Color(0xFFF9A825)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The file/display name carried by the event; null when the payload has none. */
private fun historyPrimaryName(event: HistoricalEventDto): String? =
    event.properties["name"]?.takeIf { it.isNotBlank() }?.let(::baseName)
        ?: event.properties["BOOK"]?.takeIf { it.isNotBlank() }
        ?: event.properties["SERIES"]?.takeIf { it.isNotBlank() }

/** Secondary properties as iOS shows them: "key: value · key: value", hashes shortened. */
private fun historyExtraLine(event: HistoricalEventDto): String? {
    val parts = event.properties
        .filterKeys { it != "name" && it != "BOOK" && it != "SERIES" }
        .toSortedMap()
        .map { (key, value) ->
            if (key == "reason") {
                value
            } else {
                val display = when {
                    key.lowercase().contains("hash") && value.length > 12 -> value.take(12) + "…"
                    key == "former file" || key == "source" -> baseName(value)
                    else -> value
                }
                "$key: $display"
            }
        }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun baseName(path: String): String =
    path.split('/', '\\').lastOrNull { it.isNotBlank() } ?: path

// endregion

// region filesystem page

@Composable
private fun FilesPage(state: ServerAdminUiState, onOpen: (String?) -> Unit) {
    val files = state.files
    val directory = files.directory
    when {
        files.loading && directory == null -> Loading()
        files.error || directory == null -> CenteredMessage(R.string.server_admin_files_unavailable)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    text = directory.parent ?: stringResource(R.string.server_admin_files_root),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            directory.parent?.let { parent ->
                item { NavRow("..") { onOpen(parent) } }
            }
            items(directory.directories) { path ->
                NavRow(path.name) { onOpen(path.path) }
            }
            if (directory.directories.isEmpty()) {
                item { HintText(stringResource(R.string.server_admin_files_empty)) }
            }
        }
    }
}

// endregion

// region log page

@Composable
private fun LogPage(state: ServerAdminUiState) {
    val log = state.log
    when {
        state.logLoading && log == null -> Loading()
        log == null -> CenteredMessage(R.string.server_admin_log_unavailable)
        else -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Selection mirrors the iOS share sheet: the log can be copied out as text.
            SelectionContainer {
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// endregion

// region shared bits

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/** Icon + label + trailing value, the shape of the iOS server info rows. */
@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    monospaced: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = if (monospaced) {
                MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = if (onClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Navigation row with the optional icon and count badge the iOS server links carry. */
@Composable
private fun NavRow(
    label: String,
    icon: ImageVector? = null,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun CenteredMessage(messageRes: Int) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shared "load more" footer used by the paged server-admin lists. */
@Composable
internal fun LoadMoreRow(onLoadMore: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onLoadMore) {
            Text(stringResource(R.string.server_admin_load_more))
        }
    }
}

// endregion

// region formatting

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) {
        "${bytes} ${units[index]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[index])
    }
}

internal fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

/** Absolute local date + time, matching the iOS `.abbreviated` / `.shortened` row values. */
internal fun dateTimeLabel(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** Whole-unit duration ("3d 4h"), used for uptimes and task timings. */
internal fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    var remaining = seconds
    val days = remaining / 86_400
    remaining %= 86_400
    val hours = remaining / 3_600
    remaining %= 3_600
    val minutes = remaining / 60
    val secs = remaining % 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (days > 0 || hours > 0) append("${hours}h ")
        if (days > 0 || hours > 0 || minutes > 0) append("${minutes}m ")
        if (days == 0L && hours == 0L) append("${secs}s")
    }.trim()
}

/** `ScanLibraryTask` → `Scan Library Task` (same split as iOS ServerTasksView). */
internal fun humanizeTaskType(type: String): String {
    if (type.isBlank()) return type
    val spaced = type.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ')
    return spaced.replaceFirstChar { it.uppercaseChar() }
}

/** `BOOK_ADDED` → `Book Added`. */
internal fun humanizeEventType(type: String): String {
    if (type.isBlank()) return type
    return type.lowercase().split('_', ' ', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
}

// endregion
