package com.kmworks.kmreader.ui.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置 · Tracker 账号（对齐 TrackerAccountsView）：
 * provider 账号行（已连接显示用户名 / 未连接给出授权入口）+ 默认 Tracker + 库范围 + 绑定管理/更新台账。
 * 账号授权在服务器网页端 /account/trackers 完成，app 不持有 token。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackerSettingsScreen(onBack: () -> Unit) {
    var defaultTracker by remember { mutableStateOf(TrackerProvider.AniList) }
    val libraries = listOf("漫画", "日漫", "国漫")
    var selectedLibraries by remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读进度追踪") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            TrackerSettingsTitle("账号")
            SampleTrackerAccounts.forEach { account ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProviderLogo(account.provider)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            account.provider.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            account.connectedUser?.let { "已连接 · $it" } ?: "未连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (account.connectedUser != null) {
                        TextButton(onClick = { }) { Text("断开") }
                    } else {
                        TextButton(onClick = { }) { Text("去授权") }
                    }
                }
            }
            Text(
                "账号授权在服务器网页端 /account/trackers 完成，应用不保存 token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            TrackerSettingsTitle("默认 Tracker")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackerProvider.entries.forEach { provider ->
                    FilterChip(
                        selected = defaultTracker == provider,
                        onClick = { defaultTracker = provider },
                        label = { Text(provider.displayName) },
                    )
                }
            }

            TrackerSettingsTitle("启用的库")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                libraries.forEach { library ->
                    FilterChip(
                        selected = library in selectedLibraries,
                        onClick = {
                            selectedLibraries =
                                if (library in selectedLibraries) selectedLibraries - library
                                else selectedLibraries + library
                        },
                        label = { Text(library) },
                    )
                }
            }
            Text(
                "不选择任何库 = 全部库可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SettingsEntryRow(title = "绑定管理", subtitle = "按 provider 过滤、解绑", onClick = { })
            SettingsEntryRow(title = "更新台账", subtitle = "手动触发的 tracker 更新记录", onClick = { })
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun TrackerSettingsTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsEntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
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
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
