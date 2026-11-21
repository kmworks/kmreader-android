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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
        item { NavRow(stringResource(R.string.server_admin_info)) { onOpen(ServerAdminPage.ServerInfo) } }

        item { SectionTitle(stringResource(R.string.server_admin_section_manage)) }
        item { NavRow(stringResource(R.string.server_admin_libraries)) { onOpen(ServerAdminPage.Libraries) } }
        item { NavRow(stringResource(R.string.server_admin_libraries_manage)) { onOpen(ServerAdminPage.LibrariesAdmin) } }
        item { NavRow(stringResource(R.string.server_admin_api_keys)) { onOpen(ServerAdminPage.ApiKeys) } }
        item { NavRow(stringResource(R.string.server_admin_auth_activity)) { onOpen(ServerAdminPage.AuthActivity) } }
        item { NavRow(stringResource(R.string.server_admin_media)) { onOpen(ServerAdminPage.Media) } }
        item { NavRow(stringResource(R.string.server_admin_tasks)) { onOpen(ServerAdminPage.Tasks) } }
        item { NavRow(stringResource(R.string.server_admin_sessions)) { onOpen(ServerAdminPage.Sessions) } }
        item { NavRow(stringResource(R.string.server_admin_scheduled)) { onOpen(ServerAdminPage.Scheduled) } }
        item { NavRow(stringResource(R.string.server_admin_history)) { onOpen(ServerAdminPage.History) } }
        item { NavRow(stringResource(R.string.server_admin_files)) { onOpen(ServerAdminPage.Files) } }

        item { SectionTitle(stringResource(R.string.server_admin_section_maintenance)) }
        item { NavRow(stringResource(R.string.server_admin_log)) { onDownloadLog() } }
        item {
            OutlinedButton(
                onClick = onShutdown,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text(stringResource(R.string.server_admin_shutdown), color = MaterialTheme.colorScheme.error) }
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
            info.version?.let { ValueRow(stringResource(R.string.server_admin_version), it) }
        }
        item {
            info.commit?.let { ValueRow(stringResource(R.string.server_admin_commit), it) }
        }
        item {
            info.osLine?.let { ValueRow(stringResource(R.string.server_admin_os), it) }
        }
        item {
            info.healthStatus?.let { ValueRow(stringResource(R.string.server_admin_health), it) }
        }
        item {
            info.startedAtMs?.let {
                ValueRow(
                    stringResource(R.string.server_admin_started),
                    stringResource(R.string.server_admin_rel_time, relativeTimeLabel(it)),
                )
            }
        }
        item {
            info.uptimeSeconds?.let {
                ValueRow(stringResource(R.string.server_admin_uptime), formatDuration(it))
            }
        }

        item { SectionTitle(stringResource(R.string.server_admin_section_updates)) }
        item { UpdateCheckRow(state) }
        if (!state.updateSupported) {
            item { HintText(stringResource(R.string.server_admin_update_unsupported)) }
        }

        if (info.cpuPercent != null || info.memoryBytes != null || info.diskTotal != null) {
            item { SectionTitle(stringResource(R.string.server_admin_section_resources)) }
            item {
                info.cpuPercent?.let {
                    ValueRow(stringResource(R.string.server_admin_cpu), formatPercent(it))
                }
            }
            item {
                info.memoryBytes?.let {
                    ValueRow(stringResource(R.string.server_admin_memory), formatBytes(it))
                }
            }
            item {
                if (info.diskTotal != null && info.diskFree != null) {
                    ValueRow(
                        stringResource(R.string.server_admin_disk),
                        stringResource(
                            R.string.server_admin_disk_value,
                            formatBytes(info.diskFree),
                            formatBytes(info.diskTotal),
                        ),
                    )
                }
            }
        }

        if (info.libraries != null || info.series != null || info.books != null) {
            item { SectionTitle(stringResource(R.string.server_admin_section_content)) }
            item {
                info.libraries?.let {
                    ValueRow(stringResource(R.string.server_admin_libraries), it.toString())
                }
            }
            item {
                info.series?.let {
                    ValueRow(stringResource(R.string.server_admin_series), it.toString())
                }
            }
            item {
                info.books?.let {
                    ValueRow(stringResource(R.string.server_admin_books), it.toString())
                }
            }
            item {
                info.collections?.let {
                    ValueRow(stringResource(R.string.server_admin_collections), it.toString())
                }
            }
            item {
                info.readlists?.let {
                    ValueRow(stringResource(R.string.server_admin_readlists), it.toString())
                }
            }
            item {
                info.sidecars?.let {
                    ValueRow(stringResource(R.string.server_admin_sidecars), it.toString())
                }
            }
            item {
                info.fileSize?.let {
                    ValueRow(stringResource(R.string.server_admin_total_size), formatBytes(it))
                }
            }
        }
    }
}

@Composable
private fun UpdateCheckRow(state: ServerAdminUiState) {
    val check = state.updateCheck
    if (check == null) {
        ValueRow(
            stringResource(R.string.server_admin_update_checks),
            stringResource(R.string.server_admin_update_unknown),
        )
        return
    }
    val update = check.update
    when {
        update != null && update.version.isNotBlank() ->
            ValueRow(stringResource(R.string.server_admin_update_available), update.version)
        !check.enabled ->
            ValueRow(
                stringResource(R.string.server_admin_update_checks),
                stringResource(R.string.server_admin_update_disabled),
            )
        check.checkedAt.isNullOrBlank() ->
            ValueRow(
                stringResource(R.string.server_admin_update_checks),
                stringResource(R.string.server_admin_update_not_checked),
            )
        else ->
            ValueRow(
                stringResource(R.string.server_admin_version),
                stringResource(R.string.server_admin_update_current),
            )
    }
    parseIsoMs(check.checkedAt)?.let { checkedAt ->
        ValueRow(
            stringResource(R.string.server_admin_update_last_checked),
            stringResource(R.string.server_admin_rel_time, relativeTimeLabel(checkedAt)),
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionTitle(stringResource(R.string.server_admin_tasks_queue)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.server_admin_tasks_queued, tasks.queueSize ?: 0L),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = onCancelAll, enabled = tasks.queueSize != 0L && !tasks.busy) {
                    Text(stringResource(R.string.server_admin_tasks_cancel_all))
                }
            }
        }
        if (tasks.types.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.server_admin_tasks_executed)) }
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
                    if (index == sessions.entries.lastIndex && sessions.hasMore && !sessions.loading) {
                        LaunchedEffect(entry.id) { onLoadMore() }
                    }
                }
                if (sessions.loading) item { LoadingRow() }
            }
        }
    }
}

@Composable
private fun SessionRow(entry: SessionEntry, kicking: Boolean, enabled: Boolean, onKick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.server_admin_sessions_anonymous),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            parseIsoMs(entry.creationTime)?.let { created ->
                Text(
                    text = stringResource(
                        R.string.server_admin_session_created,
                        stringResource(R.string.server_admin_rel_time, relativeTimeLabel(created)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            parseIsoMs(entry.lastAccessedTime)?.let { accessed ->
                Text(
                    text = stringResource(
                        R.string.server_admin_session_accessed,
                        stringResource(R.string.server_admin_rel_time, relativeTimeLabel(accessed)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entry.current) {
            Text(
                text = stringResource(R.string.server_admin_sessions_current),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (kicking) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            TextButton(onClick = onKick, enabled = enabled) {
                Text(stringResource(R.string.server_admin_sessions_kick))
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
                if (index == history.events.lastIndex && history.hasMore && !history.loading) {
                    LaunchedEffect(event.id) { onLoadMore() }
                }
            }
            if (history.loading) item { LoadingRow() }
        }
    }
}

@Composable
private fun HistoryRow(event: HistoricalEventDto) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(humanizeEventType(event.type), style = MaterialTheme.typography.bodyLarge)
            parseIsoMs(event.timestamp)?.let { timestamp ->
                Text(
                    text = relativeTimeLabel(timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val detail = event.properties["BOOK"] ?: event.properties["SERIES"]
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

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
            Text(
                text = log,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
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

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
private fun LoadingRow() {
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

internal fun formatPercent(value: Double): String = String.format(Locale.US, "%.0f%%", value)

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
