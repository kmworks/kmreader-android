package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.CustomFont
import kotlin.math.roundToInt

/** EPUB reader prefs (mirrors EpubReaderSettingsView / EpubOverlayPreferences). */
data class EpubReaderSettings(
    val theme: String = "跟随系统",
    val fontSizeSp: Float = 18f,
    val typeface: String = "出版方默认",
    val fontWeight: Float = 0.4f,
    val margins: Float = 1.0f,
    val lineHeight: Float = 1.4f,
    val flowStyle: String = "分页",
    val columns: String = "单栏",
    val publisherStyles: Boolean = true,
    val animatePageTurns: Boolean = true,
)

/** Theme preset row (mirrors EpubThemePresetDisplayItem). */
private data class EpubThemePresetItem(val name: String, val updatedLabel: String)

private val EpubThemeChoices = listOf("跟随系统", "安静", "米黄", "墨绿")

/** Grayscale swatches per theme; sepia/green mapped to gray (design is monochrome). */
private fun themeSwatch(choice: String): Pair<Color, Color> = when (choice) {
    "安静" -> Color(0xFFFAFAFA) to Color(0xFF1E1E1E)
    "米黄" -> Color(0xFFF0F0F0) to Color(0xFF2A2A2A)
    "墨绿" -> Color(0xFFECECEC) to Color(0xFF202020)
    else -> Color.White to Color.Black
}

/** EPUB settings content (mirrors EpubReaderSettingsView sections). Shared by sheet and defaults page. */
@Composable
fun EpubSettingsContent(
    settings: EpubReaderSettings,
    onSettingsChange: (EpubReaderSettings) -> Unit,
    customFonts: List<CustomFont>,
    onOpenPresets: () -> Unit,
    onOpenCustomFonts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        EpubSectionTitle("主题")
        // 2-col theme grid (mirrors themePicker)
        EpubThemeChoices.chunked(2).forEach { rowChoices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowChoices.forEach { choice ->
                    EpubThemeCell(
                        choice = choice,
                        selected = settings.theme == choice,
                        onClick = { onSettingsChange(settings.copy(theme = choice)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowChoices.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        EpubNavRow(
            title = "主题预设",
            subtitle = "新建 / 编辑 / 删除排版预设",
            onClick = onOpenPresets,
        )

        EpubSectionTitle("字体")
        EpubSliderRow(
            label = "字号",
            value = settings.fontSizeSp,
            valueRange = 12f..32f,
            display = "${settings.fontSizeSp.roundToInt()} sp",
            onValueChange = { onSettingsChange(settings.copy(fontSizeSp = it.roundToInt().toFloat())) },
        )
        EpubSliderRow(
            label = "字重",
            value = settings.fontWeight,
            valueRange = 0f..1f,
            display = "${(settings.fontWeight * 100).roundToInt()}%",
            onValueChange = { onSettingsChange(settings.copy(fontWeight = it)) },
        )
        // Typeface list: publisher default / system / installed custom fonts
        val typefaces = listOf("出版方默认", "系统默认") + customFonts.map { it.name }
        typefaces.forEach { face ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSettingsChange(settings.copy(typeface = face)) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(face, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (settings.typeface == face) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "当前字体",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        EpubNavRow(
            title = "管理自定义字体",
            subtitle = "已安装 ${customFonts.size} 款",
            onClick = onOpenCustomFonts,
        )

        EpubSectionTitle("排版")
        EpubSliderRow(
            label = "页边距",
            value = settings.margins,
            valueRange = 0.25f..2.0f,
            display = "%.2fx".format(settings.margins),
            onValueChange = {
                onSettingsChange(settings.copy(margins = (it * 20).roundToInt() / 20f))
            },
        )
        EpubSliderRow(
            label = "行距",
            value = settings.lineHeight,
            valueRange = 0.5f..2.5f,
            display = "%.1fx".format(settings.lineHeight),
            onValueChange = {
                onSettingsChange(settings.copy(lineHeight = (it * 10).roundToInt() / 10f))
            },
        )
        EpubSwitchRow(
            label = "启用出版方样式",
            checked = settings.publisherStyles,
            onCheckedChange = { onSettingsChange(settings.copy(publisherStyles = it)) },
        )

        EpubSectionTitle("翻页")
        EpubChipRow(
            label = "翻页方式",
            options = listOf("分页", "滚动"),
            selected = settings.flowStyle,
            onSelect = { onSettingsChange(settings.copy(flowStyle = it)) },
        )
        EpubChipRow(
            label = "页面布局",
            options = listOf("单栏", "双栏"),
            selected = settings.columns,
            onSelect = { onSettingsChange(settings.copy(columns = it)) },
        )
        EpubSwitchRow(
            label = "翻页动画",
            checked = settings.animatePageTurns,
            onCheckedChange = { onSettingsChange(settings.copy(animatePageTurns = it)) },
        )
    }
}

/** EPUB settings sheet (mirrors EpubReaderSettingsView presented as a sheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubSettingsSheet(
    settings: EpubReaderSettings,
    onSettingsChange: (EpubReaderSettings) -> Unit,
    customFonts: List<CustomFont>,
    onOpenCustomFonts: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showPresets by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "EPUB 设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            EpubSettingsContent(
                settings = settings,
                onSettingsChange = onSettingsChange,
                customFonts = customFonts,
                onOpenPresets = { showPresets = true },
                onOpenCustomFonts = onOpenCustomFonts,
            )
        }
    }
    if (showPresets) {
        EpubThemePresetsSheet(
            onApply = { preset ->
                onSettingsChange(settings.copy(theme = preset.theme))
                showPresets = false
            },
            onDismiss = { showPresets = false },
        )
    }
}

/** Theme preset manager (mirrors EpubThemePreferencesView: list + apply / rename / delete / create). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubThemePresetsSheet(onApply: (EpubReaderSettings) -> Unit, onDismiss: () -> Unit) {
    val presets = remember {
        listOf(
            "夜间阅读" to "3 天前更新",
            "通勤大字版" to "上周更新",
            "纸质书质感" to "1 个月前更新",
        ).toMutableStateList()
    }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var nextId by remember { mutableIntStateOf(1) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "主题预设",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    presets.add("新预设 ${nextId}" to "刚刚更新")
                    nextId += 1
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存当前为预设")
            }
            Spacer(Modifier.height(12.dp))
            if (presets.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.BookmarkBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "暂无主题预设",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            presets.forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(preset.first, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            preset.second,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onApply(EpubReaderSettings(theme = "安静")) }) {
                        Text("应用")
                    }
                    IconButton(onClick = {
                        renameTarget = preset.first
                        renameText = preset.first
                    }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "重命名",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { presets.remove(preset) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名预设") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val index = presets.indexOfFirst { it.first == target }
                    if (index >= 0 && renameText.isNotBlank()) {
                        presets[index] = renameText to "刚刚更新"
                    }
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EpubThemeCell(
    choice: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (light, dark) = themeSwatch(choice)
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    },
                ),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .background(light),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .background(dark),
            )
        }
        Text(
            choice,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun EpubSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun EpubChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
private fun EpubSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EpubSliderRow(
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
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
private fun EpubNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
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
