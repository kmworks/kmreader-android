package com.kmworks.kmreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.download.DownloadedIndex
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.core.network.ServerConfig
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.sse.SseConnectionState
import com.kmworks.kmreader.core.sse.SseContentSync
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Startup session gate.
 *
 * The saved server config lives in DataStore, so its first value only arrives after the disk read.
 * Starting the gate at "signed out" would therefore flash the login page for a frame on every cold
 * start (and, on a slow device, for long enough to see); [Loading] keeps that frame empty instead.
 */
enum class SessionGate { Loading, SignedIn, SignedOut }

/** Session gating: any configured instance counts as signed in; also drives SSE long-connection start/stop. */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val configStore: ServerConfigStore,
    private val appSettings: AppSettingsStore,
    private val sseService: SseService,
    private val sseContentSync: SseContentSync,
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    val config: StateFlow<ServerConfig?> = configStore.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Eagerly collected so the gate is already resolved when the first frame is drawn. */
    val sessionGate: StateFlow<SessionGate> = configStore.config
        .map { config -> if (config?.isConfigured == true) SessionGate.SignedIn else SessionGate.SignedOut }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionGate.Loading)

    val sseConnectionState: StateFlow<SseConnectionState> = sseService.connectionState

    val sseLastEventAt: StateFlow<Long?> = sseService.lastEventAt

    val sseEnabled: StateFlow<Boolean> = appSettings.sseEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Downloaded books (and series aggregates) for the card badges; refreshed whenever a download finishes or is removed. */
    val downloadedIndex: StateFlow<DownloadedIndex> = downloadRepository.downloadedIndex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadedIndex())

    /** PDF reader choice: dedicated PDF reader (true) or the DIVINA comic reader (false). */
    val useNativePdfReader: StateFlow<Boolean> = appSettings.useNativePdfReader
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        // SSE: hold the long connection while a session exists and the switch is on, otherwise disconnect
        viewModelScope.launch {
            combine(sessionManager.clients, appSettings.sseEnabled) { clients, enabled ->
                clients to enabled
            }
                .distinctUntilChanged()
                .collect { (clients, enabled) ->
                    if (clients != null && enabled) sseService.start(clients) else sseService.stop()
                }
        }
        // Turn the events on that connection into row-level changes for the screens that show those rows
        sseContentSync.start()
        // SessionExpired event -> automatic logout (logout is idempotent: SSE stops along with it)
        viewModelScope.launch {
            sseService.events.collect { event ->
                if (event.kind == SseEventKind.Session) logout()
            }
        }
    }

    /** "Visit Series" navigation: book -> owning series stableId. */
    suspend fun seriesStableIdForBook(bookStableId: Int): Int? =
        detailRepository.seriesStableIdForBook(bookStableId)

    /** Oneshot series navigation (mirrors iOS navDestination.oneshotDetail): enter the single-book detail with its only book. */
    suspend fun oneshotBookForSeries(seriesStableId: Int): Book? =
        detailRepository.seriesDtoId(seriesStableId)
            ?.let { detailRepository.seriesBooks(it) }
            ?.firstOrNull()
            ?.toUiBook()

    fun setSseEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setSseEnabled(enabled) }
    }

    /** Logout: clear the active session key but keep saved instance rows (mirrors the iOS default logout; you can switch back afterwards). */
    fun logout() {
        val clients = sessionManager.clients.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                clients?.serverApi?.logout()?.execute()
            } catch (_: Exception) {
                // fire-and-forget: a failure does not block the local logout
            }
        }
        viewModelScope.launch { configStore.clearActiveSession() }
    }

    override fun onCleared() {
        sseService.stop()
    }
}
