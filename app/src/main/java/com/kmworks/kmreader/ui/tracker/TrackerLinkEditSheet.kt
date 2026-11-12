package com.kmworks.kmreader.ui.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.CoverArt

/**
 * 绑定编辑（对齐 TrackerLinkEditSheet）：
 * 未选中 = 搜索（预填系列标题）+ 多标题胶囊 + 来自系列链接的建议 + 搜索结果；
 * 已选中 = Tracker Entry（结果行 + #id + Open/Change）→ Progress Mapping（模式 + 章节偏移）→
 * Remote Progress（状态/评分/章节/卷/起止日期，只提交变更非空字段）；
 * controls = Unlink（已链接时）+ Save（未选中禁用）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackerLinkEditSheet(
    series: Series,
    provider: TrackerProvider,
    existing: TrackerLink?,
    onUnlink: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(existing?.title ?: series.title) }
    var selectedTitle by remember { mutableStateOf<String?>(existing?.title) }
    var selectedId by remember { mutableStateOf(existing?.trackId) }
    var mode by remember { mutableStateOf(existing?.mode ?: TrackerMode.Auto) }
    var chapterOffset by remember { mutableIntStateOf(existing?.chapterOffset ?: 0) }
    var remoteStatus by remember { mutableStateOf<TrackerStatus?>(null) } // null = 保持不变

    val titles = remember(series) { listOf(series.title, "葬送のフリーレン", "Frieren") }
    val searchResults = remember(query) {
        listOf(
            "108465" to "葬送のフリーレン",
            "118743" to "Frieren at the Funeral",
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(provider)
                Spacer(Modifier.width(10.dp))
                Text(
                    provider.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            TrackerSectionTitle("Tracker 条目")
            if (selectedId != null) {
                // 选中态：结果行 + #id monospaced + Open 外链 + Change
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(
                        title = selectedTitle ?: series.title,
                        hue = series.hue,
                        modifier = Modifier
                            .width(40.dp)
                            .height(60.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedTitle ?: series.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "#$selectedId",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { }) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "在 ${provider.displayName} 打开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TextButton(onClick = { selectedId = null }) { Text("更换") }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索 ${provider.displayName}") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 多标题胶囊 chips（别称/译名）
                FlowRow(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    titles.forEach { title ->
                        FilterChip(
                            selected = query == title,
                            onClick = { query = title },
                            label = { Text(title, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
                // 来自系列链接的建议（系列 metadata links 反解 entryId）
                Surface(
                    onClick = {
                        selectedId = "285267"
                        selectedTitle = series.title
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "来自系列链接",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${series.title} · #285267",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                TrackerSectionTitle("搜索结果")
                searchResults.forEach { (id, title) ->
                    Surface(onClick = {
                        selectedId = id
                        selectedTitle = title
                    }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverArt(
                                title = title,
                                hue = series.hue,
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(60.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "#$id",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (selectedId != null) {
                // Progress Mapping：模式 + 章节偏移
                TrackerSectionTitle("进度映射")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackerMode.entries.forEach { entry ->
                        FilterChip(
                            selected = mode == entry,
                            onClick = { mode = entry },
                            label = { Text(entry.label) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "章节偏移",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { chapterOffset = (chapterOffset - 1).coerceAtLeast(-10000) }) {
                        Icon(Icons.Filled.Remove, contentDescription = "减")
                    }
                    Text(
                        "$chapterOffset",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(48.dp),
                    )
                    IconButton(onClick = { chapterOffset = (chapterOffset + 1).coerceAtMost(10000) }) {
                        Icon(Icons.Filled.Add, contentDescription = "加")
                    }
                }

                // Remote Progress：状态/评分/章节/卷/起止日期（只提交变更非空字段）
                TrackerSectionTitle("远端进度")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = remoteStatus == null,
                        onClick = { remoteStatus = null },
                        label = { Text("保持不变") },
                    )
                    TrackerStatus.entries.forEach { status ->
                        FilterChip(
                            selected = remoteStatus == status,
                            onClick = { remoteStatus = status },
                            label = { Text(status.label) },
                        )
                    }
                }
                Text(
                    "只提交变更的非空字段；自动同步跟随已读完的书籍。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (existing != null) {
                    OutlinedButton(
                        onClick = {
                            onUnlink()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("解除链接", color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = onDismiss,
                    enabled = selectedId != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun TrackerSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}
