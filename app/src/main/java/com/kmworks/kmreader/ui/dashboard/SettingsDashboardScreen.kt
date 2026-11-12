package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.data.DashboardSectionSetting
import com.kmworks.kmreader.data.label

/**
 * 仪表盘自定义（对齐 iOS SettingsDashboardView）：
 * 选项开关 + 分区顺序（上移/下移）+ 分区显示开关，全部持久化到 AppSettingsStore。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDashboardScreen(
    onBack: () -> Unit,
    viewModel: SettingsDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibleSections = state.visibleSections
    val hiddenSections = state.hiddenSections

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("仪表盘") },
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
            item { GroupHeader("选项") }
            item {
                SwitchRow(
                    title = "卡片形态菜单",
                    subtitle = "在分区标题右侧显示卡片形态切换",
                    checked = state.cardKindMenu,
                    onCheckedChange = viewModel::setCardKindMenu,
                )
            }
            item {
                SwitchRow(
                    title = "阅读列表续读",
                    subtitle = "显示「进行中的阅读列表」分区",
                    checked = state.continuation,
                    onCheckedChange = viewModel::setContinuation,
                )
            }
            item {
                Text(
                    text = "范围说明：分区内容按仪表盘顶栏的库范围（全部 / 已钉选 / 单库）过滤。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { GroupHeader("分区") }
            items(visibleSections.size, key = { index -> visibleSections[index].id }) { index ->
                val setting = visibleSections[index]
                SectionSettingRow(
                    setting = setting,
                    isFirst = index == 0,
                    isLast = index == visibleSections.lastIndex,
                    onMove = { delta -> viewModel.move(setting.id, delta) },
                    onToggle = { viewModel.setVisible(setting.id, false) },
                )
            }

            if (hiddenSections.isNotEmpty()) {
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                item { GroupHeader("隐藏分区") }
                items(hiddenSections.size, key = { index -> hiddenSections[index].id }) { index ->
                    val setting = hiddenSections[index]
                    HiddenSectionRow(
                        setting = setting,
                        onShow = { viewModel.setVisible(setting.id, true) },
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                TextButton(
                    onClick = viewModel::reset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text("恢复默认", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 可见分区一行：名称 + 默认卡片形态 + 上移/下移 + 显示开关。 */
@Composable
private fun SectionSettingRow(
    setting: DashboardSectionSetting,
    isFirst: Boolean,
    isLast: Boolean,
    onMove: (Int) -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = setting.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "默认：${setting.defaultKind.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
        }
        IconButton(onClick = { onMove(1) }, enabled = !isLast) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
        }
        Switch(checked = true, onCheckedChange = { onToggle() })
    }
}

/** 隐藏分区一行：名称 + 加回按钮。 */
@Composable
private fun HiddenSectionRow(
    setting: DashboardSectionSetting,
    onShow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = setting.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "已隐藏",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShow) {
            Icon(Icons.Filled.Add, contentDescription = "显示分区")
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(
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
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
