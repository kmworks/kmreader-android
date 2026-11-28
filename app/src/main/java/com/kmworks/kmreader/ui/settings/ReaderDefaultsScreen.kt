package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R
import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.DivinaSettingsViewModel
import com.kmworks.kmreader.ui.reader.EpubSettingsContent
import com.kmworks.kmreader.ui.reader.EpubThemePresetsSheet
import com.kmworks.kmreader.ui.reader.PageLayout
import com.kmworks.kmreader.ui.reader.PdfSettingsContent
import com.kmworks.kmreader.ui.reader.ReadingDirection
import com.kmworks.kmreader.ui.reader.SplitWidePageMode
import com.kmworks.kmreader.ui.reader.UpscaleMode
import com.kmworks.kmreader.ui.reader.UpscalePrecision
import com.kmworks.kmreader.ui.reader.UpscaleSettings
import com.kmworks.kmreader.ui.reader.rememberReaderWindowInsetsCss

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
    val epubOverlay by readerSettingsViewModel.epubOverlay.collectAsStateWithLifecycle()
    val pdfSettings by readerSettingsViewModel.pdfSettings.collectAsStateWithLifecycle()
    // Mirrors the original AppConfig.progressRecordingThreshold (default 3; 0 = immediately)
    val progressThreshold by prefsViewModel.progressRecordingThreshold.collectAsStateWithLifecycle()
    // PDF engine preference (device-local): native PdfRenderer vs the default androidx.pdf engine
    val useNativePdfReader by prefsViewModel.useNativePdfReader.collectAsStateWithLifecycle()
    // A cutout switch on a device without a notch is a dead control: keep it visible but disabled
    // (hiding it makes the feature undiscoverable until the user changes phones).
    val hasDisplayCutout = rememberReaderWindowInsetsCss().hasCutout

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reader_defaults_title)) },
                navigationIcon = settingsBackIcon {
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
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                // Shared by DIVINA, EPUB and PDF. Kept in its own section (rather than inside the
                // DIVINA card) so it reads as what it is: reader-wide behavior, not a DIVINA option
                // — the precedent is volumeButtonPageTurnEnabled, which is also a shared switch.
                SettingsSection(
                    title = stringResource(R.string.reader_behavior_section),
                    footer = stringResource(R.string.reader_behavior_section_hint),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.reader_keep_screen_awake),
                        checked = divinaSettings.keepScreenAwake,
                        onCheckedChange = { value ->
                            readerSettingsViewModel.update { it.copy(keepScreenAwake = value) }
                        },
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.reader_draw_under_cutout),
                        subtitle = stringResource(
                            if (hasDisplayCutout) {
                                R.string.reader_draw_under_cutout_hint
                            } else {
                                R.string.reader_no_display_cutout
                            },
                        ),
                        checked = divinaSettings.drawUnderCutout,
                        enabled = hasDisplayCutout,
                        onCheckedChange = { value ->
                            readerSettingsViewModel.update { it.copy(drawUnderCutout = value) }
                        },
                    )
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.reader_defaults_divina_section)) {
                    SettingsPickerRow(
                        title = stringResource(R.string.reader_defaults_reading_direction),
                        selectedLabel = stringResource(divinaDirection.labelRes),
                        options = ReadingDirection.entries.map {
                            it to stringResource(it.labelRes)
                        },
                        onSelect = { direction ->
                            prefsViewModel.setDivinaDefaultDirection(direction.name.lowercase())
                        },
                        icon = Icons.Outlined.SwapHoriz,
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.reader_defaults_force_direction),
                        subtitle = stringResource(R.string.reader_defaults_force_direction_hint),
                        checked = divinaSettings.forceDefaultDirection,
                        onCheckedChange = { value ->
                            readerSettingsViewModel.update { it.copy(forceDefaultDirection = value) }
                        },
                    )
                    SettingsRowDivider()
                    // Shared by all three readers (DIVINA, EPUB, PDF) and off by default, mirroring
                    // the original volumeButtonPageTurnEnabled AppStorage key.
                    SettingsSwitchRow(
                        title = stringResource(R.string.reader_volume_button_page_turn),
                        subtitle = stringResource(R.string.reader_volume_button_page_turn_hint),
                        checked = divinaSettings.volumeButtonPageTurnEnabled,
                        onCheckedChange = { value ->
                            readerSettingsViewModel.update { it.copy(volumeButtonPageTurnEnabled = value) }
                        },
                    )
                    SettingsRowDivider()
                    SettingsPickerRow(
                        title = stringResource(R.string.reader_defaults_page_layout),
                        selectedLabel = stringResource(divinaSettings.defaultPageLayout.labelRes),
                        options = PageLayout.entries.map {
                            it to stringResource(it.labelRes)
                        },
                        onSelect = { layout ->
                            readerSettingsViewModel.update { it.copy(defaultPageLayout = layout) }
                        },
                        icon = Icons.Outlined.ViewAgenda,
                    )
                    SettingsRowDivider()
                    // Split wide pages is a 4-option picker (none/auto/ltr/rtl), not a switch (the
                    // original's scroll mode was removed: whole-spread panning does not render reliably)
                    SettingsPickerRow(
                        title = stringResource(R.string.reader_defaults_split_wide),
                        selectedLabel = stringResource(divinaSettings.defaultSplitWideMode.labelRes),
                        options = SplitWidePageMode.entries.map {
                            it to stringResource(it.labelRes)
                        },
                        onSelect = { mode ->
                            readerSettingsViewModel.update { it.copy(defaultSplitWideMode = mode) }
                        },
                        subtitle = stringResource(divinaSettings.defaultSplitWideMode.hintRes),
                        icon = Icons.Outlined.ViewWeek,
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.reader_defaults_solo_cover),
                        checked = divinaSettings.defaultSoloCoverPage,
                        onCheckedChange = { value ->
                            readerSettingsViewModel.update { it.copy(defaultSoloCoverPage = value) }
                        },
                    )
                }
            }
            item {
                // waifu2x upscaling defaults (docs/waifu2x-upscaling-plan.md §8); mirrors the sheet's
                // "Image processing" group so the global defaults page can configure it too.
                SettingsSection(
                    title = stringResource(R.string.reader_upscale_title),
                    footer = stringResource(R.string.reader_upscale_hint),
                ) {
                    SettingsPickerRow(
                        title = stringResource(R.string.reader_upscale_mode_title),
                        selectedLabel = stringResource(divinaSettings.upscale.mode.labelRes),
                        options = UpscaleMode.entries.map { it to stringResource(it.labelRes) },
                        onSelect = { mode ->
                            readerSettingsViewModel.update {
                                it.copy(upscale = it.upscale.copy(mode = mode))
                            }
                        },
                        icon = Icons.Outlined.PhotoFilter,
                    )
                    if (divinaSettings.upscale.mode != UpscaleMode.Off) {
                        SettingsRowDivider()
                        SettingsPickerRow(
                            title = stringResource(R.string.reader_upscale_denoise_title),
                            selectedLabel = stringResource(divinaSettings.upscale.denoise.labelRes),
                            options = DenoiseLevel.entries.map { it to stringResource(it.labelRes) },
                            onSelect = { denoise ->
                                readerSettingsViewModel.update {
                                    it.copy(upscale = it.upscale.copy(denoise = denoise))
                                }
                            },
                        )
                        SettingsRowDivider()
                        SettingsPickerRow(
                            title = stringResource(R.string.reader_upscale_precision_title),
                            selectedLabel = stringResource(divinaSettings.upscale.precision.labelRes),
                            options = UpscalePrecision.entries.map { it to stringResource(it.labelRes) },
                            onSelect = { precision ->
                                readerSettingsViewModel.update {
                                    it.copy(upscale = it.upscale.copy(precision = precision))
                                }
                            },
                        )
                        SettingsRowDivider()
                        SettingsPickerRow(
                            title = stringResource(R.string.reader_upscale_preload_ahead_title),
                            selectedLabel = preloadAheadLabel(divinaSettings.upscale.preloadAhead),
                            options = (0..UpscaleSettings.MAX_PRELOAD_AHEAD).map {
                                it to preloadAheadLabel(it)
                            },
                            onSelect = { ahead ->
                                readerSettingsViewModel.update {
                                    it.copy(upscale = it.upscale.copy(preloadAhead = ahead))
                                }
                            },
                        )
                    }
                }
            }
            item {
                // Mirrors the original ReaderPreferencesView "Reading Progress" section (options 0/1/3/5/10 pages)
                SettingsSection(
                    title = stringResource(R.string.reader_defaults_progress_section),
                    footer = stringResource(R.string.reader_defaults_record_after_hint),
                ) {
                    val thresholdOptions = listOf(0, 1, 3, 5, 10)
                    SettingsPickerRow(
                        title = stringResource(R.string.reader_defaults_record_after),
                        selectedLabel = progressThresholdLabel(progressThreshold),
                        options = thresholdOptions.map { it to progressThresholdLabel(it) },
                        onSelect = prefsViewModel::setProgressRecordingThreshold,
                        icon = Icons.Outlined.Schedule,
                    )
                }
            }
            item {
                // EPUB defaults share the sheet content; the SettingsSection card keeps the page in
                // the same settings-form style as the Divina section above (label + rounded card).
                SettingsSection(title = "EPUB") {
                    EpubSettingsContent(
                        settings = epubSettings,
                        onSettingsChange = { new -> readerSettingsViewModel.updateEpub { new } },
                        customFonts = emptyList(),
                        onOpenPresets = { showEpubPresets = true },
                        onOpenCustomFonts = onOpenCustomFonts,
                        overlay = epubOverlay,
                        onOverlayChange = { new -> readerSettingsViewModel.updateEpubOverlay { new } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            item {
                SettingsSection(title = "PDF") {
                    SettingsSwitchRow(
                        title = stringResource(R.string.pdf_native_engine),
                        subtitle = stringResource(
                            if (useNativePdfReader) {
                                R.string.pdf_native_engine_hint
                            } else {
                                R.string.pdf_comic_engine_hint
                            },
                        ),
                        checked = useNativePdfReader,
                        onCheckedChange = prefsViewModel::setUseNativePdfReader,
                        icon = Icons.Outlined.PictureAsPdf,
                    )
                    // Reading options below belong to the PDF reader; the comic reader has its own
                    // defaults page, exactly as the "Use Native PDF Reader" switch gates them on iOS.
                    if (useNativePdfReader) {
                        SettingsRowDivider()
                        PdfSettingsContent(
                            settings = pdfSettings,
                            onSettingsChange = { new -> readerSettingsViewModel.updatePdf { new } },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
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

/** Mirrors the original progressRecordingThresholdLabel: 0 -> Immediately, N -> N pages. */
@Composable
private fun progressThresholdLabel(value: Int): String = when (value) {
    0 -> stringResource(R.string.settings_progress_immediately)
    else -> pluralStringResource(R.plurals.book_pages, value, value)
}

/** 0 is a named mode, positive values are a plain page count. */
@Composable
private fun preloadAheadLabel(value: Int): String =
    if (value == 0) stringResource(R.string.reader_upscale_preload_ahead_off) else value.toString()
