package com.kmworks.kmreader.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.core.api.model.AlternateTitleUpdateDto
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.core.api.model.WebLinkUpdateDto
import com.kmworks.kmreader.data.Series

/** 系列元数据编辑的初始值（DTO 直出，未改动的字段不会提交）。 */
data class SeriesEditValues(
    val title: String,
    val titleSort: String,
    val summary: String,
    /** ONGOING / ENDED / ABANDONED / HIATUS */
    val status: String,
    /** LEFT_TO_RIGHT / RIGHT_TO_LEFT / VERTICAL / WEBTOON */
    val readingDirection: String,
    val language: String,
    val publisher: String,
    val ageRating: String,
    val totalBookCount: String,
    val genres: List<String>,
    val tags: List<String>,
    val links: List<Pair<String, String>>,
    val alternateTitles: List<Pair<String, String>>,
    val sharingLabels: List<String>,
) {
    companion object {
        private val statusLabels = mapOf(
            "ONGOING" to "连载中",
            "ENDED" to "已完结",
            "ABANDONED" to "已弃坑",
            "HIATUS" to "休刊中",
        )

        private val directionLabels = mapOf(
            "LEFT_TO_RIGHT" to "从左到右",
            "RIGHT_TO_LEFT" to "从右到左",
            "VERTICAL" to "纵向",
            "WEBTOON" to "条漫",
        )

        fun statusLabel(token: String): String = statusLabels[token] ?: token

        fun directionLabel(token: String): String = directionLabels[token] ?: token

        /** 无 DTO 时的回退：从 UI 模型尽力还原可编辑字段。 */
        fun fromUiSeries(series: Series): SeriesEditValues = SeriesEditValues(
            title = series.title,
            titleSort = series.title,
            summary = series.summary.orEmpty(),
            status = statusLabels.entries.firstOrNull { it.value == series.status }?.key ?: "ONGOING",
            readingDirection = if (series.directionRtl) "RIGHT_TO_LEFT" else "LEFT_TO_RIGHT",
            language = series.language.orEmpty(),
            publisher = series.publisher.orEmpty(),
            ageRating = series.ageRating?.toString().orEmpty(),
            totalBookCount = series.totalBookCount?.toString().orEmpty(),
            genres = series.genres,
            tags = series.tags,
            links = series.links,
            alternateTitles = emptyList(),
            sharingLabels = emptyList(),
        )
    }
}

internal val seriesStatusOptions = listOf("ONGOING", "ENDED", "ABANDONED", "HIATUS")
internal val seriesDirectionOptions = listOf("LEFT_TO_RIGHT", "RIGHT_TO_LEFT", "VERTICAL", "WEBTOON")

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
    remember(initial) { LockedField(initial) }

/**
 * 编辑系列元数据（对齐 SeriesEditSheet）：
 * 五个分段 tab（通用/标题/标签/链接/分享标签）；每个字段右侧锁定开关，编辑自动锁定；
 * 保存只提交变更或被锁定的字段，其余字段不落到请求里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesEditSheet(
    series: Series,
    values: SeriesEditValues? = null,
    saving: Boolean = false,
    onSave: (SeriesMetadataUpdateDto?) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val initial = remember(values, series) { values ?: SeriesEditValues.fromUiSeries(series) }
    var tab by remember { mutableStateOf(EditTab.General) }

    // 通用
    val summary = rememberLockedField(initial.summary)
    val language = rememberLockedField(initial.language)
    val publisher = rememberLockedField(initial.publisher)
    val ageRating = rememberLockedField(initial.ageRating)
    val totalBookCount = rememberLockedField(initial.totalBookCount)
    var status by remember(initial) { mutableStateOf(initial.status) }
    var direction by remember(initial) { mutableStateOf(initial.readingDirection) }
    // 标题
    val title = rememberLockedField(initial.title)
    val titleSort = rememberLockedField(initial.titleSort)
    val alternateTitles = remember(initial) { initial.alternateTitles.toMutableStateList() }
    var alternateTitlesLocked by remember(initial) { mutableStateOf(false) }
    // 标签 / 链接 / 分享标签
    val genres = remember(initial) { initial.genres.toMutableStateList() }
    var genresLocked by remember(initial) { mutableStateOf(false) }
    val tags = remember(initial) { initial.tags.toMutableStateList() }
    var tagsLocked by remember(initial) { mutableStateOf(false) }
    val links = remember(initial) { initial.links.toMutableStateList() }
    var linksLocked by remember(initial) { mutableStateOf(false) }
    val sharingLabels = remember(initial) { initial.sharingLabels.toMutableStateList() }
    var sharingLabelsLocked by remember(initial) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
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
                IconButton(onClick = onDismiss, enabled = !saving) {
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
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    EditTab.General -> {
                        LockableTextField("简介", summary, singleLine = false)
                        ChoiceRow(
                            label = "连载状态",
                            options = seriesStatusOptions,
                            selected = status,
                            optionLabel = SeriesEditValues::statusLabel,
                            onSelect = { status = it },
                        )
                        ChoiceRow(
                            label = "阅读方向",
                            options = seriesDirectionOptions,
                            selected = direction,
                            optionLabel = SeriesEditValues::directionLabel,
                            onSelect = { direction = it },
                        )
                        LockableTextField("语言", language)
                        LockableTextField("出版社", publisher)
                        LockableTextField("年龄分级", ageRating)
                        LockableTextField("总册数", totalBookCount)
                    }

                    EditTab.Title -> {
                        LockableTextField("标题", title)
                        LockableTextField("排序标题", titleSort)
                        TextSection(
                            title = "别称",
                            locked = alternateTitlesLocked,
                            onToggleLock = { alternateTitlesLocked = !alternateTitlesLocked },
                            entries = alternateTitles.map { "${it.first} · ${it.second}" },
                            onRemove = { index -> alternateTitles.removeAt(index) },
                            addLabel = "添加别称",
                            onAdd = { label, value -> alternateTitles.add(label to value) },
                            primaryLabel = "别称",
                            secondaryLabel = "标题",
                        )
                    }

                    EditTab.Tags -> {
                        TextSection(
                            title = "分类",
                            locked = genresLocked,
                            onToggleLock = { genresLocked = !genresLocked },
                            entries = genres,
                            onRemove = { index -> genres.removeAt(index) },
                            addLabel = "添加分类",
                            onAdd = { value, _ -> genres.add(value) },
                        )
                        TextSection(
                            title = "标签",
                            locked = tagsLocked,
                            onToggleLock = { tagsLocked = !tagsLocked },
                            entries = tags,
                            onRemove = { index -> tags.removeAt(index) },
                            addLabel = "添加标签",
                            onAdd = { value, _ -> tags.add(value) },
                        )
                    }

                    EditTab.Links -> {
                        TextSection(
                            title = "链接",
                            locked = linksLocked,
                            onToggleLock = { linksLocked = !linksLocked },
                            entries = links.map { "${it.first} · ${it.second}" },
                            onRemove = { index -> links.removeAt(index) },
                            addLabel = "添加链接",
                            onAdd = { label, value -> links.add(label to value) },
                            primaryLabel = "链接名称",
                            secondaryLabel = "链接地址",
                            primaryPlaceholder = "维基百科",
                            secondaryPlaceholder = "https://",
                        )
                    }

                    EditTab.Sharing -> {
                        TextSection(
                            title = "分享标签",
                            locked = sharingLabelsLocked,
                            onToggleLock = { sharingLabelsLocked = !sharingLabelsLocked },
                            entries = sharingLabels,
                            onRemove = { index -> sharingLabels.removeAt(index) },
                            addLabel = "添加分享标签",
                            onAdd = { value, _ -> sharingLabels.add(value) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        onSave(
                            buildSeriesUpdateDto(
                                initial = initial,
                                title = title.value,
                                titleLocked = title.locked,
                                titleSort = titleSort.value,
                                titleSortLocked = titleSort.locked,
                                summary = summary.value,
                                summaryLocked = summary.locked,
                                publisher = publisher.value,
                                publisherLocked = publisher.locked,
                                language = language.value,
                                languageLocked = language.locked,
                                ageRating = ageRating.value,
                                ageRatingLocked = ageRating.locked,
                                totalBookCount = totalBookCount.value,
                                totalBookCountLocked = totalBookCount.locked,
                                status = status,
                                statusLocked = status != initial.status,
                                readingDirection = direction,
                                readingDirectionLocked = direction != initial.readingDirection,
                                genres = genres,
                                genresLocked = genresLocked,
                                tags = tags,
                                tagsLocked = tagsLocked,
                                links = links,
                                linksLocked = linksLocked,
                                alternateTitles = alternateTitles,
                                alternateTitlesLocked = alternateTitlesLocked,
                                sharingLabels = sharingLabels,
                                sharingLabelsLocked = sharingLabelsLocked,
                            ),
                        )
                    },
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("保存")
                    }
                }
            }
        }
    }
}

/** 只包含改动/被锁定字段的更新体；没有改动时返回 null（不发请求）。 */
internal fun buildSeriesUpdateDto(
    initial: SeriesEditValues,
    title: String,
    titleLocked: Boolean,
    titleSort: String,
    titleSortLocked: Boolean,
    summary: String,
    summaryLocked: Boolean,
    publisher: String,
    publisherLocked: Boolean,
    language: String,
    languageLocked: Boolean,
    ageRating: String,
    ageRatingLocked: Boolean,
    totalBookCount: String,
    totalBookCountLocked: Boolean,
    status: String,
    statusLocked: Boolean,
    readingDirection: String,
    readingDirectionLocked: Boolean,
    genres: List<String>,
    genresLocked: Boolean,
    tags: List<String>,
    tagsLocked: Boolean,
    links: List<Pair<String, String>>,
    linksLocked: Boolean,
    alternateTitles: List<Pair<String, String>>,
    alternateTitlesLocked: Boolean,
    sharingLabels: List<String>,
    sharingLabelsLocked: Boolean,
): SeriesMetadataUpdateDto? {
    val genresChanged = genres.toSet() != initial.genres.toSet() || genresLocked
    val tagsChanged = tags.toSet() != initial.tags.toSet() || tagsLocked
    val linksChanged = links != initial.links || linksLocked
    val alternatesChanged = alternateTitles != initial.alternateTitles || alternateTitlesLocked
    val sharingChanged = sharingLabels.toSet() != initial.sharingLabels.toSet() || sharingLabelsLocked
    val titleChanged = title != initial.title || titleLocked
    val titleSortChanged = titleSort != initial.titleSort || titleSortLocked
    val summaryChanged = summary != initial.summary || summaryLocked
    val publisherChanged = publisher != initial.publisher || publisherLocked
    val languageChanged = language != initial.language || languageLocked
    val ageRatingChanged = ageRating != initial.ageRating || ageRatingLocked
    val totalBookCountChanged = totalBookCount != initial.totalBookCount || totalBookCountLocked

    if (!(genresChanged || tagsChanged || linksChanged || alternatesChanged || sharingChanged ||
            titleChanged || titleSortChanged || summaryChanged || publisherChanged ||
            languageChanged || ageRatingChanged || totalBookCountChanged ||
            statusLocked || readingDirectionLocked)
    ) {
        return null
    }

    return SeriesMetadataUpdateDto(
        title = title.takeIf { titleChanged },
        titleLock = true.takeIf { titleLocked },
        titleSort = titleSort.takeIf { titleSortChanged },
        titleSortLock = true.takeIf { titleSortLocked },
        summary = summary.takeIf { summaryChanged },
        summaryLock = true.takeIf { summaryLocked },
        publisher = publisher.takeIf { publisherChanged },
        publisherLock = true.takeIf { publisherLocked },
        language = language.takeIf { languageChanged },
        languageLock = true.takeIf { languageLocked },
        ageRating = ageRating.trim().toIntOrNull().takeIf { ageRatingChanged },
        ageRatingLock = true.takeIf { ageRatingLocked },
        totalBookCount = totalBookCount.trim().toIntOrNull().takeIf { totalBookCountChanged },
        totalBookCountLock = true.takeIf { totalBookCountLocked },
        status = SeriesMetadataUpdateDto.Status.entries.firstOrNull { it.value == status }
            .takeIf { statusLocked },
        statusLock = true.takeIf { statusLocked },
        readingDirection = SeriesMetadataUpdateDto.ReadingDirection.entries
            .firstOrNull { it.value == readingDirection }
            .takeIf { readingDirectionLocked },
        readingDirectionLock = true.takeIf { readingDirectionLocked },
        genres = genres.toSet().takeIf { genresChanged },
        genresLock = true.takeIf { genresLocked },
        tags = tags.toSet().takeIf { tagsChanged },
        tagsLock = true.takeIf { tagsLocked },
        links = links
            .map { WebLinkUpdateDto(label = it.first, url = it.second.ifBlank { null }) }
            .takeIf { linksChanged },
        linksLock = true.takeIf { linksLocked },
        alternateTitles = alternateTitles
            .map { AlternateTitleUpdateDto(label = it.first, title = it.second) }
            .takeIf { alternatesChanged },
        alternateTitlesLock = true.takeIf { alternateTitlesLocked },
        sharingLabels = sharingLabels.toSet().takeIf { sharingChanged },
        sharingLabelsLock = true.takeIf { sharingLabelsLocked },
    )
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
        LockToggle(locked = field.locked, onToggle = { field.locked = !field.locked })
    }
}

@Composable
private fun LockToggle(locked: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (locked) "已锁定" else "未锁定",
            tint = if (locked) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 枚举型字段：标签 + 一组可选 chip（状态/阅读方向，值即服务端枚举 token）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    optionLabel: (String) -> String,
    onSelect: (String) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(optionLabel(option)) },
            )
        }
    }
}

/** 列表段：行 + 删除 + 添加（对齐 Genres/Tags/Links/SharingLabels/AlternateTitles 段）。 */
@Composable
private fun TextSection(
    title: String,
    locked: Boolean,
    onToggleLock: () -> Unit,
    entries: List<String>,
    onRemove: (Int) -> Unit,
    addLabel: String,
    onAdd: (String, String) -> Unit,
    primaryLabel: String? = null,
    secondaryLabel: String? = null,
    primaryPlaceholder: String = "",
    secondaryPlaceholder: String = "",
) {
    var primary by remember { mutableStateOf("") }
    var secondary by remember { mutableStateOf("") }
    val needsValue = primaryLabel != null
    val canAdd = primary.isNotBlank() && (!needsValue || secondary.isNotBlank())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        LockToggle(locked = locked, onToggle = onToggleLock)
    }
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
    if (primaryLabel == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = primary,
                onValueChange = { primary = it },
                label = { Text(title) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        OutlinedTextField(
            value = primary,
            onValueChange = { primary = it },
            label = { Text(primaryLabel) },
            placeholder = primaryPlaceholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secondary,
            onValueChange = { secondary = it },
            label = { Text(secondaryLabel ?: "") },
            placeholder = secondaryPlaceholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            addLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                onAdd(primary.trim(), secondary.trim())
                primary = ""
                secondary = ""
            },
            enabled = canAdd,
        ) {
            Icon(Icons.Filled.Add, contentDescription = addLabel, modifier = Modifier.size(18.dp))
        }
    }
}
