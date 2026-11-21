package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R
import com.kmworks.kmreader.ui.reader.DivinaSettingsViewModel
import com.kmworks.kmreader.ui.reader.EpubSettingsContent
import com.kmworks.kmreader.ui.reader.EpubThemePresetsSheet
import com.kmworks.kmreader.ui.reader.PageLayout
import com.kmworks.kmreader.ui.reader.PdfSettingsContent
import com.kmworks.kmreader.ui.reader.ReadingDirection
import com.kmworks.kmreader.ui.reader.SplitWidePageMode

/**
 * Reader defaults (mirrors the original ReaderPreferencesView + Divina/Epub/Pdf preferences).
 *
 * Semantics mirror the original: these defaults are GLOBAL preferences, persisted and effective immediately;
 * direction/layout/split-wide/solo-cover changes made inside a reader session live only for that session
 * and are not written back here (mirrors the original DivinaReaderView @State initial values and the
 * resetReaderPreferencesForCurrentBook semantics).
 * The default reading direction uses AppSettingsStore (getting client-settings cloud sync automatically);
 * everything else uses DivinaSettingsStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderDefaultsScreen(
    onBack: () -> Unit,
    onOpenCustomFonts: () -> Unit = {},
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
    readerSettingsViewModel: DivinaSettingsViewModel = hiltViewModel(),
) {
    // Theme presets sheet (state local to this screen, opened the same way as EpubSettingsSheet)
    var showEpubPresets by remember { mutableStateOf(false) }
    // Divina defaults (mirrors DivinaPreferencesView)
    val persistedDirection by prefsViewModel.divinaDefaultDirection.collectAsStateWithLifecycle()
    val divinaDirection = ReadingDirection.entries
        .firstOrNull { it.name.lowercase() == persistedDirection } ?: ReadingDirection.Ltr
    val divinaSettings by readerSettingsViewModel.settings.collectAsStateWithLifecycle()
    val epubSettings by readerSettingsViewModel.epubSettings.collectAsStateWithLifecycle()
    val pdfSettings by readerSettingsViewModel.pdfSettings.collectAsStateWithLifecycle()
    // Mirrors the original AppConfig.progressRecordingThreshold (default 3; 0 = immediately)
    val progressThreshold by prefsViewModel.progressRecordingThreshold.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reader_defaults_title)) },
                navigationIcon = SettingsBackIcon {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                DefaultsSectionTitle(stringResource(R.string.reader_defaults_divina_section))
                val directionLabels = mutableListOf<String>()
                for (direction in ReadingDirection.entries) directionLabels.add(stringResource(direction.labelRes))
                DefaultsChipRow(
                    label = stringResource(R.string.reader_defaults_reading_direction),
                    options = directionLabels,
                    selected = stringResource(divinaDirection.labelRes),
                    onSelect = { label ->
                        val index = directionLabels.indexOf(label)
                        if (index >= 0) {
                            prefsViewModel.setDivinaDefaultDirection(ReadingDirection.entries[index].name.lowercase())
                        }
                    },
                )
                DefaultsSwitchRow(
                    label = stringResource(R.string.reader_defaults_force_direction),
                    checked = divinaSettings.forceDefaultDirection,
                    onCheckedChange = { value ->
                        readerSettingsViewModel.update { it.copy(forceDefaultDirection = value) }
                    },
                )
                DefaultsHintText(stringResource(R.string.reader_defaults_force_direction_hint))
                // Mirrors DivinaPreferencesView: page layout single/auto/dual
                val pageLayoutLabels = mutableListOf<String>()
                for (layout in PageLayout.entries) pageLayoutLabels.add(stringResource(layout.labelRes))
                DefaultsChipRow(
                    label = stringResource(R.string.reader_defaults_page_layout),
                    options = pageLayoutLabels,
                    selected = stringResource(divinaSettings.defaultPageLayout.labelRes),
                    onSelect = { label ->
                        val index = pageLayoutLabels.indexOf(label)
                        if (index >= 0) {
                            readerSettingsViewModel.update { it.copy(defaultPageLayout = PageLayout.entries[index]) }
                        }
                    },
                )
                // Split wide pages is a 4-option picker (none/auto/ltr/rtl), not a switch (the
                // original's scroll mode was removed: whole-spread panning does not render reliably)
                val splitModeLabels = mutableListOf<String>()
                for (mode in SplitWidePageMode.entries) splitModeLabels.add(stringResource(mode.labelRes))
                DefaultsChipRow(
                    label = stringResource(R.string.reader_defaults_split_wide),
                    options = splitModeLabels,
                    selected = stringResource(divinaSettings.defaultSplitWideMode.labelRes),
                    onSelect = { label ->
                        val index = splitModeLabels.indexOf(label)
                        if (index >= 0) {
                            readerSettingsViewModel.update { it.copy(defaultSplitWideMode = SplitWidePageMode.entries[index]) }
                        }
                    },
                )
                DefaultsHintText(stringResource(divinaSettings.defaultSplitWideMode.hintRes))
                DefaultsSwitchRow(
                    label = stringResource(R.string.reader_defaults_solo_cover),
                    checked = divinaSettings.defaultSoloCoverPage,
                    onCheckedChange = { value ->
                        readerSettingsViewModel.update { it.copy(defaultSoloCoverPage = value) }
                    },
                )
            }
            item {
                // Mirrors the original ReaderPreferencesView "Reading Progress" section (options 0/1/3/5/10 pages)
                DefaultsSectionTitle(stringResource(R.string.reader_defaults_progress_section))
                val thresholdOptions = listOf(0, 1, 3, 5, 10)
                val thresholdLabels = mutableListOf<String>()
                for (threshold in thresholdOptions) thresholdLabels.add(progressThresholdLabel(threshold))
                DefaultsChipRow(
                    label = stringResource(R.string.reader_defaults_record_after),
                    options = thresholdLabels,
                    selected = progressThresholdLabel(progressThreshold),
                    onSelect = { label ->
                        val index = thresholdLabels.indexOf(label)
                        if (index >= 0) {
                            prefsViewModel.setProgressRecordingThreshold(thresholdOptions[index])
                        }
                    },
                )
                DefaultsHintText(stringResource(R.string.reader_defaults_record_after_hint))
            }
            item {
                DefaultsSectionTitle("EPUB")
                EpubSettingsContent(
                    settings = epubSettings,
                    onSettingsChange = { new -> readerSettingsViewModel.updateEpub { new } },
                    customFonts = emptyList(),
                    onOpenPresets = { showEpubPresets = true },
                    onOpenCustomFonts = onOpenCustomFonts,
                )
            }
            item {
                DefaultsSectionTitle("PDF")
                PdfSettingsContent(
                    settings = pdfSettings,
                    onSettingsChange = { new -> readerSettingsViewModel.updatePdf { new } },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showEpubPresets) {
        EpubThemePresetsSheet(
            currentSettings = epubSettings,
            onApply = { applied ->
                readerSettingsViewModel.updateEpub { applied }
                showEpubPresets = false
            },
            onDismiss = { showEpubPresets = false },
        )
    }
}

@Composable
private fun DefaultsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

/** Mirrors the original progressRecordingThresholdLabel: 0 -> Immediately, N -> N pages. */
@Composable
private fun progressThresholdLabel(value: Int): String = when (value) {
    0 -> stringResource(R.string.settings_progress_immediately)
    else -> pluralStringResource(R.plurals.book_pages, value, value)
}

@Composable
private fun DefaultsChipRow(
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
private fun DefaultsHintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun DefaultsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
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
