package com.kmworks.kmreader.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                ImageCacheCard(
                    title = stringResource(R.string.settings_cache_page),
                    usage = pageUsage,
                    usageFormat = R.string.settings_cache_usage_format,
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
                Spacer(Modifier.height(12.dp))
                ImageCacheCard(
                    title = stringResource(R.string.settings_cache_cover),
                    usage = coverUsage,
                    usageFormat = R.string.settings_cache_cover_usage_format,
                    limitDraft = coverLimitDraft,
                    limitRange = AppSettingsStore.COVER_CACHE_LIMIT_MIN_MB.toFloat()..
                        AppSettingsStore.COVER_CACHE_LIMIT_MAX_MB.toFloat(),
                    onLimitChange = { coverLimitDraft = (it / 128f).roundToInt() * 128f },
                    onLimitChangeFinished = {
                        prefsViewModel.setCoverCacheLimitMb(coverLimitDraft.roundToInt())
                    },
                    onClearCurrent = { pendingClear = CacheClearRequest(ImageCachePaths.KIND_COVERS, false) },
                    onClearAll = { pendingClear = CacheClearRequest(ImageCachePaths.KIND_COVERS, true) },
                    extraContent = {
                        Spacer(Modifier.height(12.dp))
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
                    },
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_cache_pdf),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            formatCacheBytes(pdfBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { pdfClearPending = true }) {
                        Text(stringResource(R.string.settings_cache_clear_action))
                    }
                }
                Spacer(Modifier.height(24.dp))
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

/** One cache kind: usage bar, cached size/count, cap slider, optional extra rows and the clear actions. */
@Composable
private fun ImageCacheCard(
    title: String,
    usage: CacheUsage,
    usageFormat: Int,
    limitDraft: Float,
    limitRange: ClosedFloatingPointRange<Float>,
    onLimitChange: (Float) -> Unit,
    onLimitChangeFinished: () -> Unit,
    onClearCurrent: () -> Unit,
    onClearAll: () -> Unit,
    extraContent: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
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
                stringResource(usageFormat, formatCacheBytes(usage.bytes), usage.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SystemSliderRow(
                label = stringResource(R.string.settings_cache_limit),
                value = limitDraft,
                valueRange = limitRange,
                display = formatCacheMb(limitDraft.roundToInt()),
                onValueChange = onLimitChange,
                onValueChangeFinished = onLimitChangeFinished,
            )
            extraContent?.invoke()
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClearCurrent, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_cache_clear_current),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = onClearAll, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_cache_clear_all),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                SystemSectionTitle(stringResource(R.string.settings_network_requests))
                SystemSliderRow(
                    label = stringResource(R.string.settings_network_timeout),
                    value = timeoutDraft,
                    valueRange = 5f..120f,
                    display = stringResource(R.string.settings_network_timeout_value, timeoutDraft.roundToInt()),
                    onValueChange = { timeoutDraft = it.roundToInt().toFloat() },
                    onValueChangeFinished = {
                        prefsViewModel.setNetworkTimeoutSeconds(timeoutDraft.roundToInt())
                    },
                )
                SystemSliderRow(
                    label = stringResource(R.string.settings_network_concurrency),
                    value = concurrencyDraft,
                    valueRange = 1f..8f,
                    display = stringResource(R.string.settings_network_concurrency_value, concurrencyDraft.roundToInt()),
                    onValueChange = { concurrencyDraft = it.roundToInt().toFloat() },
                    onValueChangeFinished = {
                        prefsViewModel.setDownloadConcurrency(concurrencyDraft.roundToInt())
                    },
                )
                SystemSwitchRow(
                    title = stringResource(R.string.settings_network_wifi_only_title),
                    subtitle = stringResource(R.string.settings_network_wifi_only_subtitle),
                    checked = wifiOnlyPreload,
                    onCheckedChange = prefsViewModel::setWifiOnlyPreload,
                )
                Spacer(Modifier.height(24.dp))
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
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                SystemSectionTitle(stringResource(R.string.settings_sse_status))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (connected) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(10.dp))
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_sse_updates_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_sse_updates_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sseEnabled, onCheckedChange = rootViewModel::setSseEnabled)
                }
                // Mirrors SettingsSSEView: the sub-switches are disabled while the master switch is off
                SystemSwitchRow(
                    title = stringResource(R.string.settings_sse_auto_refresh_title),
                    subtitle = stringResource(R.string.settings_sse_auto_refresh_subtitle),
                    checked = autoRefresh,
                    onCheckedChange = prefsViewModel::setSseAutoRefresh,
                    enabled = sseEnabled,
                )
                SystemSwitchRow(
                    title = stringResource(R.string.settings_sse_notify_title),
                    subtitle = stringResource(R.string.settings_sse_notify_subtitle),
                    checked = notify,
                    onCheckedChange = prefsViewModel::setSseNotify,
                    enabled = sseEnabled,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** HH:mm:ss event time. */
private fun formatSseTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

/** Log viewer (mirrors SettingsLogsView): real log lines + level filter + FileProvider sharing. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    var levelFilter by remember { mutableStateOf<AppLogLevel?>(null) }
    val filtered = remember(entries, levelFilter) {
        entries.filter { levelFilter == null || it.level == levelFilter }.asReversed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_logs)) },
                navigationIcon = {
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
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(filtered) { entry ->
                    // The whole entry is selectable for copying (needed for crash stack triage)
                    SelectionContainer {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Level badge: errors get a solid red tint, the rest stay muted outlines
                                val levelColor = when (entry.level) {
                                    AppLogLevel.ERROR -> MaterialTheme.colorScheme.error
                                    AppLogLevel.WARN -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(
                                    modifier = Modifier
                                        .background(
                                            levelColor.copy(
                                                alpha = if (entry.level == AppLogLevel.ERROR) 0.14f else 0.08f,
                                            ),
                                            RoundedCornerShape(4.dp),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        entry.level.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = levelColor,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    entry.timeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    entry.tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (entry.level == AppLogLevel.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
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

/** About page (mirrors SettingsAboutView). */
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
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("KMReader", style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_about_version_format, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                AboutLinkRow(title = stringResource(R.string.settings_about_original), value = "github.com/kmworks/kmreader")
                AboutLinkRow(title = stringResource(R.string.settings_about_komga), value = "komga.org")
                AboutLinkRow(title = stringResource(R.string.settings_about_feedback), value = "github.com/kmworks/kmreader/issues")
                Spacer(Modifier.height(24.dp))
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
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            items(licenses) { (name, license) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        license,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                HorizontalDivider()
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
                navigationIcon = {
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
private fun SystemSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SystemSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
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
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SystemSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row {
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

@Composable
private fun AboutLinkRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}
