package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import kotlin.math.roundToInt

/**
 * 阅读器设置面板（对齐原作 Views/Sheets/Divina/ReaderSettingsSheet.swift 的分组与选项）：
 * 外观 / 翻页 / 手势 / 性能 / 图像处理。所有项都直接写入 [DivinaSettings] 并持久化，改完即生效。
 *
 * 与原作差异（记录在 README 差异清单）：
 * - 翻页过渡只做「滚动」「覆盖」；「仿真卷页」为 iOS PageCurl 专有，Android 不实现
 * - 面板模式（Panel Mode）、Waifu2x 超分、Live Text 按项目红线不做
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: DivinaSettings,
    direction: ReadingDirection,
    onUpdate: ((DivinaSettings) -> DivinaSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val isWebtoon = direction == ReadingDirection.Webtoon

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "阅读器设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SettingsGroupTitle("外观")
            SettingsChoiceRow(
                label = "阅读器背景",
                options = ReaderBackground.entries,
                selected = settings.background,
                optionLabel = { it.label },
                onSelect = { value -> onUpdate { it.copy(background = value) } },
            )
            SettingsToggleRow("显示页码", settings.showPageNumber) { value ->
                onUpdate { it.copy(showPageNumber = value) }
            }
            SettingsToggleRow("页面阴影", settings.showPageShadow) { value ->
                onUpdate { it.copy(showPageShadow = value) }
            }
            SettingsToggleRow("控件渐变背景", settings.controlsGradient) { value ->
                onUpdate { it.copy(controlsGradient = value) }
            }
            SettingsToggleRow("阅读时显示进度条", settings.showProgressBar) { value ->
                onUpdate { it.copy(showProgressBar = value) }
            }
            if (isWebtoon) {
                SettingsSliderRow(
                    label = "条漫页面宽度",
                    value = settings.webtoonPageWidthPercentage,
                    valueRange = 50f..100f,
                    steps = 9,
                    display = "${settings.webtoonPageWidthPercentage.roundToInt()}%",
                    onValueChange = { value ->
                        onUpdate { it.copy(webtoonPageWidthPercentage = (value / 5f).roundToInt() * 5f) }
                    },
                )
            }

            SettingsGroupTitle("翻页")
            if (!isWebtoon) {
                SettingsChoiceRow(
                    label = "翻页动画样式",
                    options = PageTransitionStyle.entries,
                    selected = settings.pageTransition,
                    optionLabel = { it.label },
                    onSelect = { value -> onUpdate { it.copy(pageTransition = value) } },
                )
            }
            SettingsToggleRow("点按翻页动画", settings.animateTapTurns) { value ->
                onUpdate { it.copy(animateTapTurns = value) }
            }
            SettingsChoiceRow(
                label = "点按区域布局",
                options = TapZoneMode.entries,
                selected = settings.tapZoneMode,
                optionLabel = { it.label },
                onSelect = { value -> onUpdate { it.copy(tapZoneMode = value) } },
            )
            if (!settings.tapZoneMode.isDisabled) {
                SettingsChoiceRow(
                    label = "点按区域镜像",
                    options = TapZoneInversion.entries,
                    selected = settings.tapZoneInversion,
                    optionLabel = { it.label },
                    onSelect = { value -> onUpdate { it.copy(tapZoneInversion = value) } },
                )
                SettingsToggleRow("显示点按区域提示", settings.showTapZoneHints) { value ->
                    onUpdate { it.copy(showTapZoneHints = value) }
                }
                if (isWebtoon) {
                    SettingsSliderRow(
                        label = "条漫点按滚动高度",
                        value = settings.webtoonTapScrollPercentage,
                        valueRange = 25f..100f,
                        steps = 14,
                        display = "${settings.webtoonTapScrollPercentage.roundToInt()}%",
                        onValueChange = { value ->
                            onUpdate { it.copy(webtoonTapScrollPercentage = (value / 5f).roundToInt() * 5f) }
                        },
                    )
                }
            }

            SettingsGroupTitle("手势")
            SettingsChoiceRow(
                label = "双击动作",
                options = DoubleTapZoomMode.entries,
                selected = settings.doubleTapZoomMode,
                optionLabel = { it.label },
                onSelect = { value -> onUpdate { it.copy(doubleTapZoomMode = value) } },
            )
            if (settings.doubleTapZoomMode == DoubleTapZoomMode.Enabled) {
                SettingsSliderRow(
                    label = "双击缩放倍率",
                    value = settings.doubleTapZoomScale,
                    valueRange = 1f..8f,
                    steps = 13,
                    display = "%.1fx".format(settings.doubleTapZoomScale),
                    onValueChange = { value ->
                        onUpdate { it.copy(doubleTapZoomScale = (value * 2f).roundToInt() / 2f) }
                    },
                )
            }
            SettingsChoiceRow(
                label = "长按动作",
                options = LongPressAction.entries,
                selected = settings.longPressAction,
                optionLabel = { it.label },
                onSelect = { value -> onUpdate { it.copy(longPressAction = value) } },
            )

            SettingsGroupTitle("性能")
            SettingsChoiceRow(
                label = "图片预加载",
                options = PreloadProfile.entries,
                selected = settings.preloadProfile,
                optionLabel = { it.label },
                onSelect = { value -> onUpdate { it.copy(preloadProfile = value) } },
            )
            SettingsHintText(
                when (settings.preloadProfile) {
                    PreloadProfile.LowMemory -> "解码更少的邻近页面以降低内存占用，翻页时可能更常等待加载。"
                    PreloadProfile.Balanced -> "使用默认预加载窗口，兼顾内存占用与翻页流畅度。"
                    PreloadProfile.FastPageTurns -> "预解码更多邻近页面以加快翻页，代价是更高的内存占用。"
                },
            )

            SettingsGroupTitle("图像处理")
            SettingsChoiceRow(
                label = "裁边模式",
                options = BorderCropMode.entries,
                selected = settings.borderCrop,
                optionLabel = { it.label },
                onSelect = { value -> onUpdate { it.copy(borderCrop = value) } },
            )
            SettingsHintText("裁掉页面四周与底色相同的留白；关闭后按原图显示。")
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsHintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun <T> SettingsChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option), style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                display,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}
