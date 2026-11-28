package com.kmworks.kmreader.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.ArrowUpward
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import com.kmworks.kmreader.core.server.SessionEntry
import com.kmworks.kmreader.core.server.SessionScope
import com.kmworks.kmreader.core.server.TaskTypeStatsDto
import com.kmworks.kmreader.ui.components.relativeTimeLabel
import com.kmworks.kmreader.ui.settings.settingsBackIcon
import com.kmworks.kmreader.ui.settings.SettingsDangerRow
import com.kmworks.kmreader.ui.settings.SettingsMenuRow
import com.kmworks.kmreader.ui.settings.SettingsRowDivider
import com.kmworks.kmreader.ui.settings.SettingsSection
import com.kmworks.kmreader.ui.settings.SettingsValueRow
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
    var confirmShutdown by remember { mutableStateOf(false) }
    var confirmCancelAll by remember { mutableStateOf(false) }

    LaunchedEffect(initialPage) { viewModel.open(initialPage) }

    // stringResource is composable, so the message is resolved during composition; the effect
    // only delivers it (keyed on the same res id, so the delivery timing is unchanged).
    val messageRes = state.messageRes
    val message = messageRes?.let { stringResource(it) }
    LaunchedEffect(messageRes) {
        message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
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
                navigationIcon = settingsBackIcon(paneRoot = state.page == ServerAdminPage.Info) {
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SettingsSection(title = stringResource(R.string.server_admin_section_server)) {
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_info),
                    icon = Icons.Outlined.Dns,
                    onClick = { onOpen(ServerAdminPage.ServerInfo) },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.server_admin_section_manage)) {
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_libraries),
                    icon = Icons.Outlined.CollectionsBookmark,
                    onClick = { onOpen(ServerAdminPage.Libraries) },
                )
                SettingsRowDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_libraries_manage),
                    icon = Icons.Outlined.Tune,
                    onClick = { onOpen(ServerAdminPage.LibrariesAdmin) },
                )
                SettingsRowDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_api_keys),
                    icon = Icons.Outlined.Key,
                    onClick = { onOpen(ServerAdminPage.ApiKeys) },
                )
                SettingsRowDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_auth_activity),
                    icon = Icons.Outlined.ManageAccounts,
                    onClick = { onOpen(ServerAdminPage.AuthActivity) },
                )
                SettingsRowDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_media),
                    icon = Icons.Outlined.DocumentScanner,
                    onClick = { onOpen(ServerAdminPage.Media) },
                )
                SettingsRowDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_tasks),
                    icon = Icons.Outlined.Assignment,
                    // The queue count is the same badge the iOS tile carries.
                    badge = taskQueueCount?.takeIf { it > 0L }?.toString(),
                    onClick = { onOpen(ServerAdminPage.Tasks) },
                )
                SettingsRowDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_sessions),
                    icon = Icons.Outlined.People,
                    onClick = { onOpen(ServerAdminPage.Sessions) },
                )
                SettingsRowDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_scheduled),
                    icon = Icons.Outlined.EventRepeat,
                    onClick = { onOpen(ServerAdminPage.Scheduled) },
                )
                SettingsRowDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_history),
                    icon = Icons.Outlined.History,
                    onClick = { onOpen(ServerAdminPage.History) },
                )
                SettingsRowDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_files),
                    icon = Icons.Outlined.Folder,
                    onClick = { onOpen(ServerAdminPage.Files) },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.server_admin_section_maintenance)) {
                SettingsMenuRow(
                    title = stringResource(R.string.server_admin_log),
                    icon = Icons.Outlined.Description,
                    onClick = onDownloadLog,
                )
                SettingsRowDivider()
                SettingsDangerRow(
                    text = stringResource(R.string.server_admin_shutdown),
                    icon = Icons.Outlined.PowerSettingsNew,
                    onClick = onShutdown,
                )
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
    val serverRows = buildList<@Composable () -> Unit> {
        info.version?.let { value ->
            add { InfoRow(Icons.Outlined.Sell, stringResource(R.string.server_admin_version), value) }
        }
        info.commit?.let { value ->
            add {
                InfoRow(
                    icon = Icons.Outlined.AccountTree,
                    label = stringResource(R.string.server_admin_commit),
                    value = value,
                    monospaced = true,
                )
            }
        }
        info.osLine?.let { value ->
            add { InfoRow(Icons.Outlined.Computer, stringResource(R.string.server_admin_os), value) }
        }
        info.healthStatus?.let { value ->
            add { InfoRow(Icons.Outlined.MonitorHeart, stringResource(R.string.server_admin_health), value) }
        }
        info.startedAtMs?.let { value ->
            add {
                // Absolute date + time, matching the iOS info row (the relative label stays on updates/history).
                InfoRow(Icons.Outlined.Schedule, stringResource(R.string.server_admin_started), dateTimeLabel(value))
            }
        }
        info.uptimeSeconds?.let { value ->
            add { InfoRow(Icons.Outlined.Timer, stringResource(R.string.server_admin_uptime), formatDuration(value)) }
        }
    }
    val resourceRows = buildList<@Composable () -> Unit> {
        info.cpuPercent?.let { value ->
            add { InfoRow(Icons.Outlined.Speed, stringResource(R.string.server_admin_cpu), formatPercent(value)) }
        }
        info.memoryBytes?.let { value ->
            add { InfoRow(Icons.Outlined.Memory, stringResource(R.string.server_admin_memory), formatBytes(value)) }
        }
        info.diskFree?.let { free ->
            add {
                val value = info.diskTotal?.let { total ->
                    stringResource(R.string.server_admin_disk_value, formatBytes(free), formatBytes(total))
                } ?: stringResource(R.string.server_admin_disk_free_value, formatBytes(free))
                InfoRow(Icons.Outlined.Storage, stringResource(R.string.server_admin_disk), value)
            }
        }
    }
    val contentRows = buildList<@Composable () -> Unit> {
        info.libraries?.let { value ->
            add {
                InfoRow(
                    Icons.Outlined.CollectionsBookmark,
                    stringResource(R.string.server_admin_libraries),
                    value.toString(),
                )
            }
        }
        info.series?.let { value ->
            add {
                InfoRow(
                    Icons.AutoMirrored.Outlined.MenuBook,
                    stringResource(R.string.server_admin_series),
                    value.toString(),
                )
            }
        }
        info.books?.let { value ->
            add { InfoRow(Icons.Outlined.Book, stringResource(R.string.server_admin_books), value.toString()) }
        }
        info.collections?.let { value ->
            add {
                InfoRow(Icons.Outlined.Collections, stringResource(R.string.server_admin_collections), value.toString())
            }
        }
        info.readlists?.let { value ->
            add {
                InfoRow(
                    Icons.Outlined.FormatListBulleted,
                    stringResource(R.string.server_admin_readlists),
                    value.toString(),
                )
            }
        }
        info.sidecars?.let { value ->
            add { InfoRow(Icons.Outlined.Description, stringResource(R.string.server_admin_sidecars), value.toString()) }
        }
        info.fileSize?.let { value ->
            add {
                InfoRow(Icons.Outlined.Archive, stringResource(R.string.server_admin_total_size), formatBytes(value))
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SettingsSection(title = stringResource(R.string.server_admin_section_server)) {
                serverRows.forEachIndexed { index, row ->
                    if (index > 0) SettingsRowDivider()
                    row()
                }
            }
        }
        item {
            SettingsSection(
                title = stringResource(R.string.server_admin_section_updates),
                footer = if (!state.updateSupported) {
                    stringResource(R.string.server_admin_update_unsupported)
                } else {
                    null
                },
            ) {
                UpdateCheckRow(state)
            }
        }
        if (resourceRows.isNotEmpty()) {
            item {
                SettingsSection(title = stringResource(R.string.server_admin_section_resources)) {
                    resourceRows.forEachIndexed { index, row ->
                        if (index > 0) SettingsRowDivider()
                        row()
                    }
                }
            }
        }
        if (contentRows.isNotEmpty()) {
            item {
                SettingsSection(title = stringResource(R.string.server_admin_section_content)) {
                    contentRows.forEachIndexed { index, row ->
                        if (index > 0) SettingsRowDivider()
                        row()
                    }
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
        SettingsRowDivider()
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(stats.libraries.orEmpty()) { library ->
                SettingsSection(title = library.name.ifBlank { library.libraryId }) {
                    SettingsValueRow(
                        title = stringResource(R.string.server_admin_series),
                        value = (library.series ?: 0L).toString(),
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                    )
                    SettingsRowDivider()
                    SettingsValueRow(
                        title = stringResource(R.string.server_admin_books),
                        value = (library.books ?: 0L).toString(),
                        icon = Icons.Outlined.Book,
                    )
                    SettingsRowDivider()
                    SettingsValueRow(
                        title = stringResource(R.string.server_admin_total_size),
                        value = formatBytes(library.fileSize ?: 0L),
                        icon = Icons.Outlined.Storage,
                    )
                }
            }
            item {
                val total = stats.total
                if (total != null) {
                    SettingsSection(title = stringResource(R.string.server_admin_total)) {
                        SettingsValueRow(
                            title = stringResource(R.string.server_admin_series),
                            value = (total.series ?: 0L).toString(),
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                        )
                        SettingsRowDivider()
                        SettingsValueRow(
                            title = stringResource(R.string.server_admin_books),
                            value = (total.books ?: 0L).toString(),
                            icon = Icons.Outlined.Book,
                        )
                        SettingsRowDivider()
                        SettingsValueRow(
                            title = stringResource(R.string.server_admin_total_size),
                            value = formatBytes(total.fileSize ?: 0L),
                            icon = Icons.Outlined.Storage,
                        )
                        total.sidecars?.let { sidecars ->
                            SettingsRowDivider()
                            SettingsValueRow(
                                title = stringResource(R.string.server_admin_sidecars),
                                value = sidecars.toString(),
                                icon = Icons.Outlined.Description,
                            )
                        }
                    }
                }
            }
        }
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SettingsSection(title = stringResource(R.string.server_admin_tasks_queue)) {
                // iOS leads the queue card with the total, the per-type counts below it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
                queuedTypes.forEach { type ->
                    SettingsRowDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                SettingsRowDivider()
                SettingsDangerRow(
                    text = stringResource(R.string.server_admin_tasks_cancel_all),
                    icon = Icons.Outlined.Cancel,
                    enabled = tasks.queueSize != 0L && !tasks.busy,
                    onClick = onCancelAll,
                )
            }
        }
        item {
            SettingsSection(
                title = stringResource(R.string.server_admin_tasks_executed),
                footer = if (tasks.types.isEmpty()) {
                    stringResource(R.string.server_admin_tasks_none)
                } else {
                    null
                },
            ) {
                tasks.types.forEachIndexed { index, type ->
                    if (index > 0) SettingsRowDivider()
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        TaskTypeRow(type)
                    }
                }
            }
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
            else -> Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                ),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(sessions.entries, key = { _, entry -> entry.id }) { index, entry ->
                        SessionRow(
                            entry = entry,
                            kicking = sessions.kickingId == entry.id,
                            enabled = sessions.kickingId == null && !entry.current,
                            onKick = { onKick(entry) },
                        )
                        if (index < sessions.entries.lastIndex) {
                            SettingsRowDivider()
                        }
                        if (index >= sessions.entries.lastIndex - 2 && sessions.hasMore && !sessions.loading) {
                            LaunchedEffect(entry.id) { onLoadMore() }
                        }
                    }
                    if (sessions.loading) item { LoadingRow() }
                    item {
                        SettingsRowDivider()
                        Text(
                            text = stringResource(R.string.server_admin_sessions_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(entry: SessionEntry, kicking: Boolean, enabled: Boolean, onKick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsSection {
                    state.scheduled.forEachIndexed { index, task ->
                        if (index > 0) SettingsRowDivider()
                        SettingsValueRow(
                            title = scheduledTargetLabel(task.runnable?.target.orEmpty()),
                            value = formatDuration((task.interval ?: 0L) / 1000),
                            icon = Icons.Outlined.Schedule,
                        )
                    }
                }
            }
        }
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
        else -> Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            ),
        ) {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(history.events, key = { _, event -> event.id }) { index, event ->
                    HistoryRow(event)
                    if (index < history.events.lastIndex) {
                        SettingsRowDivider()
                    }
                    if (index >= history.events.lastIndex - 2 && history.hasMore && !history.loading) {
                        LaunchedEffect(event.id) { onLoadMore() }
                    }
                }
                if (history.loading) item { LoadingRow() }
            }
        }
    }
}

@Composable
private fun HistoryRow(event: HistoricalEventDto) {
    val primary = historyPrimaryName(event)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
        else -> Column(Modifier.fillMaxSize()) {
            Text(
                text = directory.parent ?: stringResource(R.string.server_admin_files_root),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                ),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    directory.parent?.let { parent ->
                        item {
                            SettingsMenuRow(
                                title = "..",
                                icon = Icons.Outlined.ArrowUpward,
                                onClick = { onOpen(parent) },
                            )
                        }
                        item { SettingsRowDivider() }
                    }
                    itemsIndexed(directory.directories) { index, path ->
                        if (index > 0) SettingsRowDivider()
                        SettingsMenuRow(
                            title = path.name,
                            icon = Icons.Outlined.Folder,
                            onClick = { onOpen(path.path) },
                        )
                    }
                    if (directory.directories.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.server_admin_files_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                        }
                    }
                }
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
        else -> Box(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                ),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
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
    }
}

// endregion

// region shared bits

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
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
