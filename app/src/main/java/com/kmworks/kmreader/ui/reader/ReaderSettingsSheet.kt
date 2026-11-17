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
import com.kmworks.kmreader.ui.components.AppModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.R
import kotlin.math.roundToInt

/**
 * Reader settings panel (mirrors the grouping and options of the original Views/Sheets/Divina/ReaderSettingsSheet.swift):
 * Appearance / Page Turn / Gestures / Performance / Image Processing. Every option writes directly into [DivinaSettings],
 * is persisted, and takes effect immediately.
 *
 * Differences from the original (recorded in the README differences list):
 * - Page transitions only offer "Scroll" and "Cover"; "Page Curl" is iOS PageCurl-specific and not implemented on Android
 * - Panel Mode, Waifu2x upscaling, and Live Text are not implemented per project constraints
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

    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                stringResource(R.string.reader_settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SettingsGroupTitle(stringResource(R.string.reader_group_appearance))
            SettingsChoiceRow(
                label = stringResource(R.string.reader_background_title),
                options = ReaderBackground.entries,
                selected = settings.background,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = { value -> onUpdate { it.copy(background = value) } },
            )
            SettingsToggleRow(stringResource(R.string.reader_show_page_number), settings.showPageNumber) { value ->
                onUpdate { it.copy(showPageNumber = value) }
            }
            SettingsToggleRow(stringResource(R.string.reader_show_page_shadow), settings.showPageShadow) { value ->
                onUpdate { it.copy(showPageShadow = value) }
            }
            SettingsToggleRow(stringResource(R.string.reader_controls_gradient), settings.controlsGradient) { value ->
                onUpdate { it.copy(controlsGradient = value) }
            }
            SettingsToggleRow(stringResource(R.string.reader_show_progress_bar), settings.showProgressBar) { value ->
                onUpdate { it.copy(showProgressBar = value) }
            }
            // Mirrors the original ReaderSettingsSheet.keepScreenAwakeWhileReading (default false)
            SettingsToggleRow(stringResource(R.string.reader_keep_screen_awake), settings.keepScreenAwake) { value ->
                onUpdate { it.copy(keepScreenAwake = value) }
            }
            if (isWebtoon) {
                SettingsSliderRow(
                    label = stringResource(R.string.reader_webtoon_page_width),
                    value = settings.webtoonPageWidthPercentage,
                    valueRange = 50f..100f,
                    steps = 9,
                    display = "${settings.webtoonPageWidthPercentage.roundToInt()}%",
                    onValueChange = { value ->
                        onUpdate { it.copy(webtoonPageWidthPercentage = (value / 5f).roundToInt() * 5f) }
                    },
                )
            }

            SettingsGroupTitle(stringResource(R.string.reader_group_page_turn))
            if (!isWebtoon) {
                SettingsChoiceRow(
                    label = stringResource(R.string.reader_transition_title),
                    options = PageTransitionStyle.entries,
                    selected = settings.pageTransition,
                    optionLabel = { stringResource(it.labelRes) },
                    onSelect = { value -> onUpdate { it.copy(pageTransition = value) } },
                )
            }
            SettingsToggleRow(stringResource(R.string.reader_animate_tap_turns), settings.animateTapTurns) { value ->
                onUpdate { it.copy(animateTapTurns = value) }
            }
            SettingsChoiceRow(
                label = stringResource(R.string.reader_tap_zone_layout),
                options = TapZoneMode.entries,
                selected = settings.tapZoneMode,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = { value -> onUpdate { it.copy(tapZoneMode = value) } },
            )
            if (!settings.tapZoneMode.isDisabled) {
                SettingsChoiceRow(
                    label = stringResource(R.string.reader_tap_zone_inversion),
                    options = TapZoneInversion.entries,
                    selected = settings.tapZoneInversion,
                    optionLabel = { stringResource(it.labelRes) },
                    onSelect = { value -> onUpdate { it.copy(tapZoneInversion = value) } },
                )
                SettingsToggleRow(stringResource(R.string.reader_show_tap_zone_hints), settings.showTapZoneHints) { value ->
                    onUpdate { it.copy(showTapZoneHints = value) }
                }
                if (isWebtoon) {
                    SettingsSliderRow(
                        label = stringResource(R.string.reader_webtoon_tap_scroll_height),
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

            SettingsGroupTitle(stringResource(R.string.reader_group_gestures))
            SettingsChoiceRow(
                label = stringResource(R.string.reader_double_tap_action),
                options = DoubleTapZoomMode.entries,
                selected = settings.doubleTapZoomMode,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = { value -> onUpdate { it.copy(doubleTapZoomMode = value) } },
            )
            if (settings.doubleTapZoomMode == DoubleTapZoomMode.Enabled) {
                SettingsSliderRow(
                    label = stringResource(R.string.reader_double_tap_zoom_scale),
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
                label = stringResource(R.string.reader_long_press_action),
                options = LongPressAction.entries,
                selected = settings.longPressAction,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = { value -> onUpdate { it.copy(longPressAction = value) } },
            )

            SettingsGroupTitle(stringResource(R.string.reader_group_performance))
            SettingsChoiceRow(
                label = stringResource(R.string.reader_preload_title),
                options = PreloadProfile.entries,
                selected = settings.preloadProfile,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = { value -> onUpdate { it.copy(preloadProfile = value) } },
            )
            SettingsHintText(
                when (settings.preloadProfile) {
                    PreloadProfile.LowMemory -> stringResource(R.string.reader_preload_hint_low_memory)
                    PreloadProfile.Balanced -> stringResource(R.string.reader_preload_hint_balanced)
                    PreloadProfile.FastPageTurns -> stringResource(R.string.reader_preload_hint_fast_page_turns)
                },
            )

            SettingsGroupTitle(stringResource(R.string.reader_group_image_processing))
            SettingsChoiceRow(
                label = stringResource(R.string.reader_crop_title),
                options = BorderCropMode.entries,
                selected = settings.borderCrop,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = { value -> onUpdate { it.copy(borderCrop = value) } },
            )
            SettingsHintText(stringResource(R.string.reader_crop_hint))
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
    optionLabel: @Composable (T) -> String,
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
