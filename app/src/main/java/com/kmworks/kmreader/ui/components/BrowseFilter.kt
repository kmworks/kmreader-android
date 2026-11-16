package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.data.ActiveFilter
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.BookSortFields
import com.kmworks.kmreader.data.ReadStatus
import com.kmworks.kmreader.data.SavedFilter
import com.kmworks.kmreader.data.SampleSavedFilters
import com.kmworks.kmreader.data.TriState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/** 布局模式（对齐 BrowseLayoutMode：list/grid/largeGrid），循环切换。 */
enum class BrowseLayout(val label: String) {
    List("列表"), Grid("网格"), LargeGrid("大网格");

    val next: BrowseLayout
        get() = when (this) {
            List -> Grid
            Grid -> LargeGrid
            LargeGrid -> List
        }
}

/**
 * 逐页持久化的布局状态（对齐 iOS 逐页布局记忆；存 AppSettingsStore.browseLayoutOverrides）。
 * 返回当前布局与切换器；写入前先本地生效，持久化回流后保持一致。
 */
@Composable
fun rememberPersistentBrowseLayout(
    page: String,
    default: BrowseLayout,
): Pair<BrowseLayout, (BrowseLayout) -> Unit> {
    val context = LocalContext.current
    val appSettings = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ImageLoaderEntryPoint::class.java,
        ).appSettingsStore()
    }
    val scope = rememberCoroutineScope()
    val stored by appSettings.browseLayout(page).collectAsState(initial = null)
    // 用户本轮的即时覆盖（持久化写入回流前的即时反馈）
    var local by remember(page) { mutableStateOf<BrowseLayout?>(null) }
    val value = local
        ?: stored?.let { name -> BrowseLayout.entries.firstOrNull { it.name == name } }
        ?: default
    return value to { next ->
        local = next
        scope.launch { appSettings.setBrowseLayout(page, next.name) }
    }
}

/**
 * 筛选 chip 流（对齐原作 BookFilterView 的横向 chip 行）：
 * [布局] [预设] [排序] + 已激活条件 chips；点任意 chip 打开完整面板。
 */
@Composable
fun BrowseFilterChipFlow(
    options: BookBrowseOptions,
    layout: BrowseLayout,
    onLayoutChange: (BrowseLayout) -> Unit,
    onOpenOptions: () -> Unit,
    onApplyPreset: (SavedFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var presetsOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 布局切换
        AssistChip(
            onClick = { onLayoutChange(layout.next) },
            label = { Text(layout.label) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
        )
        // 筛选预设（原作 savedFilter，跨页面复用）
        Column {
            AssistChip(
                onClick = { presetsOpen = true },
                label = { Text("预设") },
                leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
            )
            DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                SampleSavedFilters.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        onClick = {
                            presetsOpen = false
                            onApplyPreset(preset)
                        },
                    )
                }
            }
        }
        // 当前排序（点开完整面板）
        AssistChip(
            onClick = onOpenOptions,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(options.sortField)
                    Icon(
                        if (options.sortAscending) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
            },
        )
        // 已激活条件 chips（负向 = 排除态，用反色变体）
        options.activeFilters.forEach { filter ->
            if (filter.negative) {
                NegativeFilterChip(label = filter.label, onClick = onOpenOptions)
            } else {
                FilterChip(selected = true, onClick = onOpenOptions, label = { Text(filter.label) })
            }
        }
    }
}

/** 排除态 chip：反色（对应原作 .negative 变体）。 */
@Composable
private fun NegativeFilterChip(label: String, onClick: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onClick,
        label = { Text("排除 $label") },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.error,
            selectedLabelColor = MaterialTheme.colorScheme.onError,
        ),
    )
}

/**
 * 筛选与排序面板（对齐原作 BookBrowseOptionsSheet）：
 * 排序字段+方向；阅读状态三态循环；单发/已删除；作者/标签多选+∧∨；存为预设；完成。
 * authorSuggestions/tagSuggestions：Referential 可选值（空列表则不显示建议行）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseOptionsSheet(
    options: BookBrowseOptions,
    onOptionsChange: (BookBrowseOptions) -> Unit,
    onDismiss: () -> Unit,
    onSavePreset: (String, BookBrowseOptions) -> Unit = { _, _ -> },
    authorSuggestions: List<String> = emptyList(),
    tagSuggestions: List<String> = emptyList(),
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // 头部：标题 + 重置 + 关闭（对应 SheetView title/onReset）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "筛选与排序",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOptionsChange(BookBrowseOptions()) }) { Text("重置") }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            SheetSectionTitle("排序")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookSortFields.forEach { field ->
                    FilterChip(
                        selected = options.sortField == field,
                        onClick = { onOptionsChange(options.copy(sortField = field)) },
                        label = { Text(field) },
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = options.sortAscending,
                    onClick = { onOptionsChange(options.copy(sortAscending = true)) },
                    label = { Text("升序") },
                )
                FilterChip(
                    selected = !options.sortAscending,
                    onClick = { onOptionsChange(options.copy(sortAscending = false)) },
                    label = { Text("降序") },
                )
            }

            SheetSectionTitle("阅读状态")
            ReadStatus.entries.forEach { status ->
                val state = options.readStatuses[status] ?: TriState.Off
                TriStateRow(
                    label = status.label,
                    state = state,
                    onCycle = {
                        onOptionsChange(
                            // 对应原作 applyReadStatusToggle：包含互斥，排除可与包含共存
                            options.copy(readStatuses = options.readStatuses.applyReadStatusToggle(status, state.next))
                        )
                    },
                )
            }

            SheetSectionTitle("标记")
            TriStateRow(
                label = "单发",
                state = options.oneshot,
                onCycle = { onOptionsChange(options.copy(oneshot = options.oneshot.next)) },
            )
            TriStateRow(
                label = "已删除",
                state = options.deleted,
                onCycle = { onOptionsChange(options.copy(deleted = options.deleted.next)) },
            )

            SheetSectionTitle("作者")
            MultiValueFilterRow(
                values = options.authors,
                logicAnd = options.authorLogicAnd,
                onRemove = { author ->
                    onOptionsChange(options.copy(authors = options.authors - author))
                },
                onToggleLogic = {
                    onOptionsChange(options.copy(authorLogicAnd = !options.authorLogicAnd))
                },
            )
            SuggestionChipRow(
                suggestions = authorSuggestions.filter { it !in options.authors },
                onPick = { onOptionsChange(options.copy(authors = options.authors + it)) },
            )

            SheetSectionTitle("标签")
            MultiValueFilterRow(
                values = options.tags,
                logicAnd = options.tagLogicAnd,
                onRemove = { tag -> onOptionsChange(options.copy(tags = options.tags - tag)) },
                onToggleLogic = { onOptionsChange(options.copy(tagLogicAnd = !options.tagLogicAnd)) },
            )
            SuggestionChipRow(
                suggestions = tagSuggestions.filter { it !in options.tags },
                onPick = { onOptionsChange(options.copy(tags = options.tags + it)) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            // controls：Save Filter + Done
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text("存为预设")
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("完成") }
            }
        }
    }

    if (showSaveDialog) {
        SaveFilterDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                showSaveDialog = false
                onSavePreset(name, options)
            },
        )
    }
}

@Composable
private fun SheetSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** 三态行：行尾按钮循环 off → ✓ 包含 → ✕ 排除。 */
@Composable
private fun TriStateRow(label: String, state: TriState, onCycle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = when (state) {
                TriState.Off -> MaterialTheme.colorScheme.onSurface
                TriState.Include -> MaterialTheme.colorScheme.onSurface
                TriState.Exclude -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = state != TriState.Off,
            onClick = onCycle,
            label = {
                when (state) {
                    TriState.Off -> Text("关闭")
                    TriState.Include -> Icon(Icons.Filled.Check, contentDescription = "包含")
                    TriState.Exclude -> Icon(Icons.Filled.Close, contentDescription = "排除")
                }
            },
        )
    }
}

/** 多值条件行：值 chips + ∧∨ 逻辑切换（对齐 MetadataFilterSection）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiValueFilterRow(
    values: List<String>,
    logicAnd: Boolean,
    onRemove: (String) -> Unit,
    onToggleLogic: () -> Unit,
) {
    if (values.isEmpty()) {
        Text(
            "未设置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = true,
                    onClick = { onRemove(value) },
                    label = { Text(value) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "移除") },
                )
            }
        }
        TextButton(onClick = onToggleLogic) { Text(if (logicAnd) "∧" else "∨") }
    }
}

/** Referential 可选值建议行：点 chip 把值加入多选条件（已选值不再出现）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChipRow(
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    if (suggestions.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        suggestions.take(MAX_SUGGESTIONS).forEach { value ->
            AssistChip(
                onClick = { onPick(value) },
                label = { Text(value, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

private const val MAX_SUGGESTIONS = 12

/** 存为预设（对齐 SaveFilterSheet）。 */
@Composable
private fun SaveFilterDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存为筛选预设") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("预设名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 互斥规则（对齐 KMReader.applyReadStatusToggle）：包含互斥，排除可共存。 */
private fun Map<ReadStatus, TriState>.applyReadStatusToggle(
    status: ReadStatus,
    next: TriState,
): Map<ReadStatus, TriState> {
    val result = this.toMutableMap()
    result[status] = next
    if (next == TriState.Include) {
        ReadStatus.entries.forEach { other ->
            if (other != status && result[other] == TriState.Include) {
                result[other] = TriState.Off
            }
        }
    }
    return result
}
