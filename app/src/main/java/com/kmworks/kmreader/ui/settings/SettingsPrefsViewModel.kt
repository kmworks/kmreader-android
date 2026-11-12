package com.kmworks.kmreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.settings.AppSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页共享偏好（AppSettingsStore 持久化 + client-settings 云同步）。
 * 外观/阅读器默认等设置页通过它读写，落盘即自动获得上行同步。
 */
@HiltViewModel
class SettingsPrefsViewModel @Inject constructor(
    private val appSettings: AppSettingsStore,
) : ViewModel() {

    val themeMode: StateFlow<String> = appSettings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsStore.THEME_SYSTEM)

    val trueBlackDark: StateFlow<Boolean> = appSettings.trueBlackDark
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val divinaDefaultDirection: StateFlow<String> = appSettings.divinaDefaultDirection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsStore.DEFAULT_DIRECTION)

    fun setThemeMode(mode: String) {
        viewModelScope.launch { appSettings.setThemeMode(mode) }
    }

    fun setTrueBlackDark(enabled: Boolean) {
        viewModelScope.launch { appSettings.setTrueBlackDark(enabled) }
    }

    fun setDivinaDefaultDirection(direction: String) {
        viewModelScope.launch { appSettings.setDivinaDefaultDirection(direction) }
    }
}
