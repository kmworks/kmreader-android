package com.kmworks.kmreader.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.CardDisplayOptions
import com.kmworks.kmreader.ui.components.LocalCardDisplay
import com.kmworks.kmreader.ui.components.SeriesGridCard

/**
 * Browse settings (mirrors KMReader SettingsBrowseView): card preview plus display-option toggles,
 * all persisted to [com.kmworks.kmreader.core.settings.AppSettingsStore]; card components pick up
 * changes live via [LocalCardDisplay].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    val display by viewModel.cardDisplay.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browse_settings_title)) },
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
                SettingsSection(
                    title = stringResource(R.string.browse_settings_preview),
                    footer = stringResource(R.string.browse_settings_preview_hint),
                ) {
                    // LocalCardDisplay is a staticCompositionLocalOf (see ui/components);
                    // key(display) forces the preview subtree to rebuild so toggles apply to the preview immediately.
                    key(display) {
                        CompositionLocalProvider(LocalCardDisplay provides display) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SeriesGridCard(
                                    series = previewSeries,
                                    onClick = { },
                                    modifier = Modifier.weight(1f),
                                )
                                BookGridCard(
                                    book = previewInProgressBook,
                                    onClick = { },
                                    modifier = Modifier.weight(1f),
                                )
                                BookGridCard(
                                    book = previewCompletedBook,
                                    onClick = { },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.browse_settings_cards)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.browse_settings_text_overlay_title),
                        subtitle = stringResource(R.string.browse_settings_text_overlay_subtitle),
                        checked = display.textOverlay,
                        onCheckedChange = viewModel::setCardTextOverlay,
                    )
                    AnimatedVisibility(visible = !display.textOverlay) {
                        Column {
                            SettingsRowDivider()
                            SettingsSwitchRow(
                                title = stringResource(R.string.browse_settings_cover_only_title),
                                subtitle = stringResource(R.string.browse_settings_cover_only_subtitle),
                                checked = display.coverOnly,
                                onCheckedChange = viewModel::setCardCoverOnly,
                            )
                        }
                    }
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.browse_settings_show_series_title),
                        subtitle = stringResource(R.string.browse_settings_show_series_subtitle),
                        checked = display.showBookSeriesTitle,
                        onCheckedChange = viewModel::setCardShowBookSeriesTitle,
                    )
                    AnimatedVisibility(visible = !display.textOverlay) {
                        Column {
                            SettingsRowDivider()
                            SettingsSwitchRow(
                                title = stringResource(R.string.browse_settings_preserve_aspect_title),
                                subtitle = stringResource(R.string.browse_settings_preserve_aspect_subtitle),
                                checked = display.preserveAspectRatio,
                                onCheckedChange = viewModel::setThumbnailPreserveAspectRatio,
                            )
                        }
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.browse_settings_thumbnails)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.browse_settings_shadow_title),
                        subtitle = stringResource(R.string.browse_settings_shadow_subtitle),
                        checked = display.showShadow,
                        onCheckedChange = viewModel::setThumbnailShowShadow,
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.browse_settings_unread_indicator_title),
                        subtitle = stringResource(R.string.browse_settings_unread_indicator_subtitle),
                        checked = display.showUnreadIndicator,
                        onCheckedChange = viewModel::setThumbnailShowUnreadIndicator,
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.browse_settings_blur_unread_title),
                        subtitle = stringResource(R.string.browse_settings_blur_unread_subtitle),
                        checked = display.blurUnreadCovers,
                        onCheckedChange = viewModel::setThumbnailBlurUnreadCovers,
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.browse_settings_progress_bar_title),
                        subtitle = stringResource(R.string.browse_settings_progress_bar_subtitle),
                        checked = display.showProgressBar,
                        onCheckedChange = viewModel::setThumbnailShowProgressBar,
                    )
                }
            }
        }
    }
}

private val previewSeries = Series(
    id = 0,
    title = "Sample Series",
    author = "Sample Author",
    hue = 210f,
    bookCount = 12,
    unreadCount = 3,
    progress = 0.75f,
)

private val previewInProgressBook = Book(
    id = 0,
    number = "Vol. 7",
    title = "Sample Book",
    seriesTitle = "Sample Series",
    hue = 210f,
    pageCount = 200,
    progress = 0.35f,
)

private val previewCompletedBook = Book(
    id = 0,
    number = "Vol. 6",
    title = "Sample Book",
    seriesTitle = "Sample Series",
    hue = 210f,
    pageCount = 200,
    progress = 1f,
    lastReadAtMs = System.currentTimeMillis() - 3 * 86_400_000, // "3 days ago" to preview the completed state row
)
