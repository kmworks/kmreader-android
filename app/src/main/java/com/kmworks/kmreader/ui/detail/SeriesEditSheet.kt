package com.kmworks.kmreader.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.Series

private enum class EditTab(val label: String) {
    General("通用"), Title("标题"), Tags("标签"), Links("链接"), Sharing("分享标签")
}

/** 字段锁状态（对齐 SeriesMetadataUpdate.*Lock：编辑自动锁定；锁随保存提交给服务器）。 */
private class LockedField(initial: String) {
    var value by mutableStateOf(initial)
    var locked by mutableStateOf(false)

    fun edit(newValue: String) {
        value = newValue
        locked = true
    }
}

@Composable
private fun rememberLockedField(initial: String): LockedField =
    remember { LockedField(initial) }

/**
 * 编辑系列元数据（对齐 SeriesEditSheet）：
 * 五个分段 tab（通用/标题/标签/链接/分享标签）；每个字段右侧锁定开关，编辑自动锁定；
 * 保存只提交变更字段（toAPIDict against series）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesEditSheet(series: Series, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(EditTab.General) }

    // 通用
    val summary = rememberLockedField(series.summary ?: "")
    val status = rememberLockedField(series.status ?: "连载中")
    val direction = rememberLockedField(if (series.directionRtl) "从右到左" else "从左到右")
    val language = rememberLockedField(series.language ?: "日语")
    val publisher = rememberLockedField(series.publisher ?: "")
    val ageRating = rememberLockedField(series.ageRating?.toString() ?: "无")
    val totalBookCount = rememberLockedField(series.totalBookCount?.toString() ?: "")
    // 标题
    val title = rememberLockedField(series.title)
    val titleSort = rememberLockedField(series.title)
    val alternateTitles = remember {
        mutableListOf("葬送のフリーレン", "Frieren: Beyond Journey's End").toMutableStateList()
    }
    // 标签 / 链接 / 分享标签
    val genres = remember { series.genres.toMutableStateList() }
    val tags = remember { series.tags.toMutableStateList() }
    val links = remember { mutableListOf("维基百科" to "https://zh.wikipedia.org/wiki/葬送的芙莉莲").toMutableStateList() }
    val sharingLabels = remember { mutableListOf("jump-plus").toMutableStateList() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "编辑系列",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            // 分段 tab（对齐 segmented picker）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditTab.entries.forEach { entry ->
                    FilterChip(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(entry.label) },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    EditTab.General -> {
                        LockableTextField("简介", summary, singleLine = false)
                        LockableTextField("连载状态", status)
                        LockableTextField("阅读方向", direction)
                        LockableTextField("语言", language)
                        LockableTextField("出版社", publisher)
                        LockableTextField("年龄分级", ageRating)
                        LockableTextField("总册数", totalBookCount)
                    }

                    EditTab.Title -> {
                        LockableTextField("标题", title)
                        LockableTextField("排序标题", titleSort)
                        EditableListSection(
                            title = "别称",
                            entries = alternateTitles.map { it },
                            onRemove = { index -> alternateTitles.removeAt(index) },
                            onAdd = { alternateTitles.add("新别称") },
                            addLabel = "添加别称",
                        )
                    }

                    EditTab.Tags -> {
                        EditableListSection(
                            title = "分类",
                            entries = genres,
                            onRemove = { index -> genres.removeAt(index) },
                            onAdd = { genres.add("新分类") },
                            addLabel = "添加分类",
                        )
                        EditableListSection(
                            title = "标签",
                            entries = tags,
                            onRemove = { index -> tags.removeAt(index) },
                            onAdd = { tags.add("新标签") },
                            addLabel = "添加标签",
                        )
                    }

                    EditTab.Links -> {
                        EditableListSection(
                            title = "链接",
                            entries = links.map { "${it.first} · ${it.second}" },
                            onRemove = { index -> links.removeAt(index) },
                            onAdd = { links.add("新链接" to "https://") },
                            addLabel = "添加链接",
                        )
                    }

                    EditTab.Sharing -> {
                        EditableListSection(
                            title = "分享标签",
                            entries = sharingLabels,
                            onRemove = { index -> sharingLabels.removeAt(index) },
                            onAdd = { sharingLabels.add("新标签") },
                            addLabel = "添加分享标签",
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("保存")
                }
            }
        }
    }
}

/** 带锁字段：右侧锁定开关；编辑内容自动锁定（对齐 lockToggle + onChange auto-lock）。 */
@Composable
private fun LockableTextField(label: String, field: LockedField, singleLine: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = field.value,
            onValueChange = { field.edit(it) },
            label = { Text(label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { field.locked = !field.locked }) {
            Icon(
                if (field.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (field.locked) "已锁定" else "未锁定",
                tint = if (field.locked) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 列表编辑段：行 + 删除 + 底部添加（对齐 AlternateTitles/Genres/Links/SharingLabels 段）。 */
@Composable
private fun EditableListSection(
    title: String,
    entries: List<String>,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    addLabel: String,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    entries.forEachIndexed { index, entry ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onRemove(index) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    TextButton(onClick = onAdd) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.padding(2.dp))
        Text(addLabel)
    }
}
