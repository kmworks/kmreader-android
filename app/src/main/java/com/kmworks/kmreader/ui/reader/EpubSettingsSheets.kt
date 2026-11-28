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
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import androidx.compose.material3.DropdownMenuItem
import com.kmworks.kmreader.core.reader.EpubOverlayItem
import com.kmworks.kmreader.core.reader.EpubOverlayPreferences
import com.kmworks.kmreader.core.reader.EpubThemePreset
import com.kmworks.kmreader.core.reader.EpubThemePresetStore
import com.kmworks.kmreader.data.CustomFont
import com.kmworks.kmreader.ui.components.KmDropdownMenu
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** EPUB reader prefs (mirrors EpubReaderSettingsView / EpubOverlayPreferences). */
data class EpubReaderSettings(
    val theme: String = EPUB_THEME_SYSTEM,
    val fontSizeSp: Float = 18f,
    val typeface: String = EPUB_TYPEFACE_PUBLISHER,
    val fontWeight: Float = 0.4f,
    val margins: Float = 1.0f,
    val lineHeight: Float = 1.4f,
    val flowStyle: String = EPUB_FLOW_PAGED,
    val columns: String = EPUB_COLUMNS_AUTO,
    val publisherStyles: Boolean = true,
    val animatePageTurns: Boolean = true,
)

/** Stable storage keys for [EpubReaderSettings.theme] (persisted raw in EpubThemePresetStore). */
const val EPUB_THEME_SYSTEM = "system"
const val EPUB_THEME_QUIET = "quiet"
const val EPUB_THEME_SEPIA = "sepia"
const val EPUB_THEME_INK_GREEN = "inkGreen"

/** Stable storage keys for [EpubReaderSettings.typeface]; custom fonts are stored by name. */
const val EPUB_TYPEFACE_PUBLISHER = "publisher"
const val EPUB_TYPEFACE_SYSTEM = "system"

/** Stable storage keys for [EpubReaderSettings.flowStyle]. */
const val EPUB_FLOW_PAGED = "paged"
const val EPUB_FLOW_SCROLLED = "scrolled"

/** Stable storage keys for [EpubReaderSettings.columns]. */
const val EPUB_COLUMNS_AUTO = "auto"
const val EPUB_COLUMNS_SINGLE = "single"
const val EPUB_COLUMNS_DUAL = "dual"

/** Maps legacy Chinese persisted values onto the stable keys; unknown values pass through.
 *  The legacy literals are written as unicode escapes so the source stays ASCII-only. */
fun normalizeEpubTheme(value: String): String = when (value) {
    "\u5b89\u9759" -> EPUB_THEME_QUIET
    "\u7c73\u9ec4" -> EPUB_THEME_SEPIA
    "\u58a8\u7eff" -> EPUB_THEME_INK_GREEN
    "\u8ddf\u968f\u7cfb\u7edf" -> EPUB_THEME_SYSTEM
    else -> value
}

fun normalizeEpubTypeface(value: String): String = when (value) {
    "\u51fa\u7248\u65b9\u9ed8\u8ba4" -> EPUB_TYPEFACE_PUBLISHER
    "\u7cfb\u7edf\u9ed8\u8ba4" -> EPUB_TYPEFACE_SYSTEM
    else -> value
}

fun normalizeEpubFlowStyle(value: String): String = when (value) {
    "\u5206\u9875" -> EPUB_FLOW_PAGED
    "\u6eda\u52a8" -> EPUB_FLOW_SCROLLED
    else -> value
}

fun normalizeEpubColumns(value: String): String = when (value) {
    "\u81ea\u52a8" -> EPUB_COLUMNS_AUTO
    "\u5355\u680f" -> EPUB_COLUMNS_SINGLE
    "\u53cc\u680f" -> EPUB_COLUMNS_DUAL
    else -> value
}

@StringRes
fun epubThemeLabelRes(value: String): Int = when (normalizeEpubTheme(value)) {
    EPUB_THEME_QUIET -> R.string.epub_theme_quiet
    EPUB_THEME_SEPIA -> R.string.epub_theme_sepia
    EPUB_THEME_INK_GREEN -> R.string.epub_theme_ink_green
    else -> R.string.epub_theme_system
}

@StringRes
fun epubTypefaceLabelRes(value: String): Int = when (normalizeEpubTypeface(value)) {
    EPUB_TYPEFACE_SYSTEM -> R.string.epub_typeface_system
    else -> R.string.epub_typeface_publisher
}

@StringRes
fun epubFlowStyleLabelRes(value: String): Int =
    if (normalizeEpubFlowStyle(value) == EPUB_FLOW_SCROLLED) R.string.epub_flow_scrolled else R.string.epub_flow_paged

@StringRes
fun epubColumnsLabelRes(value: String): Int = when (normalizeEpubColumns(value)) {
    EPUB_COLUMNS_DUAL -> R.string.epub_columns_dual
    EPUB_COLUMNS_AUTO -> R.string.epub_columns_auto
    else -> R.string.epub_columns_single
}

@StringRes
fun epubOverlayItemLabelRes(item: EpubOverlayItem): Int = when (item) {
    EpubOverlayItem.NONE -> R.string.epub_overlay_item_none
    EpubOverlayItem.BOOK_TITLE -> R.string.epub_overlay_item_book_title
    EpubOverlayItem.CHAPTER_TITLE -> R.string.epub_overlay_item_chapter_title
    EpubOverlayItem.BOOK_PROGRESS_PERCENT -> R.string.epub_overlay_item_book_progress
    EpubOverlayItem.BOOK_REMAINING_PERCENT -> R.string.epub_overlay_item_book_remaining
    EpubOverlayItem.CHAPTER_PROGRESS_PERCENT -> R.string.epub_overlay_item_chapter_progress
    EpubOverlayItem.CHAPTER_REMAINING -> R.string.epub_overlay_item_chapter_remaining
    EpubOverlayItem.CHAPTER_POSITION -> R.string.epub_overlay_item_chapter_position
    EpubOverlayItem.BOOK_POSITION -> R.string.epub_overlay_item_book_position
}

/** Theme preset row (mirrors EpubThemePresetDisplayItem). */
private fun EpubThemePreset.toSettings(): EpubReaderSettings = EpubReaderSettings(
    theme = theme,
    fontSizeSp = fontSizeSp,
    typeface = typeface,
    fontWeight = fontWeight,
    margins = margins,
    lineHeight = lineHeight,
    flowStyle = flowStyle,
    columns = columns,
    publisherStyles = publisherStyles,
    animatePageTurns = animatePageTurns,
)

private fun EpubReaderSettings.toPreset(name: String): EpubThemePreset = EpubThemePreset(
    name = name,
    theme = theme,
    fontSizeSp = fontSizeSp,
    typeface = typeface,
    fontWeight = fontWeight,
    margins = margins,
    lineHeight = lineHeight,
    flowStyle = flowStyle,
    columns = columns,
    publisherStyles = publisherStyles,
    animatePageTurns = animatePageTurns,
)

/** Relative "updated" label (mirrors the "3 days ago" style of the mock data). */
@Composable
private fun presetUpdatedLabel(updatedAt: Long): String {
    if (updatedAt <= 0L) return ""
    val days = ((System.currentTimeMillis() - updatedAt) / 86_400_000L).coerceAtLeast(0L)
    return when {
        days < 1L -> stringResource(R.string.epub_preset_updated_now)
        days < 7L -> pluralStringResource(R.plurals.epub_preset_updated_days, days.toInt(), days)
        days < 30L -> pluralStringResource(R.plurals.epub_preset_updated_weeks, (days / 7).toInt(), days / 7)
        days < 365L -> pluralStringResource(R.plurals.epub_preset_updated_months, (days / 30).toInt(), days / 30)
        else -> pluralStringResource(R.plurals.epub_preset_updated_years, (days / 365).toInt(), days / 365)
    }
}

/** Theme preset state (persisted via EpubThemePresetStore). */
@HiltViewModel
class EpubThemePresetsViewModel @Inject constructor(
    private val store: EpubThemePresetStore,
) : ViewModel() {

    private val _presets = MutableStateFlow<List<EpubThemePreset>>(emptyList())
    val presets: StateFlow<List<EpubThemePreset>> = _presets.asStateFlow()

    init {
        viewModelScope.launch { store.presets.collect { _presets.value = it } }
    }

    fun save(preset: EpubThemePreset) {
        viewModelScope.launch { store.savePreset(preset) }
    }

    fun rename(oldName: String, newName: String) {
        viewModelScope.launch { store.renamePreset(oldName, newName) }
    }

    fun delete(name: String) {
        viewModelScope.launch { store.deletePreset(name) }
    }
}

private val EpubThemeChoices = listOf(EPUB_THEME_SYSTEM, EPUB_THEME_QUIET, EPUB_THEME_SEPIA, EPUB_THEME_INK_GREEN)

/** Grayscale swatches per theme; sepia/green mapped to gray (design is monochrome). */
private fun themeSwatch(choice: String): Pair<Color, Color> = when (choice) {
    EPUB_THEME_QUIET -> Color(0xFFFAFAFA) to Color(0xFF1E1E1E)
    EPUB_THEME_SEPIA -> Color(0xFFF0F0F0) to Color(0xFF2A2A2A)
    EPUB_THEME_INK_GREEN -> Color(0xFFECECEC) to Color(0xFF202020)
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
    overlay: EpubOverlayPreferences,
    onOverlayChange: (EpubOverlayPreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        EpubSectionTitle(stringResource(R.string.epub_section_theme))
        // 2-col theme grid (mirrors themePicker)
        EpubThemeChoices.chunked(2).forEach { rowChoices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowChoices.forEach { choice ->
                    EpubThemeCell(
                        choice = choice,
                        selected = normalizeEpubTheme(settings.theme) == choice,
                        onClick = { onSettingsChange(settings.copy(theme = choice)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowChoices.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        EpubNavRow(
            title = stringResource(R.string.epub_theme_presets),
            subtitle = stringResource(R.string.epub_theme_presets_subtitle),
            onClick = onOpenPresets,
        )

        EpubSectionTitle(stringResource(R.string.epub_section_font))
        EpubSliderRow(
            label = stringResource(R.string.epub_font_size),
            value = settings.fontSizeSp,
            valueRange = 12f..32f,
            display = "${settings.fontSizeSp.roundToInt()} sp",
            onValueChange = { onSettingsChange(settings.copy(fontSizeSp = it.roundToInt().toFloat())) },
        )
        EpubSliderRow(
            label = stringResource(R.string.epub_font_weight),
            value = settings.fontWeight,
            valueRange = 0f..1f,
            display = "${(settings.fontWeight * 100).roundToInt()}%",
            onValueChange = { onSettingsChange(settings.copy(fontWeight = it)) },
        )
        // Typeface list: publisher default / system / installed custom fonts
        val typefaces = listOf(EPUB_TYPEFACE_PUBLISHER, EPUB_TYPEFACE_SYSTEM) + customFonts.map { it.name }
        typefaces.forEach { face ->
            val faceLabel = if (face == EPUB_TYPEFACE_PUBLISHER || face == EPUB_TYPEFACE_SYSTEM) {
                stringResource(epubTypefaceLabelRes(face))
            } else {
                face
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSettingsChange(settings.copy(typeface = face)) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(faceLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (normalizeEpubTypeface(settings.typeface) == face) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.epub_current_font),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        EpubNavRow(
            title = stringResource(R.string.fonts_manage),
            subtitle = stringResource(R.string.fonts_installed_count, customFonts.size),
            onClick = onOpenCustomFonts,
        )

        EpubSectionTitle(stringResource(R.string.epub_section_layout))
        EpubSliderRow(
            label = stringResource(R.string.epub_page_margins),
            value = settings.margins,
            valueRange = 0.25f..2.0f,
            display = "%.2fx".format(settings.margins),
            onValueChange = {
                onSettingsChange(settings.copy(margins = (it * 20).roundToInt() / 20f))
            },
        )
        EpubSliderRow(
            label = stringResource(R.string.epub_line_height),
            value = settings.lineHeight,
            valueRange = 0.5f..2.5f,
            display = "%.1fx".format(settings.lineHeight),
            onValueChange = {
                onSettingsChange(settings.copy(lineHeight = (it * 10).roundToInt() / 10f))
            },
        )
        EpubSwitchRow(
            label = stringResource(R.string.epub_publisher_styles),
            checked = settings.publisherStyles,
            onCheckedChange = { onSettingsChange(settings.copy(publisherStyles = it)) },
        )

        EpubSectionTitle(stringResource(R.string.reader_group_page_turn))
        EpubChipRow(
            label = stringResource(R.string.epub_flow_style),
            options = listOf(EPUB_FLOW_PAGED, EPUB_FLOW_SCROLLED),
            selected = normalizeEpubFlowStyle(settings.flowStyle),
            optionLabel = { stringResource(epubFlowStyleLabelRes(it)) },
            onSelect = { onSettingsChange(settings.copy(flowStyle = it)) },
        )
        EpubChipRow(
            label = stringResource(R.string.reader_menu_page_layout),
            options = listOf(EPUB_COLUMNS_AUTO, EPUB_COLUMNS_SINGLE, EPUB_COLUMNS_DUAL),
            selected = normalizeEpubColumns(settings.columns),
            optionLabel = { stringResource(epubColumnsLabelRes(it)) },
            onSelect = { onSettingsChange(settings.copy(columns = it)) },
        )
        EpubSwitchRow(
            label = stringResource(R.string.epub_animate_page_turns),
            checked = settings.animatePageTurns,
            onCheckedChange = { onSettingsChange(settings.copy(animatePageTurns = it)) },
        )

        // Margin notes (mirrors the iOS EpubOverlayPreferencesEditor "Reading" section).
        EpubSectionTitle(stringResource(R.string.epub_section_overlay))
        EpubOverlayPickerRow(
            label = stringResource(R.string.epub_overlay_header_leading),
            selected = overlay.readerHeaderLeading,
            onSelect = { onOverlayChange(overlay.copy(readerHeaderLeading = it)) },
        )
        EpubOverlayPickerRow(
            label = stringResource(R.string.epub_overlay_header_center),
            selected = overlay.readerHeaderCenter,
            onSelect = { onOverlayChange(overlay.copy(readerHeaderCenter = it)) },
        )
        EpubOverlayPickerRow(
            label = stringResource(R.string.epub_overlay_header_trailing),
            selected = overlay.readerHeaderTrailing,
            onSelect = { onOverlayChange(overlay.copy(readerHeaderTrailing = it)) },
        )
        EpubOverlayPickerRow(
            label = stringResource(R.string.epub_overlay_footer_leading),
            selected = overlay.readerFooterLeading,
            onSelect = { onOverlayChange(overlay.copy(readerFooterLeading = it)) },
        )
        EpubOverlayPickerRow(
            label = stringResource(R.string.epub_overlay_footer_center),
            selected = overlay.readerFooterCenter,
            onSelect = { onOverlayChange(overlay.copy(readerFooterCenter = it)) },
        )
        EpubOverlayPickerRow(
            label = stringResource(R.string.epub_overlay_footer_trailing),
            selected = overlay.readerFooterTrailing,
            onSelect = { onOverlayChange(overlay.copy(readerFooterTrailing = it)) },
        )
        EpubSwitchRow(
            label = stringResource(R.string.epub_overlay_progress_bar),
            checked = overlay.showsReaderProgressBar,
            onCheckedChange = { onOverlayChange(overlay.copy(showsReaderProgressBar = it)) },
        )

        // Slots shown while the reader chrome is visible (mirrors the iOS "Controls" section).
        EpubSectionTitle(stringResource(R.string.epub_section_overlay_controls))
        EpubOverlayPickerRow(
            label = stringResource(R.string.epub_overlay_controls_header_center),
            selected = overlay.controlsHeaderCenter,
            onSelect = { onOverlayChange(overlay.copy(controlsHeaderCenter = it)) },
        )
        EpubOverlayPickerRow(
            label = stringResource(R.string.epub_overlay_controls_footer_center),
            selected = overlay.controlsFooterCenter,
            onSelect = { onOverlayChange(overlay.copy(controlsFooterCenter = it)) },
        )
    }
}

/** EPUB settings sheet (mirrors EpubReaderSettingsView presented as a sheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubSettingsSheet(
    settings: EpubReaderSettings,
    onSettingsChange: (EpubReaderSettings) -> Unit,
    /**
     * Reader-wide switches (keep screen awake / draw under cutout), shared with DIVINA.
     * Passed as the DIVINA settings object because that is where they are persisted, mirroring
     * volumeButtonPageTurnEnabled.
     */
    common: DivinaSettings,
    /** Transform on the SHARED reader settings: applied to the latest persisted value, not to the
     *  snapshot this sheet was composed with, so two quick toggles cannot clobber each other. */
    onCommonUpdate: ((DivinaSettings) -> DivinaSettings) -> Unit,
    customFonts: List<CustomFont>,
    onOpenCustomFonts: () -> Unit,
    overlay: EpubOverlayPreferences,
    onOverlayChange: (EpubOverlayPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    var showPresets by remember { mutableStateOf(false) }
    val hasDisplayCutout = rememberReaderWindowInsetsCss().hasCutout
    AppModalDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                stringResource(R.string.epub_settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            EpubSettingsContent(
                settings = settings,
                onSettingsChange = onSettingsChange,
                customFonts = customFonts,
                onOpenPresets = { showPresets = true },
                onOpenCustomFonts = onOpenCustomFonts,
                overlay = overlay,
                onOverlayChange = onOverlayChange,
            )
            // Reader-wide behavior, mirrored from (and back into) the DIVINA settings object so
            // the same switch toggled in any reader stays in step everywhere.
            EpubSectionTitle(stringResource(R.string.reader_behavior_section))
            EpubSwitchRow(
                label = stringResource(R.string.reader_keep_screen_awake),
                checked = common.keepScreenAwake,
                onCheckedChange = { value -> onCommonUpdate { it.copy(keepScreenAwake = value) } },
            )
            EpubSwitchRow(
                label = stringResource(R.string.reader_draw_under_cutout),
                subtitle = stringResource(
                    if (hasDisplayCutout) {
                        R.string.reader_draw_under_cutout_hint
                    } else {
                        R.string.reader_no_display_cutout
                    },
                ),
                checked = common.drawUnderCutout,
                enabled = hasDisplayCutout,
                onCheckedChange = { value -> onCommonUpdate { it.copy(drawUnderCutout = value) } },
            )
        }
    }
    if (showPresets) {
        EpubThemePresetsSheet(
            currentSettings = settings,
            onApply = { presetSettings ->
                onSettingsChange(presetSettings)
                showPresets = false
            },
            onDismiss = { showPresets = false },
        )
    }
}

/** Theme preset manager (mirrors EpubThemePreferencesView: list + apply / rename / delete / create). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubThemePresetsSheet(
    currentSettings: EpubReaderSettings,
    onApply: (EpubReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    viewModel: EpubThemePresetsViewModel = hiltViewModel(),
) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    AppModalDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                stringResource(R.string.epub_theme_presets),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val newPresetBase = stringResource(R.string.epub_new_preset)
            OutlinedButton(
                onClick = {
                    var candidate = newPresetBase
                    var index = 2
                    while (presets.any { it.name == candidate }) {
                        candidate = "$newPresetBase $index"
                        index += 1
                    }
                    viewModel.save(currentSettings.toPreset(candidate))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.epub_save_as_preset))
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
                        stringResource(R.string.epub_no_theme_presets),
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
                        Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            presetUpdatedLabel(preset.updatedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onApply(preset.toSettings()) }) {
                        Text(stringResource(R.string.epub_apply))
                    }
                    IconButton(onClick = {
                        renameTarget = preset.name
                        renameText = preset.name
                    }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.epub_rename),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.delete(preset.name) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_delete),
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
            title = { Text(stringResource(R.string.epub_rename_preset)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        viewModel.rename(target, renameText)
                    }
                    renameTarget = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.common_cancel)) }
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
            stringResource(epubThemeLabelRes(choice)),
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
    optionLabel: @Composable (String) -> String = { it },
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
                    label = { Text(optionLabel(option), style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
private fun EpubOverlayPickerRow(
    label: String,
    selected: EpubOverlayItem,
    onSelect: (EpubOverlayItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(epubOverlayItemLabelRes(selected)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KmDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EpubOverlayItem.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(epubOverlayItemLabelRes(item))) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EpubSwitchRow(
    label: String,
    checked: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
