package com.kmworks.kmreader.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
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
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Smallest screen width that marks a device as a tablet (Android's own sw600dp split point). */
private const val TabletMinWidthDp = 600

/** Narrowest window that fits the hub beside a readable detail pane. */
private const val SplitMinWidthDp = 840

/** Width of the hub column in the split layout. */
private val SidebarWidth = 360.dp

/** Breathing room between the section cards of the hub list. */
private val CardGap = 16.dp

/**
 * Whether the settings hub shows the master/detail split: a tablet, in a window wide enough that the hub
 * column can stand next to the page content instead of covering it.
 */
internal fun isSettingsSplitLayout(smallestScreenWidthDp: Int, screenWidthDp: Int): Boolean =
    smallestScreenWidthDp >= TabletMinWidthDp && screenWidthDp >= SplitMinWidthDp

/**
 * Settings hub (mirrors SettingsView). Entries are grouped iOS-style into cards with a colored icon badge
 * per row. On a phone a row opens a pushed page; on a tablet with enough width the hub keeps the left
 * column and the chosen page renders in the right one, and that choice is made here so the rows stay the
 * same in both presentations.
 *
 * @param onOpenPage presented a destination when the hub drives navigation itself (phone layout).
 * @param splitDetail detail pane of the split layout; receives the page, the callback that clears the
 * selection, and the callback that switches to another page without going back through the hub.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit = {},
    splitDetail: (@Composable (page: SettingsPage, onDismiss: () -> Unit, onNavigate: (SettingsPage) -> Unit) -> Unit)? = null,
    viewModel: SettingsEntryViewModel = hiltViewModel(),
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    // The true-black quick toggle and the appearance screen read/write the same AppSettingsStore.trueBlackDark, so both stay in sync
    val trueBlack by prefsViewModel.trueBlackDark.collectAsStateWithLifecycle()
    val themeMode by prefsViewModel.themeMode.collectAsStateWithLifecycle()
    val serverConfig by rootViewModel.config.collectAsStateWithLifecycle()
    // kmrs capability gate: entry subtitles hint when the ReadingStats/Trackers features are missing
    val statsSupported by viewModel.readingStatsSupported.collectAsState()
    val trackersSupported by viewModel.trackersSupported.collectAsState()

    val configuration = LocalConfiguration.current
    val detail = splitDetail
    val split = detail != null &&
        isSettingsSplitLayout(configuration.smallestScreenWidthDp, configuration.screenWidthDp)
    // The chosen pane survives a configuration change; the name of the page is enough to restore it.
    var selectedPageName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPage = selectedPageName?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
    // In the split layout a row selects the pane beside the hub; otherwise the host presents it as a page.
    val navigate: (SettingsPage) -> Unit = if (split) {
        { page -> selectedPageName = page.name }
    } else {
        onOpenPage
    }

    val groups = settingsGroups(
        themeMode = themeMode,
        trueBlack = trueBlack,
        onTrueBlackChange = prefsViewModel::setTrueBlackDark,
        navigate = navigate,
        selectedPage = selectedPage.takeIf { split },
        trackersSupported = trackersSupported,
        statsSupported = statsSupported,
        currentInstance = serverConfig?.normalizedBaseUrl,
        account = when {
            serverConfig == null -> null
            serverConfig?.useApiKey == true -> "API Key"
            else -> serverConfig?.username.orEmpty()
        },
    )

    if (detail != null && split) {
        // System back leaves the open page before it leaves the settings hub.
        BackHandler(enabled = selectedPage != null) { selectedPageName = null }
        // A page layer only paints what the page itself draws, and the split leaves part of the window to
        // the pane. Paint the whole split on the page surface: otherwise the tab skeleton shows through the
        // empty pane (and the floating nav bar with it), and taps on that empty space would reach it too.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                SettingsHub(onBack = onBack, groups = groups, modifier = Modifier.width(SidebarWidth))
                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val page = selectedPage
                    if (page == null) {
                        SettingsDetailPlaceholder()
                    } else {
                        detail(
                            page,
                            { selectedPageName = null },
                            { target -> selectedPageName = target.name },
                        )
                    }
                }
            }
        }
    } else {
        SettingsHub(onBack = onBack, groups = groups)
    }
}

/** The hub itself: a titled, scrollable list of section cards (one column, whatever the window width). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHub(
    onBack: () -> Unit,
    groups: List<SettingsGroup>,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            items(groups, key = { it.title }) { group ->
                SettingsGroupCard(group)
            }
        }
    }
}

/** Right pane of the split layout before a row is chosen. */
@Composable
private fun SettingsDetailPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_split_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Hub content in the order of the iOS settings form, each section card tagged with its iOS badge
 * icon and accent color. Only the sections of this app exist here; capabilities without kmrs support
 * keep their hint subtitles. [selectedPage] is set only by the split layout, where the open page is
 * marked in the hub.
 */
@Composable
private fun settingsGroups(
    themeMode: String,
    trueBlack: Boolean,
    onTrueBlackChange: (Boolean) -> Unit,
    navigate: (SettingsPage) -> Unit,
    selectedPage: SettingsPage?,
    trackersSupported: Boolean,
    statsSupported: Boolean,
    currentInstance: String?,
    account: String?,
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
                selected = selectedPage == SettingsPage.Appearance,
                onClick = { navigate(SettingsPage.Appearance) },
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
                selected = selectedPage == SettingsPage.Browse,
                onClick = { navigate(SettingsPage.Browse) },
            ),
            SettingsRow(
                title = stringResource(R.string.settings_dashboard_title),
                subtitle = stringResource(R.string.settings_dashboard_subtitle),
                icon = Icons.Outlined.Dashboard,
                accent = SettingsAccent.Orange,
                selected = selectedPage == SettingsPage.Dashboard,
                onClick = { navigate(SettingsPage.Dashboard) },
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
                selected = selectedPage == SettingsPage.ReaderDefaults,
                onClick = { navigate(SettingsPage.ReaderDefaults) },
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
                selected = selectedPage == SettingsPage.Trackers,
                onClick = { navigate(SettingsPage.Trackers) },
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
                selected = selectedPage == SettingsPage.Stats,
                onClick = { navigate(SettingsPage.Stats) },
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
                selected = selectedPage == SettingsPage.CustomFonts,
                onClick = { navigate(SettingsPage.CustomFonts) },
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
                selected = selectedPage == SettingsPage.Cache,
                onClick = { navigate(SettingsPage.Cache) },
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
                selected = selectedPage == SettingsPage.Network,
                onClick = { navigate(SettingsPage.Network) },
            ),
            SettingsRow(
                title = stringResource(R.string.settings_sse),
                subtitle = stringResource(R.string.settings_sse_subtitle),
                icon = Icons.Outlined.Sensors,
                accent = SettingsAccent.Orange,
                selected = selectedPage == SettingsPage.Sse,
                onClick = { navigate(SettingsPage.Sse) },
            ),
            SettingsRow(
                title = stringResource(R.string.settings_logs),
                subtitle = stringResource(R.string.settings_logs_subtitle),
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                accent = SettingsAccent.Brown,
                selected = selectedPage == SettingsPage.Logs,
                onClick = { navigate(SettingsPage.Logs) },
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
                selected = selectedPage == SettingsPage.Account,
                onClick = { navigate(SettingsPage.Account) },
            ),
            SettingsRow(
                title = stringResource(R.string.settings_server_title),
                subtitle = stringResource(R.string.settings_server_subtitle),
                icon = Icons.Outlined.AdminPanelSettings,
                accent = SettingsAccent.Gray,
                selected = selectedPage == SettingsPage.Server,
                onClick = { navigate(SettingsPage.Server) },
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
                selected = selectedPage == SettingsPage.About,
                onClick = { navigate(SettingsPage.About) },
            ),
            SettingsRow(
                title = stringResource(R.string.settings_licenses),
                subtitle = stringResource(R.string.settings_licenses_subtitle),
                icon = Icons.Outlined.Description,
                accent = SettingsAccent.Gray,
                selected = selectedPage == SettingsPage.Licenses,
                onClick = { navigate(SettingsPage.Licenses) },
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
