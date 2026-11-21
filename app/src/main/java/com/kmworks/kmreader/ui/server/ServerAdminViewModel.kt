package com.kmworks.kmreader.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.DirectoryListingDto
import com.kmworks.kmreader.core.api.model.HistoricalEventDto
import com.kmworks.kmreader.core.server.ActuatorMetricDto
import com.kmworks.kmreader.core.server.ActuatorScheduledTaskDto
import com.kmworks.kmreader.core.server.ServerAdminRepository
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerInfoDto
import com.kmworks.kmreader.core.server.ServerTaskStatsDto
import com.kmworks.kmreader.core.server.SessionEntry
import com.kmworks.kmreader.core.server.SessionScope
import com.kmworks.kmreader.core.server.StatsLibrariesDto
import com.kmworks.kmreader.core.server.TaskTypeStatsDto
import com.kmworks.kmreader.core.server.UpdateCheckDto
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Sub-pages of the server management screen (mirrors the iOS NavigationLinks from ServerView). */
enum class ServerAdminPage {
    Info,
    ServerInfo,
    Libraries,
    LibrariesAdmin,
    ApiKeys,
    AuthActivity,
    Media,
    Tasks,
    Sessions,
    Scheduled,
    History,
    Files,
    Log,
}

/** Server snapshot for the info page: kmrs stats/server first, actuator metrics as the fallback. */
data class ServerInfoSnapshot(
    val version: String? = null,
    val commit: String? = null,
    val osLine: String? = null,
    val startedAtMs: Long? = null,
    val uptimeSeconds: Long? = null,
    val cpuPercent: Double? = null,
    val memoryBytes: Long? = null,
    val diskFree: Long? = null,
    val diskTotal: Long? = null,
    val libraries: Long? = null,
    val series: Long? = null,
    val books: Long? = null,
    val collections: Long? = null,
    val readlists: Long? = null,
    val sidecars: Long? = null,
    val fileSize: Long? = null,
    val healthStatus: String? = null,
    /** `stats/server` answered: the kmrs-private totals are authoritative. */
    val hasStats: Boolean = false,
)

data class TasksSnapshot(
    val queueSize: Long? = null,
    val types: List<TaskTypeStatsDto> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
)

data class SessionsSnapshot(
    val scope: SessionScope = SessionScope.Own,
    val entries: List<SessionEntry> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val supported: Boolean = true,
    /** Id currently being kicked (the row shows a spinner and the others disable). */
    val kickingId: String? = null,
)

data class HistorySnapshot(
    val events: List<HistoricalEventDto> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val page: Int = 0,
)

data class FilesSnapshot(
    val directory: DirectoryListingDto? = null,
    val loading: Boolean = false,
    val error: Boolean = false,
)

data class ServerAdminUiState(
    val page: ServerAdminPage = ServerAdminPage.Info,
    val isAdmin: Boolean = true,
    val info: ServerInfoSnapshot = ServerInfoSnapshot(),
    val infoLoading: Boolean = false,
    val updateCheck: UpdateCheckDto? = null,
    val updateSupported: Boolean = false,
    val libraries: StatsLibrariesDto? = null,
    val librariesLoading: Boolean = false,
    val tasks: TasksSnapshot = TasksSnapshot(),
    val sessions: SessionsSnapshot = SessionsSnapshot(),
    val scheduled: List<ActuatorScheduledTaskDto> = emptyList(),
    val scheduledLoading: Boolean = false,
    val history: HistorySnapshot = HistorySnapshot(),
    val files: FilesSnapshot = FilesSnapshot(),
    val log: String? = null,
    val logLoading: Boolean = false,
    /** Transient one-shot notice (R.string id) consumed by the screen's snackbar. */
    val messageRes: Int? = null,
)

/**
 * Server management screen state (mirrors iOS ServerInfoView + ServerTasksView + ServerSessionsView +
 * ServerScheduledTasksView + HistoryView + filesystem browser).
 *
 * Everything is admin-gated like iOS: a non-admin account only sees the "admin required" state.
 * kmrs stats answer first; stock Komga falls back to the actuator metrics family.
 */
@HiltViewModel
class ServerAdminViewModel @Inject constructor(
    private val repository: ServerAdminRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerAdminUiState())
    val state: StateFlow<ServerAdminUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val isAdmin = repository.isAdmin()
            _state.update {
                it.copy(
                    isAdmin = isAdmin,
                    updateSupported = repository.hasFeature(ServerFeature.UpdateCheck),
                )
            }
            if (isAdmin) loadInfo()
        }
    }

    fun open(page: ServerAdminPage) {
        _state.update { it.copy(page = page) }
        if (!_state.value.isAdmin) return
        viewModelScope.launch {
            when (page) {
                ServerAdminPage.Info, ServerAdminPage.ServerInfo -> loadInfo()
                ServerAdminPage.Libraries -> loadLibraries()
                ServerAdminPage.Tasks -> loadTasks()
                ServerAdminPage.Sessions -> loadSessions(reset = true)
                ServerAdminPage.Scheduled -> loadScheduled()
                ServerAdminPage.History -> loadHistory(reset = true)
                // The browser always restarts at the server root.
                ServerAdminPage.Files -> loadDirectory(null)
                ServerAdminPage.Log -> loadLog()
                // Handled by their own screens with dedicated view models.
                ServerAdminPage.LibrariesAdmin,
                ServerAdminPage.ApiKeys,
                ServerAdminPage.AuthActivity,
                ServerAdminPage.Media,
                -> Unit
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(messageRes = null) }

    // region info

    private suspend fun loadInfo() {
        _state.update { it.copy(infoLoading = true, messageRes = null) }
        val nativeInfo: ServerInfoDto? = repository.serverInfo()
        val actuatorInfo = repository.actuatorInfo()
        val stats = repository.serverStats()
        val health = repository.actuatorHealth()
        val updateCheck = repository.updateCheck()

        val process = stats?.process
        val diskSpace = health?.components?.diskSpace?.details
        var snapshot = ServerInfoSnapshot(
            version = nativeInfo?.version?.takeIf { it.isNotBlank() }
                ?: actuatorInfo?.build?.version?.takeIf { it.isNotBlank() },
            commit = actuatorInfo?.git?.commit?.id?.take(7)?.takeIf { it.isNotBlank() },
            osLine = listOfNotNull(
                actuatorInfo?.os?.name?.takeIf { it.isNotBlank() },
                actuatorInfo?.os?.version?.takeIf { it.isNotBlank() },
                actuatorInfo?.os?.arch?.takeIf { it.isNotBlank() },
            ).joinToString(" ").takeIf { it.isNotBlank() },
            startedAtMs = parseIsoMs(process?.startTime),
            uptimeSeconds = process?.uptimeSeconds,
            cpuPercent = process?.cpuUsage,
            memoryBytes = process?.memoryBytes,
            diskFree = stats?.disk?.free ?: diskSpace?.get("free")?.jsonPrimitive?.longOrNull,
            diskTotal = stats?.disk?.total ?: diskSpace?.get("total")?.jsonPrimitive?.longOrNull,
            libraries = stats?.totals?.libraries,
            series = stats?.totals?.series,
            books = stats?.totals?.books,
            collections = stats?.totals?.collections,
            readlists = stats?.totals?.readlists,
            sidecars = stats?.totals?.sidecars,
            fileSize = stats?.totals?.fileSize,
            healthStatus = health?.status,
            hasStats = stats != null,
        )

        // Stock Komga: the same numbers come from the standard actuator metric family.
        if (!snapshot.hasStats) {
            snapshot = snapshot.copy(
                cpuPercent = snapshot.cpuPercent ?: cpuPercent(),
                memoryBytes = snapshot.memoryBytes ?: metricValue("jvm.memory.used")?.toLong(),
                uptimeSeconds = snapshot.uptimeSeconds ?: metricValue("process.uptime")?.toLong(),
                startedAtMs = snapshot.startedAtMs
                    ?: metricValue("process.start.time")?.let { (it * 1000).toLong() },
                libraries = snapshot.libraries ?: metricValue("komga.libraries")?.toLong(),
                series = snapshot.series ?: metricValue("komga.series")?.toLong(),
                books = snapshot.books ?: metricValue("komga.books")?.toLong(),
                collections = snapshot.collections ?: metricValue("komga.collections")?.toLong(),
                readlists = snapshot.readlists ?: metricValue("komga.readlists")?.toLong(),
                sidecars = snapshot.sidecars ?: metricValue("komga.sidecars")?.toLong(),
                fileSize = snapshot.fileSize ?: metricValue("komga.books.filesize")?.toLong(),
            )
        }

        // The queue size has no metric counterpart; it arrives over SSE instead.
        val queueSize = stats?.tasks?.queueSize
        _state.update {
            it.copy(
                infoLoading = false,
                info = snapshot,
                updateCheck = updateCheck,
                tasks = it.tasks.copy(queueSize = queueSize ?: it.tasks.queueSize),
            )
        }
    }

    private suspend fun cpuPercent(): Double? = metricValue("process.cpu.usage")?.let { value ->
        // The actuator reports percent directly, but JVM metrics may hand back a 0..1 ratio.
        if (value in 0.0..1.0) value * 100 else value
    }

    // endregion

    // region libraries

    private suspend fun loadLibraries() {
        _state.update { it.copy(librariesLoading = true, messageRes = null) }
        val libraries = repository.librariesStats()
        _state.update {
            it.copy(
                librariesLoading = false,
                libraries = libraries,
                messageRes = if (libraries == null) R.string.server_admin_stats_unavailable else null,
            )
        }
    }

    // endregion

    // region tasks

    private suspend fun loadTasks() {
        _state.update { it.copy(tasks = it.tasks.copy(loading = true), messageRes = null) }
        val stats: ServerTaskStatsDto? = repository.serverStats()?.tasks
        // Stock Komga: the same numbers come from the komga.tasks.* actuator meters, per type.
        val types = stats?.types.orEmpty()
            .ifEmpty { repository.actuatorTaskStats() }
            .sortedByDescending { it.executions ?: 0L }
        _state.update {
            it.copy(
                tasks = TasksSnapshot(
                    queueSize = stats?.queueSize ?: it.tasks.queueSize,
                    types = types,
                    loading = false,
                ),
            )
        }
    }

    fun cancelAllTasks() {
        if (_state.value.tasks.busy) return
        _state.update { it.copy(tasks = it.tasks.copy(busy = true)) }
        viewModelScope.launch {
            val ok = repository.emptyTaskQueue()
            _state.update {
                it.copy(
                    tasks = it.tasks.copy(busy = false, queueSize = if (ok) 0L else it.tasks.queueSize),
                    messageRes = if (ok) R.string.server_admin_tasks_cleared else R.string.server_admin_action_failed,
                )
            }
            if (ok) loadTasks()
        }
    }

    // endregion

    // region sessions

    fun loadSessions(reset: Boolean = false, scope: SessionScope = _state.value.sessions.scope) {
        if (_state.value.sessions.loading) return
        viewModelScope.launch { loadSessionsInternal(reset, scope) }
    }

    private suspend fun loadSessionsInternal(reset: Boolean, scope: SessionScope) {
        val previous = _state.value.sessions
        val page = if (reset || previous.scope != scope) 0 else previous.entries.size / SESSION_PAGE_SIZE
        _state.update { it.copy(sessions = previous.copy(scope = scope, loading = true)) }
        val result = repository.sessions(scope, page = page, size = SESSION_PAGE_SIZE)
        val entries = if (reset || previous.scope != scope) result.entries else previous.entries + result.entries
        _state.update {
            it.copy(
                sessions = SessionsSnapshot(
                    scope = scope,
                    entries = entries,
                    loading = false,
                    hasMore = result.hasMore,
                    supported = result.supported,
                ),
            )
        }
    }

    fun kickSession(entry: SessionEntry) {
        val sessions = _state.value.sessions
        if (sessions.kickingId != null || entry.current) return
        _state.update { it.copy(sessions = sessions.copy(kickingId = entry.id)) }
        viewModelScope.launch {
            val ok = repository.kickSession(sessions.scope, entry)
            _state.update {
                it.copy(
                    sessions = it.sessions.copy(kickingId = null),
                    messageRes = if (ok) R.string.server_admin_session_kicked else R.string.server_admin_action_failed,
                )
            }
            if (ok) loadSessionsInternal(reset = true, scope = sessions.scope)
        }
    }

    // endregion

    // region scheduled tasks

    private suspend fun loadScheduled() {
        _state.update { it.copy(scheduledLoading = true, messageRes = null) }
        val tasks = repository.actuatorScheduledTasks()
        _state.update {
            it.copy(
                scheduledLoading = false,
                scheduled = tasks,
                messageRes = if (tasks.isEmpty()) R.string.server_admin_scheduled_unavailable else null,
            )
        }
    }

    // endregion

    // region history

    fun loadHistory(reset: Boolean = false) {
        if (_state.value.history.loading) return
        viewModelScope.launch { loadHistoryInternal(reset) }
    }

    private suspend fun loadHistoryInternal(reset: Boolean) {
        val previous = _state.value.history
        val page = if (reset) 0 else previous.page + 1
        _state.update { it.copy(history = previous.copy(loading = true)) }
        val result = repository.history(page = page, size = HISTORY_PAGE_SIZE)
        _state.update {
            it.copy(
                history = HistorySnapshot(
                    events = if (reset) result.items else previous.events + result.items,
                    loading = false,
                    hasMore = result.hasMore,
                    page = page,
                ),
            )
        }
    }

    // endregion

    // region filesystem

    fun loadDirectory(path: String?) {
        viewModelScope.launch { loadDirectoryInternal(path) }
    }

    private suspend fun loadDirectoryInternal(path: String?) {
        _state.update { it.copy(files = it.files.copy(loading = true, error = false)) }
        val listing = repository.directory(path)
        _state.update {
            it.copy(
                files = FilesSnapshot(directory = listing, loading = false, error = listing == null),
            )
        }
    }

    // endregion

    // region log / shutdown

    private suspend fun loadLog() {
        _state.update { it.copy(logLoading = true) }
        val text = repository.logfile()
        _state.update {
            it.copy(
                log = text,
                logLoading = false,
                messageRes = if (text == null) R.string.server_admin_log_unavailable else null,
            )
        }
    }

    fun shutdown() {
        viewModelScope.launch {
            val ok = repository.shutdown()
            _state.update {
                it.copy(
                    messageRes = if (ok) R.string.server_admin_shutdown_requested else R.string.server_admin_action_failed,
                )
            }
        }
    }

    // endregion

    private suspend fun metricValue(name: String): Double? {
        val metric: ActuatorMetricDto? = repository.metric(name)
        val measurements = metric?.measurements.orEmpty()
        return (measurements.firstOrNull { it.statistic == "VALUE" }
            ?: measurements.firstOrNull { it.statistic == "COUNT" }
            ?: measurements.firstOrNull())?.value
    }

    private companion object {
        const val SESSION_PAGE_SIZE = 20
        const val HISTORY_PAGE_SIZE = 40
    }
}

/** ISO-8601 (offset or zulu) to epoch millis; null for blank/unparseable values. */
internal fun parseIsoMs(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}
