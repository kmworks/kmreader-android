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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
 * 设置 · Tracker 账号（对齐 TrackerAccountsView）：
 * provider 账号行（已连接显示用户名 / 未连接给出授权入口）+ 默认 Tracker + 库范围 + 绑定管理/更新台账。
 * 账号授权在服务器网页端 /account/trackers 完成，app 不持有 token。
 * 有会话且 kmrs 支持 Trackers 时走真实数据；无会话回退演示数据；不支持时显示能力提示。
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

    // 演示模式（无会话）的本地状态
    val realMode = hasSession && supported
    var demoDefaultTracker by remember { mutableStateOf(TrackerProvider.AniList) }
    val demoLibraries = remember { listOf("漫画", "日漫", "国漫") }
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
                title = { Text("阅读进度追踪") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                    "当前服务器不支持 Tracker（需要 kmrs 服务器的 trackers 能力）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }

            TrackerSettingsTitle("账号")
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
                            account.connectedUser?.let { "已连接 · $it" } ?: "未连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (account.connectedUser != null) {
                        TextButton(onClick = {
                            if (realMode) pendingDisconnect = account.provider
                        }) { Text("断开") }
                    } else {
                        TextButton(
                            onClick = { authorizationUrl?.let(uriHandler::openUri) },
                            enabled = realMode && authorizationUrl != null,
                        ) { Text("去授权") }
                    }
                }
            }
            Text(
                "账号授权在服务器网页端 /account/trackers 完成，应用不保存 token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            TrackerSettingsTitle("默认 Tracker")
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

            TrackerSettingsTitle("启用的库")
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
                "不选择任何库 = 全部库可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SettingsEntryRow(
                title = "绑定管理",
                subtitle = "按 provider 过滤、解绑",
                onClick = {
                    showBindings = true
                    if (realMode) viewModel.loadBindings(refresh = true)
                },
            )
            SettingsEntryRow(
                title = "更新台账",
                subtitle = "手动触发的 tracker 更新记录",
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
                        if (realMode) "暂无更新记录" else "演示模式无台账数据",
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
            title = { Text("断开 Tracker") },
            text = {
                Text("断开 ${provider.displayName} 会移除服务器上的授权；之后需要在服务器网页端重新连接。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDisconnect = null
                    viewModel.logoutProvider(provider)
                }) { Text("断开") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisconnect = null }) { Text("取消") }
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
            title = { Text("解绑 Tracker") },
            text = { Text("这会移除该系列的 Tracker 绑定，但不会修改远端条目。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnlink = null
                    viewModel.deleteBinding(link)
                }) { Text("解绑") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnlink = null }) { Text("取消") }
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
        title = { Text("绑定管理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == null,
                        onClick = { onProviderSelected(null) },
                        label = {
                            Text(counts?.series?.let { "全部 ($it)" } ?: "全部")
                        },
                    )
                    TrackerProvider.entries.forEach { item ->
                        FilterChip(
                            selected = provider == item,
                            onClick = { onProviderSelected(item) },
                            label = {
                                Text(
                                    counts?.providers?.get(item.apiName)
                                        ?.let { "${item.displayName} ($it)" }
                                        ?: item.displayName,
                                )
                            },
                        )
                    }
                }
                if (!realMode) {
                    Text(
                        "演示模式无绑定数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (groups.isEmpty() && !loading) {
                    Text(
                        "暂无已绑定系列；可在系列详情页建立 Tracker 绑定。",
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
                                ) { Text("加载更多") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
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
                TextButton(onClick = { onOpenLink(link) }) { Text("打开") }
                TextButton(onClick = { onUnlink(link) }) { Text("解绑") }
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

/** Tracker 设置数据（账号/偏好/库/台账）；全部经能力门控的 TrackerRepository。 */
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

    /** 库 id → 名称。 */
    private val _libraries = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val libraries: StateFlow<List<Pair<String, String>>> = _libraries.asStateFlow()

    private val _ledger = MutableStateFlow<List<TrackerLedgerItemDto>?>(null)
    val ledger: StateFlow<List<TrackerLedgerItemDto>?> = _ledger.asStateFlow()

    /** 服务器端 tracker 账号管理页（对齐 iOS serverURL + /account/trackers）。 */
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

    /** 绑定过滤变化（对齐 iOS TrackerBindingsView 的 provider picker）。 */
    fun setBindingProvider(provider: TrackerProvider?) {
        if (_bindingProvider.value == provider) return
        _bindingProvider.value = provider
        loadBindings(refresh = true)
    }

    /** 加载全局绑定第一页；refresh 会重置 provider 当前分页。 */
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

    /** 解绑单条 Tracker 绑定；成功后本地移除，失败时重载当前过滤页。 */
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
