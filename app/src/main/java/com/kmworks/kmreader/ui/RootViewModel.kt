package com.kmworks.kmreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.network.ServerConfig
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.sse.SseConnectionState
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 会话门控：有已配置实例即视为已登录；同时驱动 SSE 长连启停。 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val configStore: ServerConfigStore,
    private val appSettings: AppSettingsStore,
    private val sseService: SseService,
    sessionManager: SessionManager,
) : ViewModel() {

    val config: StateFlow<ServerConfig?> = configStore.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hasSession: StateFlow<Boolean> = configStore.config
        .map { it?.isConfigured == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sseConnectionState: StateFlow<SseConnectionState> = sseService.connectionState

    val sseLastEventAt: StateFlow<Long?> = sseService.lastEventAt

    val sseEnabled: StateFlow<Boolean> = appSettings.sseEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        // SSE：有会话且开关打开时长连，否则断开
        viewModelScope.launch {
            combine(sessionManager.clients, appSettings.sseEnabled) { clients, enabled ->
                clients to enabled
            }
                .distinctUntilChanged()
                .collect { (clients, enabled) ->
                    if (clients != null && enabled) sseService.start(clients) else sseService.stop()
                }
        }
        // SessionExpired 事件 → 自动登出（logout 幂等：sse 随之 stop）
        viewModelScope.launch {
            sseService.events.collect { event ->
                if (event.kind == SseEventKind.Session) logout()
            }
        }
    }

    fun setSseEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setSseEnabled(enabled) }
    }

    /** 清除实例配置，门控自动回到登录页。 */
    fun logout() {
        viewModelScope.launch { configStore.clear() }
    }

    override fun onCleared() {
        sseService.stop()
    }
}
