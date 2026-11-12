package com.kmworks.kmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.settings.DivinaSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * DIVINA 阅读器设置的读写：设置面板改动立即生效并持久化（对齐原作 @AppStorage 行为），
 * 同时提供默认阅读方向（设置-阅读器默认值里持久化的 divinaDefaultDirection）。
 */
@HiltViewModel
class DivinaSettingsViewModel @Inject constructor(
    private val store: DivinaSettingsStore,
    private val appSettings: AppSettingsStore,
) : ViewModel() {

    private val _settings = MutableStateFlow(DivinaSettings())
    val settings: StateFlow<DivinaSettings> = _settings.asStateFlow()

    /** 设置里保存的默认阅读方向（菜单里改方向会同步保存）。 */
    val defaultDirection: StateFlow<ReadingDirection> = appSettings.divinaDefaultDirection
        .map { raw -> ReadingDirection.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ReadingDirection.Ltr }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReadingDirection.Ltr)

    init {
        viewModelScope.launch {
            store.settings.collect { _settings.value = it }
        }
    }

    /** 本地先应用（UI 立即生效），再落盘。 */
    fun update(transform: (DivinaSettings) -> DivinaSettings) {
        _settings.value = transform(_settings.value)
        viewModelScope.launch { store.update(transform) }
    }

    /** 阅读方向沿用「设置-阅读器默认值」里的默认方向（与原作同一 AppStorage 语义）。 */
    fun setDefaultDirection(direction: ReadingDirection) {
        viewModelScope.launch { appSettings.setDivinaDefaultDirection(direction.name.lowercase()) }
    }
}
