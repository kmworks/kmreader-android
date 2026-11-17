package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.ui.RootViewModel
import com.kmworks.kmreader.ui.tracker.TrackerSettingsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** kmrs capability gate for the Reading Stats / Tracker entries (used for SettingsScreen subtitles). */
@HiltViewModel
class SettingsEntryViewModel @Inject constructor(
    serverProfileStore: ServerProfileStore,
) : ViewModel() {
    val readingStatsSupported: StateFlow<Boolean> = serverProfileStore.profile
        .map { it?.features?.contains(ServerFeature.ReadingStats) == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val trackersSupported: StateFlow<Boolean> = serverProfileStore.profile
        .map { it?.features?.contains(ServerFeature.Trackers) == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/** Settings hub (mirrors SettingsView). New entries are exposed via callbacks; host navigates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit = {},
    onOpenBrowse: () -> Unit = {},
    onOpenDashboard: () -> Unit = {},
    onOpenReaderDefaults: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenCustomFonts: () -> Unit = {},
    onOpenCache: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenSse: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    viewModel: SettingsEntryViewModel = hiltViewModel(),
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    // The true-black quick toggle and the appearance screen read/write the same AppSettingsStore.trueBlackDark, so both stay in sync
    val trueBlack by prefsViewModel.trueBlackDark.collectAsStateWithLifecycle()
    val themeMode by prefsViewModel.themeMode.collectAsStateWithLifecycle()
    val serverConfig by rootViewModel.config.collectAsStateWithLifecycle()
    var showTrackerSettings by remember { mutableStateOf(false) }
    // kmrs capability gate: entry subtitles hint when the ReadingStats/Trackers features are missing
    val statsSupported by viewModel.readingStatsSupported.collectAsState()
    val trackersSupported by viewModel.trackersSupported.collectAsState()

    if (showTrackerSettings) {
        TrackerSettingsScreen(onBack = { showTrackerSettings = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
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
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item { SettingsGroupHeader(stringResource(R.string.appearance_title)) }
            item { SettingsValueRow(stringResource(R.string.appearance_theme), themeModeLabel(themeMode)) }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_true_black_title),
                    subtitle = stringResource(R.string.settings_true_black_subtitle),
                    checked = trueBlack,
                    onCheckedChange = prefsViewModel::setTrueBlackDark,
                )
            }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_appearance_entry_title),
                    subtitle = stringResource(R.string.settings_appearance_entry_subtitle),
                    onClick = onOpenAppearance,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader(stringResource(R.string.browse_settings_title)) }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_browse_entry_title),
                    subtitle = stringResource(R.string.settings_browse_entry_subtitle),
                    onClick = onOpenBrowse,
                )
            }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_dashboard_title),
                    subtitle = stringResource(R.string.settings_dashboard_subtitle),
                    onClick = onOpenDashboard,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader(stringResource(R.string.settings_section_reader)) }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.reader_defaults_title),
                    subtitle = stringResource(R.string.settings_reader_defaults_subtitle),
                    onClick = onOpenReaderDefaults,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader(stringResource(R.string.settings_section_tracking)) }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_trackers_title),
                    subtitle = stringResource(
                        if (trackersSupported) {
                            R.string.settings_trackers_subtitle
                        } else {
                            R.string.settings_trackers_subtitle_requires_kmrs
                        },
                    ),
                    onClick = { showTrackerSettings = true },
                )
            }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_stats_title),
                    subtitle = stringResource(
                        if (statsSupported) {
                            R.string.settings_stats_subtitle
                        } else {
                            R.string.settings_stats_subtitle_requires_kmrs
                        },
                    ),
                    onClick = onOpenStats,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader("EPUB") }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_custom_fonts_title),
                    subtitle = stringResource(R.string.settings_custom_fonts_subtitle),
                    onClick = onOpenCustomFonts,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader(stringResource(R.string.settings_cache)) }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_cache),
                    subtitle = stringResource(R.string.settings_cache_subtitle),
                    onClick = onOpenCache,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader(stringResource(R.string.settings_section_system)) }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_network),
                    subtitle = stringResource(R.string.settings_network_subtitle),
                    onClick = onOpenNetwork,
                )
            }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_sse),
                    subtitle = stringResource(R.string.settings_sse_subtitle),
                    onClick = onOpenSse,
                )
            }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_logs),
                    subtitle = stringResource(R.string.settings_logs_subtitle),
                    onClick = onOpenLogs,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader(stringResource(R.string.settings_account)) }
            item {
                SettingsValueRow(
                    stringResource(R.string.settings_current_instance),
                    serverConfig?.normalizedBaseUrl ?: stringResource(R.string.settings_not_connected),
                )
            }
            item {
                SettingsValueRow(
                    stringResource(R.string.settings_account),
                    when {
                        serverConfig == null -> "—"
                        serverConfig?.useApiKey == true -> "API Key"
                        else -> serverConfig?.username.orEmpty()
                    },
                )
            }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.server_list_title),
                    subtitle = stringResource(R.string.settings_server_accounts_subtitle),
                    onClick = onOpenAccount,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { SettingsGroupHeader(stringResource(R.string.settings_about)) }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_about_title),
                    subtitle = stringResource(R.string.settings_about_subtitle),
                    onClick = onOpenAbout,
                )
            }
            item {
                SettingsNavRow(
                    title = stringResource(R.string.settings_licenses),
                    subtitle = stringResource(R.string.settings_licenses_subtitle),
                    onClick = onOpenLicenses,
                )
            }
        }
    }
}

/** Theme stored value to display label (consistent with the appearance screen's ThemeOptions). */
@Composable
private fun themeModeLabel(mode: String): String = when (mode) {
    "light" -> stringResource(R.string.appearance_theme_light)
    "dark" -> stringResource(R.string.appearance_theme_dark)
    else -> stringResource(R.string.appearance_theme_system)
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsValueRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
        Spacer(Modifier.height(0.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
