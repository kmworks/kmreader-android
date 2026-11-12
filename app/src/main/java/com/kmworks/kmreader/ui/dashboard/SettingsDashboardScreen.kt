package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.DashboardSectionSetting
import com.kmworks.kmreader.data.SampleDashboardData
import com.kmworks.kmreader.data.label

/**
 * Dashboard customization: options (card kind menu / read list continuation),
 * section list with visibility toggles, arrow-based reorder and card kind override,
 * then reset. Aligned with SettingsDashboardView (iOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDashboardScreen(onBack: () -> Unit) {
    var sections by remember { mutableStateOf(SampleDashboardData.sectionSettings) }
    var cardKindMenu by rememberSaveable { mutableStateOf(true) }
    var continuationEnabled by rememberSaveable { mutableStateOf(true) }
    var kindMenuFor by remember { mutableStateOf<String?>(null) }

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
                    checked = cardKindMenu,
                    onCheckedChange = { cardKindMenu = it },
                )
            }
            item {
                SwitchRow(
                    title = "阅读列表续读",
                    subtitle = "显示「进行中的阅读列表」分区",
                    checked = continuationEnabled,
                    onCheckedChange = { continuationEnabled = it },
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
            itemsIndexed(sections, key = { _, s -> s.id }) { index, setting ->
                SectionSettingRow(
                    setting = setting,
                    isFirst = index == 0,
                    isLast = index == sections.lastIndex,
                    kindMenuOpen = kindMenuFor == setting.id,
                    onKindMenuOpenChange = { open -> kindMenuFor = if (open) setting.id else null },
                    onToggle = { enabled ->
                        sections = sections.map {
                            if (it.id == setting.id) it.copy(enabled = enabled) else it
                        }
                    },
                    onMove = { delta -> sections = sections.moved(index, delta) },
                    onKindChange = { kind ->
                        sections = sections.map {
                            if (it.id == setting.id) it.copy(kindOverride = kind) else it
                        }
                    },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                TextButton(
                    onClick = { sections = SampleDashboardData.sectionSettings },
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

/** One section row: handle placeholder + name/default kind + toggle; arrows + kind override below. */
@Composable
private fun SectionSettingRow(
    setting: DashboardSectionSetting,
    isFirst: Boolean,
    isLast: Boolean,
    kindMenuOpen: Boolean,
    onKindMenuOpenChange: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onKindChange: (DashboardCardKind?) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // drag handle placeholder; reorder uses the arrows below in this mock
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = "排序手柄",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = setting.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (setting.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "默认：${setting.defaultKind.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = setting.enabled, onCheckedChange = onToggle)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
            }
            Spacer(Modifier.width(8.dp))
            Box {
                AssistChip(
                    onClick = { onKindMenuOpenChange(true) },
                    label = { Text("卡片：${setting.kindOverride?.label ?: "跟随默认"}") },
                )
                DropdownMenu(
                    expanded = kindMenuOpen,
                    onDismissRequest = { onKindMenuOpenChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text("跟随默认") },
                        onClick = {
                            onKindMenuOpenChange(false)
                            onKindChange(null)
                        },
                    )
                    setting.availableKinds.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.label) },
                            onClick = {
                                onKindMenuOpenChange(false)
                                onKindChange(kind)
                            },
                        )
                    }
                }
            }
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun List<DashboardSectionSetting>.moved(index: Int, delta: Int): List<DashboardSectionSetting> {
    val target = (index + delta).coerceIn(0, size - 1)
    if (target == index) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
