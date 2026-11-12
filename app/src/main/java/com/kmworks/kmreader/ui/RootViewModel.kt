package com.kmworks.kmreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.network.ServerConfig
import com.kmworks.kmreader.core.network.ServerConfigStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 会话门控：有已配置实例即视为已登录；同时暴露当前配置与登出。 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val configStore: ServerConfigStore,
) : ViewModel() {

    val config: StateFlow<ServerConfig?> = configStore.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hasSession: StateFlow<Boolean> = configStore.config
        .map { it?.isConfigured == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 清除实例配置，门控自动回到登录页。 */
    fun logout() {
        viewModelScope.launch { configStore.clear() }
    }
}
