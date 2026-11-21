package com.kmworks.kmreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.CoverUrlRegistry
import com.kmworks.kmreader.core.network.ServerConfig
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.ServerInstance
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Server list screen state (mirrors iOS ServerListView: sorted by lastUsedAt descending, current instance checked). */
@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val configStore: ServerConfigStore,
) : ViewModel() {

    val instances: StateFlow<List<ServerInstance>> = configStore.instances
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeConfig: StateFlow<ServerConfig?> = configStore.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _switchingInstanceId = MutableStateFlow<String?>(null)

    /** Instance currently being activated; the card shows a progress indicator (iOS isSwitching). */
    val switchingInstanceId: StateFlow<String?> = _switchingInstanceId.asStateFlow()

    /**
     * Switches the active instance (mirrors iOS switchTo): writes the instance credentials back
     * to the active config, SessionManager's clients flow rebuilds automatically (SSE reconnects
     * with it), and lastUsedAt is refreshed.
     */
    fun switchTo(instance: ServerInstance) {
        if (_switchingInstanceId.value != null) return
        _switchingInstanceId.value = instance.id
        viewModelScope.launch {
            try {
                // Stale instance ids must not leak onto another server
                CoverUrlRegistry.clear()
                // Cached rows are keyed per server instance and deliberately kept: switching back to a
                // previously used server lets CatalogCache seed the first screen immediately (mirrors
                // iOS, which keeps a per-instance cache namespace instead of dropping it on switch).
                configStore.save(instance.toConfig())
                configStore.touchInstance(instance.toConfig())
            } finally {
                _switchingInstanceId.value = null
            }
        }
    }

    /** Deletes an instance row (including its credentials). Logging out the active instance is performed first by the caller (RootViewModel.logout). */
    fun delete(instance: ServerInstance) {
        viewModelScope.launch { configStore.deleteInstance(instance.id) }
    }
}
