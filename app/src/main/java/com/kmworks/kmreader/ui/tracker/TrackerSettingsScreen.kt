package com.kmworks.kmreader.ui.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
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
    var showLedger by remember { mutableStateOf(false) }

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
                            if (realMode) viewModel.logoutProvider(account.provider)
                        }) { Text("断开") }
                    } else {
                        TextButton(onClick = { }) { Text("去授权") }
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
            SettingsEntryRow(title = "绑定管理", subtitle = "按 provider 过滤、解绑", onClick = { })
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
    sessionManager: SessionManager,
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
}
