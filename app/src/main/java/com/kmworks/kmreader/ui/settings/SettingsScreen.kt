package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.ui.RootViewModel
import com.kmworks.kmreader.ui.tracker.TrackerSettingsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 阅读统计/Tracker 入口的 kmrs 能力门控（SettingsScreen 副标题提示用）。 */
@HiltViewModel
class SettingsEntryViewModel @Inject constructor(
    serverProfileStore: ServerProfileStore,
) : ViewModel() {
    val readingStatsSupported: StateFlow<Boolean> = serverProfileStore.profile
        .map { it?.features?.contains(ServerFeature.ReadingStats) == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val trackersSupported: StateFlow<Boolean> = serverProfileStore.profile
        .map { it?.features?.contains(ServerFeature.Trackers) == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/** Settings hub (mirrors SettingsView). New entries are exposed via callbacks; host navigates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit = {},
    onOpenBrowse: () -> Unit = {},
    onOpenDashboard: () -> Unit = {},
    onOpenReaderDefaults: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenCustomFonts: () -> Unit = {},
    onOpenCache: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenSse: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    viewModel: SettingsEntryViewModel = hiltViewModel(),
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    // 真黑快捷开关与外观页读写同一个 AppSettingsStore.trueBlackDark，两处实时同步
    val trueBlack by prefsViewModel.trueBlackDark.collectAsStateWithLifecycle()
    val themeMode by prefsViewModel.themeMode.collectAsStateWithLifecycle()
    val serverConfig by rootViewModel.config.collectAsStateWithLifecycle()
    var showTrackerSettings by remember { mutableStateOf(false) }
    // kmrs 能力门控：无 ReadingStats/Trackers 特性时对应入口副标题提示
    val statsSupported by viewModel.readingStatsSupported.collectAsState()
    val trackersSupported by viewModel.trackersSupported.collectAsState()

    if (showTrackerSettings) {
        TrackerSettingsScreen(onBack = { showTrackerSettings = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item { SettingsGroupHeader("外观") }
            item { SettingsValueRow("主题", themeModeLabel(themeMode)) }
            item {
                SettingsSwitchRow(
                    title = "真黑背景",
                    subtitle = "OLED 下阅读器与仪表盘使用纯黑背景",
                    checked = trueBlack,
                    onCheckedChange = prefsViewModel::setTrueBlackDark,
                )
            }
            item {
                SettingsNavRow(
                    title = "外观设置",
                    subtitle = "主题、纯黑、应用图标",
                    onClick = onOpenAppearance,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader("浏览") }
            item {
                SettingsNavRow(
                    title = "浏览设置",
                    subtitle = "卡片样式与网格显示项",
                    onClick = onOpenBrowse,
                )
            }
            item {
                SettingsNavRow(
                    title = "仪表盘",
                    subtitle = "分区显示、排序与卡片形态",
                    onClick = onOpenDashboard,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader("阅读器") }
            item {
                SettingsNavRow(
                    title = "阅读器默认",
                    subtitle = "Divina / EPUB / PDF 默认偏好",
                    onClick = onOpenReaderDefaults,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader("阅读进度追踪") }
            item {
                SettingsNavRow(
                    title = "Tracker 账号",
                    subtitle = if (trackersSupported) {
                        "AniList / MyAnimeList / Bangumi / MangaBaka"
                    } else {
                        "AniList / MyAnimeList / Bangumi / MangaBaka（需要 kmrs 服务器）"
                    },
                    onClick = { showTrackerSettings = true },
                )
            }
            item {
                SettingsNavRow(
                    title = "阅读统计",
                    subtitle = if (statsSupported) {
                        "阅读历史、连续天数与热力图"
                    } else {
                        "阅读历史、连续天数与热力图（需要 kmrs 服务器）"
                    },
                    onClick = onOpenStats,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader("EPUB") }
            item {
                SettingsNavRow(
                    title = "自定义字体",
                    subtitle = "导入并管理 EPUB 字体",
                    onClick = onOpenCustomFonts,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader("缓存") }
            item {
                SettingsNavRow(
                    title = "缓存",
                    subtitle = "图片缓存占用与清理",
                    onClick = onOpenCache,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader("系统") }
            item {
                SettingsNavRow(
                    title = "网络",
                    subtitle = "超时与并发设置",
                    onClick = onOpenNetwork,
                )
            }
            item {
                SettingsNavRow(
                    title = "SSE 连接",
                    subtitle = "实时同步连接状态",
                    onClick = onOpenSse,
                )
            }
            item {
                SettingsNavRow(
                    title = "日志",
                    subtitle = "查看与分享运行日志",
                    onClick = onOpenLogs,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader("账户") }
            item { SettingsValueRow("当前实例", serverConfig?.normalizedBaseUrl ?: "未连接") }
            item {
                SettingsValueRow(
                    "账户",
                    when {
                        serverConfig == null -> "—"
                        serverConfig?.useApiKey == true -> "API Key"
                        else -> serverConfig?.username.orEmpty()
                    },
                )
            }
            item {
                SettingsNavRow(
                    title = "服务器账户",
                    subtitle = "多服务器切换与管理",
                    onClick = onOpenAccount,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader("关于") }
            item {
                SettingsNavRow(
                    title = "关于 KMReader",
                    subtitle = "版本与项目链接",
                    onClick = onOpenAbout,
                )
            }
            item {
                SettingsNavRow(
                    title = "开源许可",
                    subtitle = "第三方组件许可列表",
                    onClick = onOpenLicenses,
                )
            }
        }
    }
}

/** 主题存储值 → 显示标签（与外观页 ThemeOptions 一致）。 */
private fun themeModeLabel(mode: String): String = when (mode) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsValueRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
        TextButton(onClick = onClick) { Text("打开") }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
        Spacer(Modifier.height(0.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
