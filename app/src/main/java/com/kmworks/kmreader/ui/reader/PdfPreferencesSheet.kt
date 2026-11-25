package com.kmworks.kmreader.ui.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.ui.components.AppModalDialog

/** PDF reader prefs (mirrors PdfPreferencesView / PdfReaderSettingsSheet). */
data class PdfReaderSettings(
    val continuousScroll: Boolean = false,
    val pagePresentation: String = PDF_PRESENTATION_AUTO,
    val background: String = PDF_BACKGROUND_SYSTEM,
    val soloCoverPage: Boolean = true,
    val controlsScrim: Boolean = true,
    val showProgressWhileReading: Boolean = true,
)

/** Stable storage keys for [PdfReaderSettings.pagePresentation] (persisted raw in DivinaSettingsStore). */
const val PDF_PRESENTATION_AUTO = "auto"
const val PDF_PRESENTATION_SINGLE = "single"
const val PDF_PRESENTATION_SINGLE_CONTINUOUS = "singleContinuous"
const val PDF_PRESENTATION_DUAL_CONTINUOUS = "dualContinuous"

/** Stable storage keys for [PdfReaderSettings.background]. */
const val PDF_BACKGROUND_SYSTEM = "system"
const val PDF_BACKGROUND_WHITE = "white"
const val PDF_BACKGROUND_GRAY = "gray"
const val PDF_BACKGROUND_BLACK = "black"

/** Ordered page-presentation options (auto / single / single continuous / dual continuous). */
val PdfPagePresentationOptions = listOf(
    PDF_PRESENTATION_AUTO,
    PDF_PRESENTATION_SINGLE,
    PDF_PRESENTATION_SINGLE_CONTINUOUS,
    PDF_PRESENTATION_DUAL_CONTINUOUS,
)

/** Ordered reading-background options (system / white / gray / black). */
val PdfBackgroundOptions = listOf(
    PDF_BACKGROUND_SYSTEM,
    PDF_BACKGROUND_WHITE,
    PDF_BACKGROUND_GRAY,
    PDF_BACKGROUND_BLACK,
)

/** Maps legacy persisted values (including the old Chinese labels) onto the stable keys. */
fun normalizePdfPagePresentation(value: String): String = when (value) {
    PDF_PRESENTATION_SINGLE, "\u5355\u9875" -> PDF_PRESENTATION_SINGLE
    PDF_PRESENTATION_SINGLE_CONTINUOUS, "\u5355\u9875\u8fde\u7eed" -> PDF_PRESENTATION_SINGLE_CONTINUOUS
    PDF_PRESENTATION_DUAL_CONTINUOUS, "\u53cc\u9875\u8fde\u7eed" -> PDF_PRESENTATION_DUAL_CONTINUOUS
    else -> PDF_PRESENTATION_AUTO
}

/**
 * Resolves [PDF_PRESENTATION_AUTO] against the current orientation, mirroring iOS
 * `PdfPagePresentation.resolved(for:)`: auto = dualContinuous in landscape, single (paged)
 * in portrait. Non-auto values pass through normalized.
 */
fun resolvePdfPresentation(presentation: String, isLandscape: Boolean): String =
    when (val normalized = normalizePdfPagePresentation(presentation)) {
        PDF_PRESENTATION_AUTO ->
            if (isLandscape) PDF_PRESENTATION_DUAL_CONTINUOUS else PDF_PRESENTATION_SINGLE
        else -> normalized
    }

fun normalizePdfBackground(value: String): String = when (value) {
    PDF_BACKGROUND_WHITE, "\u767d" -> PDF_BACKGROUND_WHITE
    PDF_BACKGROUND_GRAY, "\u7070" -> PDF_BACKGROUND_GRAY
    PDF_BACKGROUND_BLACK, "\u9ed1" -> PDF_BACKGROUND_BLACK
    else -> PDF_BACKGROUND_SYSTEM
}

@StringRes
fun pdfPagePresentationLabelRes(value: String): Int = when (normalizePdfPagePresentation(value)) {
    PDF_PRESENTATION_SINGLE -> R.string.pdf_presentation_single
    PDF_PRESENTATION_SINGLE_CONTINUOUS -> R.string.pdf_presentation_single_continuous
    PDF_PRESENTATION_DUAL_CONTINUOUS -> R.string.pdf_presentation_dual_continuous
    else -> R.string.pdf_presentation_auto
}

@StringRes
fun pdfBackgroundLabelRes(value: String): Int = when (normalizePdfBackground(value)) {
    PDF_BACKGROUND_WHITE -> R.string.reader_background_white
    PDF_BACKGROUND_GRAY -> R.string.reader_background_gray
    PDF_BACKGROUND_BLACK -> R.string.reader_background_black
    else -> R.string.reader_background_system
}

/** PDF settings content (mirrors PdfPreferencesView sections). Shared by sheet and defaults page. */
@Composable
fun PdfSettingsContent(
    settings: PdfReaderSettings,
    onSettingsChange: (PdfReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = normalizePdfPagePresentation(settings.pagePresentation)
    Column(modifier = modifier) {
        PdfSectionTitle(stringResource(R.string.pdf_section_paging))
        PdfSwitchRow(
            label = stringResource(R.string.pdf_continuous_scroll),
            subtitle = stringResource(R.string.pdf_continuous_scroll_hint),
            checked = settings.continuousScroll,
            onCheckedChange = { onSettingsChange(settings.copy(continuousScroll = it)) },
        )
        val presentationLabels = mutableListOf<String>()
        for (option in PdfPagePresentationOptions) presentationLabels.add(stringResource(pdfPagePresentationLabelRes(option)))
        PdfChipRow(
            label = stringResource(R.string.pdf_page_presentation),
            options = presentationLabels,
            selected = stringResource(pdfPagePresentationLabelRes(presentation)),
            onSelect = { label ->
                val index = presentationLabels.indexOf(label)
                if (index >= 0) {
                    // The chip and the switch above write two different keys that the reader
                    // combines (PdfReaderScreen's discretePages), so a chip that only wrote
                    // pagePresentation silently lost to a switch left off: picking
                    // "single continuous" still turned pages, and picking "single" ignored a
                    // switch turned on. Keep continuousScroll in step with the chip; "auto"
                    // keeps whatever the switch says because the orientation picks the layout.
                    val next = PdfPagePresentationOptions[index]
                    val continuous =
                        when (next) {
                            PDF_PRESENTATION_SINGLE_CONTINUOUS, PDF_PRESENTATION_DUAL_CONTINUOUS -> true
                            PDF_PRESENTATION_SINGLE -> false
                            else -> settings.continuousScroll
                        }
                    onSettingsChange(
                        settings.copy(pagePresentation = next, continuousScroll = continuous),
                    )
                }
            },
        )
        // Solo cover only meaningful for auto / dual continuous (mirrors supportsCoverSolo)
        if (presentation == PDF_PRESENTATION_AUTO || presentation == PDF_PRESENTATION_DUAL_CONTINUOUS) {
            PdfSwitchRow(
                label = stringResource(R.string.pdf_solo_cover),
                checked = settings.soloCoverPage,
                onCheckedChange = { onSettingsChange(settings.copy(soloCoverPage = it)) },
            )
        }

        PdfSectionTitle(stringResource(R.string.pdf_section_appearance))
        val backgroundLabels = mutableListOf<String>()
        for (option in PdfBackgroundOptions) backgroundLabels.add(stringResource(pdfBackgroundLabelRes(option)))
        PdfChipRow(
            label = stringResource(R.string.pdf_reading_background),
            options = backgroundLabels,
            selected = stringResource(pdfBackgroundLabelRes(settings.background)),
            onSelect = { label ->
                val index = backgroundLabels.indexOf(label)
                if (index >= 0) {
                    onSettingsChange(settings.copy(background = PdfBackgroundOptions[index]))
                }
            },
        )

        PdfSectionTitle(stringResource(R.string.pdf_section_overlay))
        PdfSwitchRow(
            label = stringResource(R.string.reader_controls_gradient),
            checked = settings.controlsScrim,
            onCheckedChange = { onSettingsChange(settings.copy(controlsScrim = it)) },
        )
        PdfSwitchRow(
            label = stringResource(R.string.pdf_show_progress),
            checked = settings.showProgressWhileReading,
            onCheckedChange = { onSettingsChange(settings.copy(showProgressWhileReading = it)) },
        )
    }
}

/** PDF preferences sheet (mirrors PdfReaderSettingsSheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreferencesSheet(
    settings: PdfReaderSettings,
    onSettingsChange: (PdfReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    /** Resolved reading direction (series metadata / forced default); shown read-only. */
    readingDirection: ReadingDirection? = null,
) {
    AppModalDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                stringResource(R.string.pdf_settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // The direction has no control here (it comes from the series, or from Reader Defaults
            // when forced), but this is the only place the reader can state which one it resolved:
            // page order cannot flip in PdfView, so the page-jump strip is the one visible effect.
            if (readingDirection != null) {
                PdfInfoRow(
                    label = stringResource(R.string.reader_menu_direction),
                    value = stringResource(readingDirection.labelRes),
                )
            }
            PdfSettingsContent(settings = settings, onSettingsChange = onSettingsChange)
        }
    }
}

/** Read-only label/value row (a resolved setting, not a control). */
@Composable
private fun PdfInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PdfSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun PdfSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PdfChipRow(
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
