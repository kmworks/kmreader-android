package com.kmworks.kmreader.ui.settings

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.core.sse.SseConnectionState
import com.kmworks.kmreader.data.SampleSystemData
import com.kmworks.kmreader.ui.RootViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Cache settings (mirrors SettingsCacheView). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheSettingsScreen(onBack: () -> Unit) {
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
                        LinearProgressIndicator(
                            progress = { 1.2f / 2f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "已用 1.2 GB / 上限 2 GB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { /* wiring point: clear image cache */ },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清理缓存")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Network settings (mirrors SettingsNetworkView). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(onBack: () -> Unit) {
    var timeout by remember { mutableFloatStateOf(30f) }
    var concurrency by remember { mutableFloatStateOf(4f) }
    var wifiOnlyPreload by remember { mutableStateOf(true) }

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
                    value = timeout,
                    valueRange = 5f..120f,
                    display = "${timeout.roundToInt()} 秒",
                    onValueChange = { timeout = it.roundToInt().toFloat() },
                )
                SystemSliderRow(
                    label = "下载并发数",
                    value = concurrency,
                    valueRange = 1f..8f,
                    display = "${concurrency.roundToInt()} 个",
                    onValueChange = { concurrency = it.roundToInt().toFloat() },
                )
                SystemSwitchRow(
                    title = "仅 Wi-Fi 预加载",
                    subtitle = "阅读器预加载下一章时仅使用 Wi-Fi",
                    checked = wifiOnlyPreload,
                    onCheckedChange = { wifiOnlyPreload = it },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** SSE connection settings (mirrors SettingsSSEView). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SseSettingsScreen(
    onBack: () -> Unit,
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val connectionState by rootViewModel.sseConnectionState.collectAsStateWithLifecycle()
    val lastEventAt by rootViewModel.sseLastEventAt.collectAsStateWithLifecycle()
    val sseEnabled by rootViewModel.sseEnabled.collectAsStateWithLifecycle()

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

/** Log viewer (mirrors SettingsLogsView). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
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
                    // wiring point: share log file
                    IconButton(onClick = { }) {
                        Icon(Icons.Filled.Share, contentDescription = "分享日志")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            items(SampleSystemData.logLines) { line ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "${line.time}  ${line.level}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        line.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                HorizontalDivider()
            }
        }
    }
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
    SystemScaffold(title = "关于", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("KMReader（Android 设计稿）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "版本 0.1.0（设计稿，无网络）",
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
private fun SystemSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
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
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
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
