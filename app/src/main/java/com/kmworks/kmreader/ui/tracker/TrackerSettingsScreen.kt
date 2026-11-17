package com.kmworks.kmreader.ui.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.core.tracker.TrackerLedgerItemDto
import com.kmworks.kmreader.core.tracker.TrackerLinkCountsDto
import com.kmworks.kmreader.core.tracker.TrackerLinkDto
import com.kmworks.kmreader.core.tracker.TrackerLinkGroupDto
import com.kmworks.kmreader.core.tracker.TrackerPreferencesDto
import com.kmworks.kmreader.core.tracker.TrackerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings · Tracker accounts (aligned with TrackerAccountsView):
 * provider account rows (connected shows the username / not connected offers an authorization entry) + default tracker + library scope + bindings management/update ledger.
 * Account authorization is completed in the server web interface at /account/trackers; the app never holds tokens.
 * With a session and kmrs Trackers support this uses real data; without a session it falls back to demo data; without support it shows a capability notice.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackerSettingsScreen(
    onBack: () -> Unit,
    viewModel: TrackerSettingsViewModel = hiltViewModel(),
) {
    val hasSession by viewModel.hasSession.collectAsStateWithLifecycle()
    val supported by viewModel.supported.collectAsStateWithLifecycle()
    val accountMap by viewModel.accounts.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val ledger by viewModel.ledger.collectAsStateWithLifecycle()
    val bindings by viewModel.bindings.collectAsStateWithLifecycle()
    val bindingCounts by viewModel.bindingCounts.collectAsStateWithLifecycle()
    val bindingProvider by viewModel.bindingProvider.collectAsStateWithLifecycle()
    val bindingsLoading by viewModel.bindingsLoading.collectAsStateWithLifecycle()
    val bindingsHasMore by viewModel.bindingsHasMore.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val authorizationUrl = viewModel.trackerAccountUrl
    var showLedger by remember { mutableStateOf(false) }
    var showBindings by remember { mutableStateOf(false) }
    var pendingDisconnect by remember { mutableStateOf<TrackerProvider?>(null) }
    var pendingUnlink by remember { mutableStateOf<TrackerLinkDto?>(null) }

    // Local state for demo mode (no session)
    val realMode = hasSession && supported
    var demoDefaultTracker by remember { mutableStateOf(TrackerProvider.AniList) }
    val demoLibraries = remember { listOf("Comics", "Manga", "Manhua") }
    var demoSelectedLibraries by remember { mutableStateOf(setOf<String>()) }

    val accounts: List<TrackerAccount> = if (realMode) {
        TrackerProvider.entries.map { provider ->
            accountMap[provider] ?: TrackerAccount(provider, connectedUser = null)
        }
    } else {
        SampleTrackerAccounts
    }
    val defaultTracker: TrackerProvider = if (realMode) {
        preferences?.defaultTracker?.let(::trackerProviderFromApi) ?: TrackerProvider.AniList
    } else {
        demoDefaultTracker
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tracker_trackers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (hasSession && !supported) {
                Text(
                    stringResource(R.string.tracker_unsupported_server),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }

            TrackerSettingsTitle(stringResource(R.string.tracker_accounts))
            accounts.forEach { account ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProviderLogo(account.provider)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            account.provider.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            account.connectedUser?.let {
                                stringResource(R.string.tracker_connected_user, it)
                            } ?: stringResource(R.string.tracker_not_connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (account.connectedUser != null) {
                        TextButton(onClick = {
                            if (realMode) pendingDisconnect = account.provider
                        }) { Text(stringResource(R.string.tracker_disconnect)) }
                    } else {
                        TextButton(
                            onClick = { authorizationUrl?.let(uriHandler::openUri) },
                            enabled = realMode && authorizationUrl != null,
                        ) { Text(stringResource(R.string.tracker_connect_on_web)) }
                    }
                }
            }
            Text(
                stringResource(R.string.tracker_accounts_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            TrackerSettingsTitle(stringResource(R.string.tracker_default_tracker))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackerProvider.entries.forEach { provider ->
                    FilterChip(
                        selected = defaultTracker == provider,
                        onClick = {
                            if (realMode) viewModel.setDefaultTracker(provider)
                            else demoDefaultTracker = provider
                        },
                        label = { Text(provider.displayName) },
                    )
                }
            }

            TrackerSettingsTitle(stringResource(R.string.tracker_libraries))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (realMode) {
                    libraries.forEach { (libraryId, name) ->
                        FilterChip(
                            selected = libraryId in preferences?.libraries.orEmpty(),
                            onClick = { viewModel.toggleLibrary(libraryId) },
                            label = { Text(name) },
                        )
                    }
                } else {
                    demoLibraries.forEach { library ->
                        FilterChip(
                            selected = library in demoSelectedLibraries,
                            onClick = {
                                demoSelectedLibraries =
                                    if (library in demoSelectedLibraries) {
                                        demoSelectedLibraries - library
                                    } else {
                                        demoSelectedLibraries + library
                                    }
                            },
                            label = { Text(library) },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.tracker_libraries_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SettingsEntryRow(
                title = stringResource(R.string.tracker_linked_series),
                subtitle = stringResource(R.string.tracker_bindings_subtitle),
                onClick = {
                    showBindings = true
                    if (realMode) viewModel.loadBindings(refresh = true)
                },
            )
            SettingsEntryRow(
                title = stringResource(R.string.tracker_recent_updates),
                subtitle = stringResource(R.string.tracker_ledger_subtitle),
                expanded = showLedger,
                onClick = {
                    showLedger = !showLedger
                    if (showLedger && realMode) viewModel.loadLedger()
                },
            )
            if (showLedger) {
                val items = if (realMode) ledger.orEmpty() else emptyList()
                if (items.isEmpty()) {
                    Text(
                        if (realMode) {
                            stringResource(R.string.tracker_no_updates)
                        } else {
                            stringResource(R.string.tracker_demo_no_ledger)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items.forEach { item ->
                    LedgerRow(item)
                }
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    pendingDisconnect?.let { provider ->
        AlertDialog(
            onDismissRequest = { pendingDisconnect = null },
            title = { Text(stringResource(R.string.tracker_disconnect_tracker)) },
            text = {
                Text(stringResource(R.string.tracker_disconnect_message, provider.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDisconnect = null
                    viewModel.logoutProvider(provider)
                }) { Text(stringResource(R.string.tracker_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisconnect = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showBindings) {
        TrackerBindingsDialog(
            groups = bindings.orEmpty(),
            counts = bindingCounts,
            provider = bindingProvider,
            loading = bindingsLoading,
            hasMore = bindingsHasMore,
            realMode = realMode,
            onProviderSelected = viewModel::setBindingProvider,
            onLoadMore = viewModel::loadMoreBindings,
            onOpenLink = { link ->
                val provider = trackerProviderFromApi(link.provider)
                uriHandler.openUri(provider.entryUrl(link.trackId))
            },
            onUnlink = { pendingUnlink = it },
            onDismiss = { showBindings = false },
        )
    }

    pendingUnlink?.let { link ->
        AlertDialog(
            onDismissRequest = { pendingUnlink = null },
            title = { Text(stringResource(R.string.tracker_unlink_tracker)) },
            text = { Text(stringResource(R.string.tracker_unlink_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnlink = null
                    viewModel.deleteBinding(link)
                }) { Text(stringResource(R.string.tracker_unlink)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnlink = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun TrackerBindingsDialog(
    groups: List<TrackerLinkGroupDto>,
    counts: com.kmworks.kmreader.core.tracker.TrackerLinkCountsDto?,
    provider: TrackerProvider?,
    loading: Boolean,
    hasMore: Boolean,
    realMode: Boolean,
    onProviderSelected: (TrackerProvider?) -> Unit,
    onLoadMore: () -> Unit,
    onOpenLink: (TrackerLinkDto) -> Unit,
    onUnlink: (TrackerLinkDto) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracker_linked_series)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == null,
                        onClick = { onProviderSelected(null) },
                        label = {
                            Text(
                                counts?.series?.let {
                                    stringResource(R.string.tracker_all_with_count, it)
                                } ?: stringResource(R.string.common_all)
                            )
                        },
                    )
                    TrackerProvider.entries.forEach { item ->
                        FilterChip(
                            selected = provider == item,
                            onClick = { onProviderSelected(item) },
                            label = {
                                Text(
                                    counts?.providers?.get(item.apiName)
                                        ?.let {
                                            stringResource(
                                                R.string.tracker_provider_with_count,
                                                item.displayName,
                                                it,
                                            )
                                        }
                                        ?: item.displayName,
                                )
                            },
                        )
                    }
                }
                if (!realMode) {
                    Text(
                        stringResource(R.string.tracker_demo_no_bindings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (groups.isEmpty() && !loading) {
                    Text(
                        stringResource(R.string.tracker_no_bindings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(groups, key = { it.seriesId }) { group ->
                            TrackerBindingGroupRow(
                                group = group,
                                onOpenLink = onOpenLink,
                                onUnlink = onUnlink,
                            )
                        }
                        if (loading) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        } else if (hasMore) {
                            item {
                                TextButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.tracker_load_more)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

@Composable
private fun TrackerBindingGroupRow(
    group: TrackerLinkGroupDto,
    onOpenLink: (TrackerLinkDto) -> Unit,
    onUnlink: (TrackerLinkDto) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            group.links.firstOrNull()?.title ?: group.seriesId,
            style = MaterialTheme.typography.bodyLarge,
        )
        group.links.forEach { link ->
            val linkProvider = trackerProviderFromApi(link.provider)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderLogo(linkProvider)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(linkProvider.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "#${link.trackId} · ${link.trackMode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onOpenLink(link) }) { Text(stringResource(R.string.common_open)) }
                TextButton(onClick = { onUnlink(link) }) { Text(stringResource(R.string.tracker_unlink)) }
            }
        }
    }
}

@Composable
private fun LedgerRow(item: TrackerLedgerItemDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderLogo(trackerProviderFromApi(item.provider))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title ?: item.trackId,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "#${item.trackId} · ${formatLedgerTime(item.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ledgerTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatLedgerTime(epochSeconds: Double): String =
    ledgerTimeFormatter.format(Instant.ofEpochSecond(epochSeconds.toLong()))

@Composable
private fun TrackerSettingsTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    expanded: Boolean? = null,
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
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
                if (expanded == true) Icons.Filled.ExpandLess else Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tracker settings data (accounts/preferences/libraries/ledger); everything goes through the capability-gated TrackerRepository. */
@HiltViewModel
class TrackerSettingsViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    serverProfileStore: ServerProfileStore,
    private val trackerRepository: TrackerRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val hasSession: StateFlow<Boolean> = sessionManager.clients
        .map { it != null }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            sessionManager.clients.value != null,
        )

    val supported: StateFlow<Boolean> = serverProfileStore.profile
        .map { it?.features?.contains(ServerFeature.Trackers) == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _accounts = MutableStateFlow<Map<TrackerProvider, TrackerAccount>>(emptyMap())
    val accounts: StateFlow<Map<TrackerProvider, TrackerAccount>> = _accounts.asStateFlow()

    private val _preferences = MutableStateFlow<TrackerPreferencesDto?>(null)
    val preferences: StateFlow<TrackerPreferencesDto?> = _preferences.asStateFlow()

    /** Library id → name. */
    private val _libraries = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val libraries: StateFlow<List<Pair<String, String>>> = _libraries.asStateFlow()

    private val _ledger = MutableStateFlow<List<TrackerLedgerItemDto>?>(null)
    val ledger: StateFlow<List<TrackerLedgerItemDto>?> = _ledger.asStateFlow()

    /** Server-side tracker account management page (aligned with iOS serverURL + /account/trackers). */
    val trackerAccountUrl: String?
        get() = sessionManager.clients.value?.baseUrl
            ?.trimEnd('/')
            ?.plus("/account/trackers")

    private val _bindings = MutableStateFlow<List<TrackerLinkGroupDto>?>(null)
    val bindings: StateFlow<List<TrackerLinkGroupDto>?> = _bindings.asStateFlow()

    private val _bindingCounts = MutableStateFlow<TrackerLinkCountsDto?>(null)
    val bindingCounts: StateFlow<TrackerLinkCountsDto?> = _bindingCounts.asStateFlow()

    private val _bindingProvider = MutableStateFlow<TrackerProvider?>(null)
    val bindingProvider: StateFlow<TrackerProvider?> = _bindingProvider.asStateFlow()

    private val _bindingsLoading = MutableStateFlow(false)
    val bindingsLoading: StateFlow<Boolean> = _bindingsLoading.asStateFlow()

    private val _bindingsHasMore = MutableStateFlow(true)
    val bindingsHasMore: StateFlow<Boolean> = _bindingsHasMore.asStateFlow()

    private var bindingsPage = 0

    init {
        viewModelScope.launch {
            supported.collect { if (it) loadAll() }
        }
    }

    private suspend fun loadAll() {
        loadAccounts()
        _preferences.value = trackerRepository.preferences()
        _libraries.value = libraryRepository.libraries().map { it.id to it.name }
    }

    private suspend fun loadAccounts() {
        val loaded = coroutineScope {
            TrackerProvider.entries.map { provider ->
                async { provider to trackerRepository.oauthStatus(provider.apiName) }
            }.awaitAll()
        }
        _accounts.value = loaded.mapNotNull { (provider, dto) ->
            dto?.let { account ->
                provider to TrackerAccount(
                    provider = provider,
                    connectedUser = account.username.takeIf { account.loggedIn },
                )
            }
        }.toMap()
    }

    fun setDefaultTracker(provider: TrackerProvider) {
        val next = (_preferences.value ?: TrackerPreferencesDto())
            .copy(defaultTracker = provider.apiName)
        _preferences.value = next
        viewModelScope.launch { trackerRepository.savePreferences(next) }
    }

    fun toggleLibrary(libraryId: String) {
        val current = _preferences.value ?: TrackerPreferencesDto()
        val next = current.copy(
            libraries = if (libraryId in current.libraries) {
                current.libraries - libraryId
            } else {
                current.libraries + libraryId
            },
        )
        _preferences.value = next
        viewModelScope.launch { trackerRepository.savePreferences(next) }
    }

    fun logoutProvider(provider: TrackerProvider) {
        viewModelScope.launch {
            trackerRepository.logoutProvider(provider.apiName)
            loadAccounts()
        }
    }

    fun loadLedger() {
        viewModelScope.launch { _ledger.value = trackerRepository.ledger() }
    }

    /** Binding filter change (aligned with the iOS TrackerBindingsView provider picker). */
    fun setBindingProvider(provider: TrackerProvider?) {
        if (_bindingProvider.value == provider) return
        _bindingProvider.value = provider
        loadBindings(refresh = true)
    }

    /** Loads the first page of global bindings; refresh resets the current provider's pagination. */
    fun loadBindings(refresh: Boolean = false) {
        if (_bindingsLoading.value) return
        if (!refresh && !_bindingsHasMore.value) return
        _bindingsLoading.value = true
        viewModelScope.launch {
            if (refresh) {
                bindingsPage = 0
                _bindingsHasMore.value = true
                _bindings.value = emptyList()
            }
            val result = trackerRepository.links(
                page = bindingsPage,
                size = BINDINGS_PAGE_SIZE,
                provider = _bindingProvider.value?.apiName,
            )
            if (result == null) {
                _bindingsHasMore.value = false
            } else {
                _bindingCounts.value = result.counts
                _bindings.value = _bindings.value.orEmpty() + result.content
                _bindingsHasMore.value = !result.last
                if (result.last) bindingsPage = 0 else bindingsPage += 1
            }
            _bindingsLoading.value = false
        }
    }

    fun loadMoreBindings() = loadBindings(refresh = false)

    /** Unlinks a single tracker binding; removed locally on success, reloads the current filtered page on failure. */
    fun deleteBinding(link: TrackerLinkDto) {
        viewModelScope.launch {
            val removed = trackerRepository.deleteLink(link.seriesId, link.provider)
            if (removed) {
                _bindings.update { groups ->
                    groups.orEmpty().mapNotNull { group ->
                        if (group.seriesId != link.seriesId) {
                            group
                        } else {
                            val links = group.links.filterNot {
                                it.trackId == link.trackId && it.provider == link.provider
                            }
                            if (links.isEmpty()) null else group.copy(links = links)
                        }
                    }
                }
            } else {
                loadBindings(refresh = true)
            }
        }
    }

    private companion object {
        const val BINDINGS_PAGE_SIZE = 50
    }
}
