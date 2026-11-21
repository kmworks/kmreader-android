package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
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

/** Expanded (tablet landscape) width: the hub splits into two columns of cards. */
private val TwoColumnBreakpoint = 840.dp

/** Breathing room above and below each section card. */
private val CardGap = 16.dp

/** Gutter between the two columns, wider than [CardGap] so the split reads clearly. */
private val TwoColumnGap = 24.dp

/** Cards stop stretching on very wide tablets; the columns are centred instead. */
private val WideContentMaxWidth = 1100.dp

/**
 * Settings hub (mirrors SettingsView). Entries are grouped iOS-style into cards with a colored icon
 * badge per row; on a tablet in landscape the cards flow into two level columns. New entries are
 * exposed via callbacks; host navigates.
 */
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
    onOpenServer: () -> Unit = {},
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
    // Two columns are a tablet affordance: a phone in landscape is wide but still a phone.
    val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600

    if (showTrackerSettings) {
        TrackerSettingsScreen(onBack = { showTrackerSettings = false })
        return
    }

    val groups = settingsGroups(
        themeMode = themeMode,
        trueBlack = trueBlack,
        onTrueBlackChange = prefsViewModel::setTrueBlackDark,
        onOpenAppearance = onOpenAppearance,
        onOpenBrowse = onOpenBrowse,
        onOpenDashboard = onOpenDashboard,
        onOpenReaderDefaults = onOpenReaderDefaults,
        trackersSupported = trackersSupported,
        onOpenTrackers = { showTrackerSettings = true },
        statsSupported = statsSupported,
        onOpenStats = onOpenStats,
        onOpenCustomFonts = onOpenCustomFonts,
        onOpenCache = onOpenCache,
        onOpenNetwork = onOpenNetwork,
        onOpenSse = onOpenSse,
        onOpenLogs = onOpenLogs,
        currentInstance = serverConfig?.normalizedBaseUrl,
        account = when {
            serverConfig == null -> null
            serverConfig?.useApiKey == true -> "API Key"
            else -> serverConfig?.username.orEmpty()
        },
        onOpenAccount = onOpenAccount,
        onOpenServer = onOpenServer,
        onOpenAbout = onOpenAbout,
        onOpenLicenses = onOpenLicenses,
    )

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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isTablet && maxWidth >= TwoColumnBreakpoint) {
                // Wide layout: two independent columns sharing one scroll, balanced so neither ends early.
                val (leftGroups, rightGroups) = balanceSettingsGroups(groups)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = WideContentMaxWidth)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(TwoColumnGap),
                    ) {
                        SettingsColumn(leftGroups, modifier = Modifier.weight(1f))
                        SettingsColumn(rightGroups, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(CardGap),
                ) {
                    items(groups, key = { it.title }) { group ->
                        SettingsGroupCard(group)
                    }
                }
            }
        }
    }
}

/** One column of the wide layout. */
@Composable
private fun SettingsColumn(groups: List<SettingsGroup>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CardGap)) {
        groups.forEach { group -> SettingsGroupCard(group) }
    }
}

/**
 * Hub content in the order of the iOS settings form, each section card tagged with its iOS badge
 * icon and accent color. Only the sections of this app exist here; capabilities without kmrs support
 * keep their hint subtitles.
 */
@Composable
private fun settingsGroups(
    themeMode: String,
    trueBlack: Boolean,
    onTrueBlackChange: (Boolean) -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenReaderDefaults: () -> Unit,
    trackersSupported: Boolean,
    onOpenTrackers: () -> Unit,
    statsSupported: Boolean,
    onOpenStats: () -> Unit,
    onOpenCustomFonts: () -> Unit,
    onOpenCache: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenSse: () -> Unit,
    onOpenLogs: () -> Unit,
    currentInstance: String?,
    account: String?,
    onOpenAccount: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
): List<SettingsGroup> = listOf(
    SettingsGroup(
        title = stringResource(R.string.appearance_title),
        rows = listOf(
            SettingsRow(
                title = stringResource(R.string.appearance_theme),
                icon = Icons.Outlined.Palette,
                accent = SettingsAccent.Blue,
                value = themeModeLabel(themeMode),
            ),
            SettingsRow(
                title = stringResource(R.string.settings_true_black_title),
                subtitle = stringResource(R.string.settings_true_black_subtitle),
                icon = Icons.Outlined.DarkMode,
                accent = SettingsAccent.Blue,
                checked = trueBlack,
                onCheckedChange = onTrueBlackChange,
            ),
            SettingsRow(
                title = stringResource(R.string.settings_appearance_entry_title),
                subtitle = stringResource(R.string.settings_appearance_entry_subtitle),
                icon = Icons.Outlined.Brush,
                accent = SettingsAccent.Blue,
                onClick = onOpenAppearance,
            ),
        ),
    ),
    SettingsGroup(
        title = stringResource(R.string.browse_settings_title),
        rows = listOf(
            SettingsRow(
                title = stringResource(R.string.settings_browse_entry_title),
                subtitle = stringResource(R.string.settings_browse_entry_subtitle),
                icon = Icons.Outlined.GridView,
                accent = SettingsAccent.Purple,
                onClick = onOpenBrowse,
            ),
            SettingsRow(
                title = stringResource(R.string.settings_dashboard_title),
                subtitle = stringResource(R.string.settings_dashboard_subtitle),
                icon = Icons.Outlined.Dashboard,
                accent = SettingsAccent.Orange,
                onClick = onOpenDashboard,
            ),
        ),
    ),
    SettingsGroup(
        title = stringResource(R.string.settings_section_reader),
        rows = listOf(
            SettingsRow(
                title = stringResource(R.string.reader_defaults_title),
                subtitle = stringResource(R.string.settings_reader_defaults_subtitle),
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                accent = SettingsAccent.Blue,
                onClick = onOpenReaderDefaults,
            ),
        ),
    ),
    SettingsGroup(
        title = stringResource(R.string.settings_section_tracking),
        rows = listOf(
            SettingsRow(
                title = stringResource(R.string.settings_trackers_title),
                subtitle = stringResource(
                    if (trackersSupported) {
                        R.string.settings_trackers_subtitle
                    } else {
                        R.string.settings_trackers_subtitle_requires_kmrs
                    },
                ),
                icon = Icons.Outlined.Sync,
                accent = SettingsAccent.Purple,
                onClick = onOpenTrackers,
            ),
            SettingsRow(
                title = stringResource(R.string.settings_stats_title),
                subtitle = stringResource(
                    if (statsSupported) {
                        R.string.settings_stats_subtitle
                    } else {
                        R.string.settings_stats_subtitle_requires_kmrs
                    },
                ),
                icon = Icons.Outlined.BarChart,
                accent = SettingsAccent.Indigo,
                onClick = onOpenStats,
            ),
        ),
    ),
    SettingsGroup(
        title = "EPUB",
        rows = listOf(
            SettingsRow(
                title = stringResource(R.string.settings_custom_fonts_title),
                subtitle = stringResource(R.string.settings_custom_fonts_subtitle),
                icon = Icons.Outlined.TextFields,
                accent = SettingsAccent.Mint,
                onClick = onOpenCustomFonts,
            ),
        ),
    ),
    SettingsGroup(
        title = stringResource(R.string.settings_cache),
        rows = listOf(
            SettingsRow(
                title = stringResource(R.string.settings_cache),
                subtitle = stringResource(R.string.settings_cache_subtitle),
                icon = Icons.Outlined.Cached,
                accent = SettingsAccent.Gray,
                onClick = onOpenCache,
            ),
        ),
    ),
    SettingsGroup(
        title = stringResource(R.string.settings_section_system),
        rows = listOf(
            SettingsRow(
                title = stringResource(R.string.settings_network),
                subtitle = stringResource(R.string.settings_network_subtitle),
                icon = Icons.Outlined.Wifi,
                accent = SettingsAccent.Teal,
                onClick = onOpenNetwork,
            ),
            SettingsRow(
                title = stringResource(R.string.settings_sse),
                subtitle = stringResource(R.string.settings_sse_subtitle),
                icon = Icons.Outlined.Sensors,
                accent = SettingsAccent.Orange,
                onClick = onOpenSse,
            ),
            SettingsRow(
                title = stringResource(R.string.settings_logs),
                subtitle = stringResource(R.string.settings_logs_subtitle),
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                accent = SettingsAccent.Brown,
                onClick = onOpenLogs,
            ),
        ),
    ),
    SettingsGroup(
        title = stringResource(R.string.settings_account),
        rows = listOf(
            SettingsRow(
                title = stringResource(R.string.settings_current_instance),
                icon = Icons.Outlined.Dns,
                accent = SettingsAccent.Green,
                value = currentInstance ?: stringResource(R.string.settings_not_connected),
            ),
            SettingsRow(
                title = stringResource(R.string.settings_account),
                icon = Icons.Outlined.Person,
                accent = SettingsAccent.Green,
                value = account ?: "—",
            ),
            SettingsRow(
                title = stringResource(R.string.server_list_title),
                subtitle = stringResource(R.string.settings_server_accounts_subtitle),
                icon = Icons.Outlined.Storage,
                accent = SettingsAccent.Gray,
                onClick = onOpenAccount,
            ),
            SettingsRow(
                title = stringResource(R.string.settings_server_title),
                subtitle = stringResource(R.string.settings_server_subtitle),
                icon = Icons.Outlined.AdminPanelSettings,
                accent = SettingsAccent.Gray,
                onClick = onOpenServer,
            ),
        ),
    ),
    SettingsGroup(
        title = stringResource(R.string.settings_about),
        rows = listOf(
            SettingsRow(
                title = stringResource(R.string.settings_about_title),
                subtitle = stringResource(R.string.settings_about_subtitle),
                icon = Icons.Outlined.Info,
                accent = SettingsAccent.Gray,
                onClick = onOpenAbout,
            ),
            SettingsRow(
                title = stringResource(R.string.settings_licenses),
                subtitle = stringResource(R.string.settings_licenses_subtitle),
                icon = Icons.Outlined.Description,
                accent = SettingsAccent.Gray,
                onClick = onOpenLicenses,
            ),
        ),
    ),
)

/** Theme stored value to display label (consistent with the appearance screen's ThemeOptions). */
@Composable
private fun themeModeLabel(mode: String): String = when (mode) {
    "light" -> stringResource(R.string.appearance_theme_light)
    "dark" -> stringResource(R.string.appearance_theme_dark)
    else -> stringResource(R.string.appearance_theme_system)
}

/**
 * Height estimate used only to balance the two columns of the wide layout: a row is taller when it
 * carries a subtitle, so a card with describing rows lands where it fits without a measuring pass.
 */
private fun estimatedCardHeight(group: SettingsGroup): Int =
    group.rows.sumOf { row -> if (row.subtitle.isNullOrEmpty()) 56 else 72 }

/**
 * Splits the hub into two level columns, keeping the section order: each card goes to the column that
 * is currently shorter. Greedy is enough here because the cards are few and their heights are known.
 */
internal fun balanceSettingsGroups(
    groups: List<SettingsGroup>,
): Pair<List<SettingsGroup>, List<SettingsGroup>> {
    val left = mutableListOf<SettingsGroup>()
    val right = mutableListOf<SettingsGroup>()
    var leftHeight = 0
    var rightHeight = 0
    groups.forEach { group ->
        val height = estimatedCardHeight(group)
        if (leftHeight <= rightHeight) {
            left += group
            leftHeight += height
        } else {
            right += group
            rightHeight += height
        }
    }
    return left to right
}
