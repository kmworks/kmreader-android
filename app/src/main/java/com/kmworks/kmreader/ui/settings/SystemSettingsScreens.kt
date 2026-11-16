package com.kmworks.kmreader.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.logging.AppLogLevel
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.sse.SseConnectionState
import com.kmworks.kmreader.ui.RootViewModel
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

/** 图片缓存用量：Coil3 磁盘缓存真实字节数 + 清空。 */
@HiltViewModel
class CacheSettingsViewModel @Inject constructor(
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheBytes.value = runCatching {
                sessionManager.imageLoader.value?.diskCache?.size ?: 0L
            }.getOrDefault(0L)
        }
    }

    /** 清空内存 + 磁盘图片缓存（对齐 iOS SettingsCacheView 的 Clear）。 */
    fun clear() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val loader = sessionManager.imageLoader.value
                loader?.memoryCache?.clear()
                loader?.diskCache?.clear()
            }.onSuccess {
                AppLog.i("Cache", "image cache cleared")
            }.onFailure {
                AppLog.e("Cache", "clear image cache failed", it)
            }
            refresh()
        }
    }
}

/** Cache settings (mirrors SettingsCacheView)：真实占用 + 上限持久化 + 清空确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheSettingsScreen(
    onBack: () -> Unit,
    viewModel: CacheSettingsViewModel = hiltViewModel(),
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val limitMb by prefsViewModel.imageCacheLimitMb.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }
    // 上限滑杆的本地拖动态，拖动结束才落盘
    var limitDraft by rememberSaveable(limitMb) { mutableFloatStateOf(limitMb.toFloat()) }

    SystemScaffold(title = "缓存", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("图片缓存", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        val limitBytes = limitDraft.toLong() * 1024L * 1024L
                        LinearProgressIndicator(
                            progress = {
                                if (limitBytes > 0) {
                                    (cacheBytes.toFloat() / limitBytes).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "已用 ${formatCacheBytes(cacheBytes)} / 上限 ${formatCacheMb(limitDraft.roundToInt())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SystemSliderRow(
                    label = "缓存上限",
                    value = limitDraft,
                    valueRange = 512f..8192f,
                    display = formatCacheMb(limitDraft.roundToInt()),
                    onValueChange = { limitDraft = (it / 128f).roundToInt() * 128f },
                    onValueChangeFinished = {
                        prefsViewModel.setImageCacheLimitMb(limitDraft.roundToInt())
                    },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清理缓存")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清理缓存") },
            text = { Text("将清空全部图片缓存（内存与磁盘），封面会重新从服务器加载。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clear()
                    },
                ) {
                    Text("清理")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            },
        )
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

/** Network settings (mirrors SettingsNetworkView)：超时/并发/仅 Wi-Fi 预加载全部持久化。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    onBack: () -> Unit,
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    val timeoutSeconds by prefsViewModel.networkTimeoutSeconds.collectAsStateWithLifecycle()
    val concurrency by prefsViewModel.downloadConcurrency.collectAsStateWithLifecycle()
    val wifiOnlyPreload by prefsViewModel.wifiOnlyPreload.collectAsStateWithLifecycle()
    // 滑杆本地拖动态，拖动结束才落盘
    var timeoutDraft by rememberSaveable(timeoutSeconds) { mutableFloatStateOf(timeoutSeconds.toFloat()) }
    var concurrencyDraft by rememberSaveable(concurrency) { mutableFloatStateOf(concurrency.toFloat()) }

    SystemScaffold(title = "网络", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                SystemSectionTitle("请求")
                SystemSliderRow(
                    label = "请求超时",
                    value = timeoutDraft,
                    valueRange = 5f..120f,
                    display = "${timeoutDraft.roundToInt()} 秒",
                    onValueChange = { timeoutDraft = it.roundToInt().toFloat() },
                    onValueChangeFinished = {
                        prefsViewModel.setNetworkTimeoutSeconds(timeoutDraft.roundToInt())
                    },
                )
                SystemSliderRow(
                    label = "下载并发数",
                    value = concurrencyDraft,
                    valueRange = 1f..8f,
                    display = "${concurrencyDraft.roundToInt()} 个",
                    onValueChange = { concurrencyDraft = it.roundToInt().toFloat() },
                    onValueChangeFinished = {
                        prefsViewModel.setDownloadConcurrency(concurrencyDraft.roundToInt())
                    },
                )
                SystemSwitchRow(
                    title = "仅 Wi-Fi 预加载",
                    subtitle = "阅读器预加载下一章时仅使用 Wi-Fi",
                    checked = wifiOnlyPreload,
                    onCheckedChange = prefsViewModel::setWifiOnlyPreload,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** SSE connection settings (mirrors SettingsSSEView)：状态 + 总开关 + 自动刷新/通知联动开关。 */
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
        is SseConnectionState.Connected -> "已连接"
        is SseConnectionState.Connecting -> "连接中…"
        is SseConnectionState.Retrying -> "重连中（${state.retryInSeconds} 秒后）"
        is SseConnectionState.Disconnected -> "未连接"
    }
    val lastEventText = lastEventAt?.let(::formatSseTime) ?: "暂无"

    SystemScaffold(title = "SSE 连接", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                SystemSectionTitle("状态")
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
                            "最近事件：$lastEventText",
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
                        Text("SSE 实时更新", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "接收服务端实时事件，关闭后断开长连",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sseEnabled, onCheckedChange = rootViewModel::setSseEnabled)
                }
                // 对齐 SettingsSSEView：总开关关闭时子开关禁用
                SystemSwitchRow(
                    title = "自动刷新",
                    subtitle = "收到内容事件时自动刷新仪表盘",
                    checked = autoRefresh,
                    onCheckedChange = prefsViewModel::setSseAutoRefresh,
                    enabled = sseEnabled,
                )
                SystemSwitchRow(
                    title = "连接通知",
                    subtitle = "连接状态变化与任务完成时通知",
                    checked = notify,
                    onCheckedChange = prefsViewModel::setSseNotify,
                    enabled = sseEnabled,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** HH:mm:ss 事件时间。 */
private fun formatSseTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

/** Log viewer (mirrors SettingsLogsView)：真实日志行 + 级别过滤 + FileProvider 分享。 */
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
                title = { Text("日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { shareLogs(context) }) {
                        Icon(Icons.Filled.Share, contentDescription = "分享日志")
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
                    label = { Text("全部") },
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
                            "暂无日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(filtered) { entry ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            "${entry.timeLabel}  ${entry.level.label}  [${entry.tag}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (entry.level) {
                                AppLogLevel.ERROR -> MaterialTheme.colorScheme.error
                                AppLogLevel.WARN -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 导出 app.log 为 txt 并调起系统分享。 */
private fun shareLogs(context: Context) {
    val file = AppLog.shareableFile() ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享日志"))
}

/** Current server account (mirrors SettingsAccountView: instance info + sign out). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val config by rootViewModel.config.collectAsStateWithLifecycle()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    SystemScaffold(title = "服务器账户", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "当前服务器",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "已连接",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            config?.normalizedBaseUrl.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            when {
                                config == null -> ""
                                config?.useApiKey == true -> "认证方式：API Key"
                                else -> "账户：${config?.username.orEmpty()}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { showLogoutConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("退出登录")
                }
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("将清除本机保存的服务器与凭据，需要重新连接。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        rootViewModel.logout()
                    },
                ) {
                    Text("退出登录")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
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
    SystemScaffold(title = "关于", onBack = onBack) { padding ->
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
                    "版本 $versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                AboutLinkRow(title = "KMReader 原作", value = "github.com/kmworks/kmreader")
                AboutLinkRow(title = "Komga 服务器", value = "komga.org")
                AboutLinkRow(title = "问题反馈", value = "github.com/kmworks/kmreader/issues")
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
        "Readium（EPUB 引擎参考）" to "BSD-3-Clause",
        "PDFium（PDF 引擎参考）" to "Apache-2.0",
    )
    SystemScaffold(title = "开源许可", onBack = onBack) { padding ->
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
private fun SystemScaffold(
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
