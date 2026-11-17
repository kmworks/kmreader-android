package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Browse card display options, mirroring the AppStorage entries of KMReader's
 * SettingsBrowseView.
 *
 * Injected by AppRoot from the persisted settings in
 * [com.kmworks.kmreader.core.settings.AppSettingsStore]; card components read them
 * here, so the settings-page toggles genuinely affect card rendering in
 * browse/lists/dashboard.
 */
data class CardDisplayOptions(
    val coverOnly: Boolean = false,
    val textOverlay: Boolean = false,
    val showBookSeriesTitle: Boolean = true,
    val preserveAspectRatio: Boolean = true,
    val showShadow: Boolean = true,
    val showUnreadIndicator: Boolean = true,
    val showProgressBar: Boolean = true,
    val blurUnreadCovers: Boolean = false,
)

val LocalCardDisplay = compositionLocalOf { CardDisplayOptions() }
