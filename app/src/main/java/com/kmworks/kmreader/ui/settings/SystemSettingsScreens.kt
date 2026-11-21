package com.kmworks.kmreader.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ImageCachePaths
import com.kmworks.kmreader.core.data.ImageCacheStore
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.logging.AppLogLevel
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.sse.SseConnectionState
import com.kmworks.kmreader.ui.RootViewModel
import com.kmworks.kmreader.core.pdf.PdfRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Usage of one image cache kind: bytes on disk + number of cached entries. */
data class CacheUsage(val bytes: Long = 0L, val count: Int = 0)

/**
 * Page / cover cache usage and cleanup (mirrors the iOS SettingsCacheView page and cover sections).
 * The two caches are independent: switching servers namespaces them, and each has its own cap and
 * clear actions ("current server" keeps the other saved instances' images).
 */
@HiltViewModel
class CacheSettingsViewModel @Inject constructor(
    private val imageCaches: ImageCacheStore,
    private val pdfRepository: PdfRepository,
) : ViewModel() {

    private val _pageUsage = MutableStateFlow(CacheUsage())
    val pageUsage: StateFlow<CacheUsage> = _pageUsage.asStateFlow()

    private val _coverUsage = MutableStateFlow(CacheUsage())
    val coverUsage: StateFlow<CacheUsage> = _coverUsage.asStateFlow()

    /** Whole-book PDF downloads share the settings screen but have no dedicated cache kind. */
    private val _pdfBytes = MutableStateFlow(0L)
    val pdfBytes: StateFlow<Long> = _pdfBytes.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _pageUsage.value = usageOf(ImageCachePaths.KIND_PAGES)
            _coverUsage.value = usageOf(ImageCachePaths.KIND_COVERS)
            _pdfBytes.value = runCatching { pdfRepository.cacheBytes() }.getOrDefault(0L)
        }
    }

    private suspend fun usageOf(kind: String): CacheUsage =
        runCatching { imageCaches.stats(kind) }
            .map { CacheUsage(it.bytes, it.count) }
            .getOrDefault(CacheUsage())

    /** Clear one cache kind; [allServers] mirrors the iOS "Clear All" alert. */
    fun clear(kind: String, allServers: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { imageCaches.clear(kind, allServers) }
                .onSuccess { AppLog.i("Cache", "cleared $kind cache (allServers=$allServers)") }
                .onFailure { AppLog.e("Cache", "clear $kind cache failed", it) }
            refresh()
        }
    }

    fun clearPdfCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { pdfRepository.clearAll() }
                .onSuccess { AppLog.i("Cache", "pdf cache cleared") }
                .onFailure { AppLog.e("Cache", "clear pdf cache failed", it) }
            refresh()
        }
    }
}

/** Which cache clear the confirmation dialog is asking about. */
private data class CacheClearRequest(val kind: String, val allServers: Boolean)

/**
 * Cache settings (mirrors iOS SettingsCacheView): a card per cache kind with its own usage, cap,
 * clear actions (and, for covers, the refresh interval), plus the downloaded PDF cache.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheSettingsScreen(
    onBack: () -> Unit,
    viewModel: CacheSettingsViewModel = hiltViewModel(),
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    val pageUsage by viewModel.pageUsage.collectAsStateWithLifecycle()
    val coverUsage by viewModel.coverUsage.collectAsStateWithLifecycle()
    val pdfBytes by viewModel.pdfBytes.collectAsStateWithLifecycle()

    val pageLimitMb by prefsViewModel.pageCacheLimitMb.collectAsStateWithLifecycle()
    val coverLimitMb by prefsViewModel.coverCacheLimitMb.collectAsStateWithLifecycle()
    val expirationDays by prefsViewModel.coverCacheExpirationDays.collectAsStateWithLifecycle()

    // Local drag state of each slider; only persisted when the drag ends.
    var pageLimitDraft by rememberSaveable(pageLimitMb) { mutableFloatStateOf(pageLimitMb.toFloat()) }
    var coverLimitDraft by rememberSaveable(coverLimitMb) { mutableFloatStateOf(coverLimitMb.toFloat()) }
    var expirationDraft by rememberSaveable(expirationDays) { mutableFloatStateOf(expirationDays.toFloat()) }

    var pendingClear by remember { mutableStateOf<CacheClearRequest?>(null) }
    var pdfClearPending by remember { mutableStateOf(false) }

    SystemScaffold(title = stringResource(R.string.settings_cache), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                CacheSection(
                    title = stringResource(R.string.settings_cache_page),
                    usage = pageUsage,
                    countLabel = stringResource(R.string.settings_cache_cached_images),
                    limitDraft = pageLimitDraft,
                    limitRange = AppSettingsStore.PAGE_CACHE_LIMIT_MIN_MB.toFloat()..
                        AppSettingsStore.PAGE_CACHE_LIMIT_MAX_MB.toFloat(),
                    onLimitChange = { pageLimitDraft = (it / 1024f).roundToInt() * 1024f },
                    onLimitChangeFinished = {
                        prefsViewModel.setPageCacheLimitMb(pageLimitDraft.roundToInt())
                    },
                    onClearCurrent = { pendingClear = CacheClearRequest(ImageCachePaths.KIND_PAGES, false) },
                    onClearAll = { pendingClear = CacheClearRequest(ImageCachePaths.KIND_PAGES, true) },
                )
            }
            item {
                CacheSection(
                    title = stringResource(R.string.settings_cache_cover),
                    usage = coverUsage,
                    countLabel = stringResource(R.string.settings_cache_cached_covers),
                    limitDraft = coverLimitDraft,
                    limitRange = AppSettingsStore.COVER_CACHE_LIMIT_MIN_MB.toFloat()..
                        AppSettingsStore.COVER_CACHE_LIMIT_MAX_MB.toFloat(),
                    onLimitChange = { coverLimitDraft = (it / 128f).roundToInt() * 128f },
                    onLimitChangeFinished = {
                        prefsViewModel.setCoverCacheLimitMb(coverLimitDraft.roundToInt())
                    },
                    onClearCurrent = { pendingClear = CacheClearRequest(ImageCachePaths.KIND_COVERS, false) },
                    onClearAll = { pendingClear = CacheClearRequest(ImageCachePaths.KIND_COVERS, true) },
                    extraRows = {
                        SettingsRowDivider()
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SystemSliderRow(
                                label = stringResource(R.string.settings_cache_cover_expiration_label),
                                value = expirationDraft,
                                valueRange = 1f..90f,
                                display = stringResource(
                                    R.string.settings_cache_cover_expiration_value,
                                    expirationDraft.roundToInt(),
                                ),
                                onValueChange = { expirationDraft = it.roundToInt().toFloat() },
                                onValueChangeFinished = {
                                    prefsViewModel.setCoverCacheExpirationDays(expirationDraft.roundToInt())
                                },
                            )
                        }
                    },
                )
            }
            item {
                SettingsSection(title = stringResource(R.string.settings_cache_pdf)) {
                    SettingsValueRow(
                        title = stringResource(R.string.settings_cache_pdf_size),
                        value = formatCacheBytes(pdfBytes),
                    )
                    SettingsRowDivider()
                    SettingsDangerRow(
                        text = stringResource(R.string.settings_cache_clear_action),
                        icon = Icons.Filled.Delete,
                        onClick = { pdfClearPending = true },
                    )
                }
            }
        }
    }

    pendingClear?.let { request ->
        val isPage = request.kind == ImageCachePaths.KIND_PAGES
        val titleRes = when {
            isPage && request.allServers -> R.string.settings_cache_clear_all
            isPage -> R.string.settings_cache_clear_current
            request.allServers -> R.string.settings_cache_clear_all
            else -> R.string.settings_cache_clear_current
        }
        val messageRes = when {
            isPage && request.allServers -> R.string.settings_cache_clear_page_all_message
            isPage -> R.string.settings_cache_clear_page_current_message
            request.allServers -> R.string.settings_cache_clear_cover_all_message
            else -> R.string.settings_cache_clear_cover_current_message
        }
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text(stringResource(titleRes)) },
            text = { Text(stringResource(messageRes)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClear = null
                        viewModel.clear(request.kind, request.allServers)
                    },
                ) {
                    Text(stringResource(R.string.settings_cache_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (pdfClearPending) {
        AlertDialog(
            onDismissRequest = { pdfClearPending = false },
            title = { Text(stringResource(R.string.settings_cache_pdf)) },
            text = { Text(stringResource(R.string.settings_cache_clear_pdf_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pdfClearPending = false
                        viewModel.clearPdfCache()
                    },
                ) {
                    Text(stringResource(R.string.settings_cache_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pdfClearPending = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** One cache kind: usage bar, size/count rows, cap slider, optional extra rows and the destructive clear actions. */
@Composable
private fun CacheSection(
    title: String,
    usage: CacheUsage,
    countLabel: String,
    limitDraft: Float,
    limitRange: ClosedFloatingPointRange<Float>,
    onLimitChange: (Float) -> Unit,
    onLimitChangeFinished: () -> Unit,
    onClearCurrent: () -> Unit,
    onClearAll: () -> Unit,
    extraRows: @Composable ColumnScope.() -> Unit = {},
) {
    SettingsSection(title = title) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val limitBytes = limitDraft.toLong() * 1024L * 1024L
            LinearProgressIndicator(
                progress = {
                    if (limitBytes > 0) (usage.bytes.toFloat() / limitBytes).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.settings_cache_used_of,
                    formatCacheBytes(usage.bytes),
                    formatCacheMb(limitDraft.roundToInt()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsRowDivider()
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SystemSliderRow(
                label = stringResource(R.string.settings_cache_limit),
                value = limitDraft,
                valueRange = limitRange,
                display = formatCacheMb(limitDraft.roundToInt()),
                onValueChange = onLimitChange,
                onValueChangeFinished = onLimitChangeFinished,
            )
        }
        extraRows()
        SettingsRowDivider()
        SettingsValueRow(
            title = stringResource(R.string.settings_cache_cached_size),
            value = formatCacheBytes(usage.bytes),
        )
        SettingsRowDivider()
        SettingsValueRow(
            title = countLabel,
            value = formatCacheCount(usage.count),
        )
        SettingsRowDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val destructiveColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = onClearCurrent,
                modifier = Modifier.weight(1f),
                colors = destructiveColors,
            ) {
                Text(
                    stringResource(R.string.settings_cache_clear_current),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
            TextButton(
                onClick = onClearAll,
                modifier = Modifier.weight(1f),
                colors = destructiveColors,
            ) {
                Text(
                    stringResource(R.string.settings_cache_clear_all),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun formatCacheBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toFloat())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toFloat())
    else -> "$bytes B"
}

private fun formatCacheMb(mb: Int): String =
    if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB"

private fun formatCacheCount(count: Int): String = String.format(Locale.getDefault(), "%,d", count)

/** Network settings (mirrors SettingsNetworkView): timeout / concurrency / Wi-Fi-only preload are all persisted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    onBack: () -> Unit,
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    val timeoutSeconds by prefsViewModel.networkTimeoutSeconds.collectAsStateWithLifecycle()
    val concurrency by prefsViewModel.downloadConcurrency.collectAsStateWithLifecycle()
    val wifiOnlyPreload by prefsViewModel.wifiOnlyPreload.collectAsStateWithLifecycle()
    // Local drag states of the sliders; only persisted when the drag ends
    var timeoutDraft by rememberSaveable(timeoutSeconds) { mutableFloatStateOf(timeoutSeconds.toFloat()) }
    var concurrencyDraft by rememberSaveable(concurrency) { mutableFloatStateOf(concurrency.toFloat()) }

    SystemScaffold(title = stringResource(R.string.settings_network), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_network_requests),
                    footer = stringResource(R.string.settings_network_footer),
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SystemSliderRow(
                            label = stringResource(R.string.settings_network_timeout),
                            value = timeoutDraft,
                            valueRange = 5f..120f,
                            display = stringResource(
                                R.string.settings_network_timeout_value,
                                timeoutDraft.roundToInt(),
                            ),
                            icon = Icons.Outlined.Schedule,
                            onValueChange = { timeoutDraft = it.roundToInt().toFloat() },
                            onValueChangeFinished = {
                                prefsViewModel.setNetworkTimeoutSeconds(timeoutDraft.roundToInt())
                            },
                        )
                    }
                    SettingsRowDivider()
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SystemSliderRow(
                            label = stringResource(R.string.settings_network_concurrency),
                            value = concurrencyDraft,
                            valueRange = 1f..8f,
                            display = stringResource(
                                R.string.settings_network_concurrency_value,
                                concurrencyDraft.roundToInt(),
                            ),
                            icon = Icons.Outlined.Download,
                            onValueChange = { concurrencyDraft = it.roundToInt().toFloat() },
                            onValueChangeFinished = {
                                prefsViewModel.setDownloadConcurrency(concurrencyDraft.roundToInt())
                            },
                        )
                    }
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_network_wifi_only_title),
                        subtitle = stringResource(R.string.settings_network_wifi_only_subtitle),
                        checked = wifiOnlyPreload,
                        onCheckedChange = prefsViewModel::setWifiOnlyPreload,
                        icon = Icons.Outlined.Wifi,
                    )
                }
            }
        }
    }
}

/** SSE connection settings (mirrors SettingsSSEView): status + master switch + linked auto-refresh/notification switches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SseSettingsScreen(
    onBack: () -> Unit,
    rootViewModel: RootViewModel = hiltViewModel(),
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    val connectionState by rootViewModel.sseConnectionState.collectAsStateWithLifecycle()
    val lastEventAt by rootViewModel.sseLastEventAt.collectAsStateWithLifecycle()
    val sseEnabled by rootViewModel.sseEnabled.collectAsStateWithLifecycle()
    val autoRefresh by prefsViewModel.sseAutoRefresh.collectAsStateWithLifecycle()
    val notify by prefsViewModel.sseNotify.collectAsStateWithLifecycle()

    val connected = connectionState is SseConnectionState.Connected
    val statusText = when (val state = connectionState) {
        is SseConnectionState.Connected -> stringResource(R.string.settings_sse_connected)
        is SseConnectionState.Connecting -> stringResource(R.string.settings_sse_connecting)
        is SseConnectionState.Retrying -> stringResource(R.string.settings_sse_retrying, state.retryInSeconds)
        is SseConnectionState.Disconnected -> stringResource(R.string.settings_sse_disconnected)
    }
    val lastEventText = lastEventAt?.let(::formatSseTime) ?: stringResource(R.string.common_none)

    SystemScaffold(title = stringResource(R.string.settings_sse), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsSection(title = stringResource(R.string.settings_sse_status)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (connected) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = if (connected) Color(0xFF43A047) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                statusText,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(R.string.settings_sse_last_event, lastEventText),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_sse_connection_section),
                    footer = stringResource(R.string.settings_sse_footer),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sse_updates_title),
                        subtitle = stringResource(R.string.settings_sse_updates_subtitle),
                        checked = sseEnabled,
                        onCheckedChange = rootViewModel::setSseEnabled,
                        icon = Icons.Outlined.Sensors,
                    )
                }
            }
            item {
                // Mirrors SettingsSSEView: the sub-switches are disabled while the master switch is off
                SettingsSection(
                    title = stringResource(R.string.settings_sse_auto_refresh_section),
                    footer = if (sseEnabled) {
                        stringResource(R.string.settings_sse_auto_refresh_footer)
                    } else {
                        stringResource(R.string.settings_sse_enable_first)
                    },
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sse_auto_refresh_title),
                        subtitle = stringResource(R.string.settings_sse_auto_refresh_subtitle),
                        checked = autoRefresh,
                        onCheckedChange = prefsViewModel::setSseAutoRefresh,
                        enabled = sseEnabled,
                        icon = Icons.Outlined.Sync,
                    )
                }
            }
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_sse_notifications_section),
                    footer = if (sseEnabled) {
                        stringResource(R.string.settings_sse_notify_footer)
                    } else {
                        stringResource(R.string.settings_sse_enable_first)
                    },
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sse_notify_title),
                        subtitle = stringResource(R.string.settings_sse_notify_subtitle),
                        checked = notify,
                        onCheckedChange = prefsViewModel::setSseNotify,
                        enabled = sseEnabled,
                        icon = Icons.Outlined.Notifications,
                    )
                }
            }
        }
    }
}

/** HH:mm:ss event time. */
private fun formatSseTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

/** Full local timestamp shown on the right of a log row (iOS yyyy-MM-dd HH:mm:ss.SSS). */
private val logDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** Log viewer (mirrors SettingsLogsView): level/category/search filters, full timestamps and FileProvider sharing. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    var levelFilter by remember { mutableStateOf<AppLogLevel?>(null) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val categories = remember(entries) {
        entries.groupingBy { it.tag }.eachCount().toList().sortedByDescending { it.second }
    }
    val filtered = remember(entries, levelFilter, categoryFilter, query) {
        val text = query.trim()
        entries.asReversed().filter { entry ->
            (levelFilter == null || entry.level == levelFilter) &&
                (categoryFilter == null || entry.tag == categoryFilter) &&
                (
                    text.isEmpty() ||
                        entry.message.contains(text, ignoreCase = true) ||
                        entry.tag.contains(text, ignoreCase = true)
                    )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_logs)) },
                navigationIcon = SettingsBackIcon {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { shareLogs(context) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.settings_logs_share))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.settings_logs_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = levelFilter == null,
                    onClick = { levelFilter = null },
                    label = { Text(stringResource(R.string.common_all)) },
                )
                AppLogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = levelFilter == level,
                        onClick = { levelFilter = level },
                        label = { Text(level.label) },
                    )
                }
                Box {
                    FilterChip(
                        selected = categoryFilter != null,
                        onClick = { categoryMenuOpen = true },
                        label = {
                            Text(categoryFilter ?: stringResource(R.string.settings_logs_all_categories))
                        },
                        trailingIcon = {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        },
                    )
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_logs_all_categories)) },
                            onClick = {
                                categoryFilter = null
                                categoryMenuOpen = false
                            },
                        )
                        categories.forEach { (tag, count) ->
                            DropdownMenuItem(
                                text = { Text("$tag ($count)") },
                                onClick = {
                                    categoryFilter = tag
                                    categoryMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.settings_logs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                        )
                    }
                }
                items(filtered) { entry ->
                    // The whole entry is selectable for copying (needed for crash stack triage)
                    SelectionContainer {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Level keeps the iOS colored level text; errors stand out in red.
                                val levelColor = when (entry.level) {
                                    AppLogLevel.ERROR -> MaterialTheme.colorScheme.error
                                    AppLogLevel.WARN -> Color(0xFFEF6C00)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Text(
                                    text = entry.level.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = levelColor,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = entry.tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = logDateFormatter.format(Instant.ofEpochMilli(entry.atMillis)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (entry.level == AppLogLevel.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Exports app.log as a txt and opens the system share sheet. */
private fun shareLogs(context: Context) {
    val file = AppLog.shareableFile() ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.settings_logs_share)))
}

/** About page (mirrors SettingsAboutView): icon link rows and the version centered below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "0.1.0" }
    }
    SystemScaffold(title = stringResource(R.string.settings_about), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsSection {
                    AboutLinkRow(
                        title = stringResource(R.string.settings_about_project),
                        value = "github.com/kmworks/kmreader-android",
                        icon = Icons.Outlined.Code,
                    )
                    SettingsRowDivider()
                    AboutLinkRow(
                        title = stringResource(R.string.settings_about_original),
                        value = "kmworks.date/reader/",
                        icon = Icons.Outlined.Public,
                    )
                    SettingsRowDivider()
                    AboutLinkRow(
                        title = stringResource(R.string.settings_about_server),
                        value = "kmworks.date/server/",
                        icon = Icons.Outlined.Dns,
                    )
                    SettingsRowDivider()
                    AboutLinkRow(
                        title = stringResource(R.string.settings_about_feedback),
                        value = "github.com/kmworks/kmreader-android/issues",
                        icon = Icons.Outlined.BugReport,
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.settings_about_version_format, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Open source licenses (mirrors OpenSourceLicensesView). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    // Placeholder license list; real build generates this from dependencies
    val licenses = listOf(
        "Jetpack Compose" to "Apache-2.0",
        "Material Icons Extended" to "Apache-2.0",
        "Kotlin Coroutines" to "Apache-2.0",
        stringResource(R.string.settings_license_readium) to "BSD-3-Clause",
        stringResource(R.string.settings_license_pdfium) to "Apache-2.0",
    )
    SystemScaffold(title = stringResource(R.string.settings_licenses), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_licenses),
                    footer = stringResource(R.string.settings_licenses_subtitle),
                ) {
                    licenses.forEachIndexed { index, (name, license) ->
                        if (index > 0) SettingsRowDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    license,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = SettingsBackIcon {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        content = content,
    )
}

@Composable
private fun SystemSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    icon: ImageVector? = null,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                display,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
        )
    }
}

/** About row whose [value] is shown under the title and opens [url] in the browser. */
@Composable
private fun AboutLinkRow(
    title: String,
    value: String,
    icon: ImageVector,
    url: String = "https://$value",
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
