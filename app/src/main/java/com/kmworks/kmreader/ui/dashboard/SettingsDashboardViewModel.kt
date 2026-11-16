package com.kmworks.kmreader.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.DashboardSectionSetting
import com.kmworks.kmreader.data.SampleDashboardData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 仪表盘自定义（对齐 SettingsDashboardView 的 AppStorage DashboardConfiguration）：
 * 可见分区 = 有顺序的 id 列表，隐藏分区 = 不在该列表内的 id。
 * 全部持久化在 AppSettingsStore（DataStore），仪表盘按同一份配置渲染。
 */
data class DashboardSettingsUiState(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val cardKindMenu: Boolean = true,
    val continuation: Boolean = true,
    val overrides: Map<String, DashboardCardKind> = emptyMap(),
) {
    /** 可见分区，按持久化顺序；未定制过的分区按默认顺序追加。 */
    val visibleSections: List<DashboardSectionSetting>
        get() {
            val all = SampleDashboardData.dashboardSections
            val byId = all.associateBy { it.id }
            val ordered = order.mapNotNull { byId[it] }.filter { it.id !in hidden }
            val rest = all.filter { setting ->
                ordered.none { it.id == setting.id } && setting.id !in hidden
            }
            return ordered + rest
        }

    val hiddenSections: List<DashboardSectionSetting>
        get() = SampleDashboardData.dashboardSections.filter { it.id in hidden }
}

@HiltViewModel
class SettingsDashboardViewModel @Inject constructor(
    private val appSettings: AppSettingsStore,
) : ViewModel() {

    val state: StateFlow<DashboardSettingsUiState> = combine(
        appSettings.dashboardSectionOrder,
        appSettings.dashboardHiddenSections,
        appSettings.dashboardCardKindMenu,
        appSettings.dashboardContinuationEnabled,
        appSettings.dashboardKindOverrides,
    ) { order, hidden, kindMenu, continuation, overrides ->
        DashboardSettingsUiState(
            order = order,
            hidden = hidden,
            cardKindMenu = kindMenu,
            continuation = continuation,
            overrides = overrides.mapNotNull { (id, value) ->
                DashboardCardKind.entries.firstOrNull { it.name == value }?.let { id to it }
            }.toMap(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardSettingsUiState(),
    )

    fun move(sectionId: String, delta: Int) {
        val ids = state.value.visibleSections.map { it.id }.toMutableList()
        val index = ids.indexOf(sectionId)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, ids.lastIndex)
        if (target == index) return
        ids.add(target, ids.removeAt(index))
        viewModelScope.launch { appSettings.setDashboardSectionOrder(ids) }
    }

    /** 拖拽排序结束后整体提交可见分区顺序（对齐 iOS .onMove 的持久化时机）。 */
    fun setOrder(ids: List<String>) {
        viewModelScope.launch { appSettings.setDashboardSectionOrder(ids) }
    }

    fun setVisible(sectionId: String, visible: Boolean) {
        val current = state.value
        val visibleIds = current.visibleSections.map { it.id }.toMutableList()
        viewModelScope.launch {
            if (visible) {
                if (sectionId !in visibleIds) visibleIds.add(sectionId)
                appSettings.setDashboardHiddenSections(current.hidden - sectionId)
                appSettings.setDashboardSectionOrder(visibleIds)
            } else {
                visibleIds.remove(sectionId)
                appSettings.setDashboardSectionOrder(visibleIds)
                appSettings.setDashboardHiddenSections(current.hidden + sectionId)
            }
        }
    }

    fun setCardKindMenu(enabled: Boolean) {
        viewModelScope.launch { appSettings.setDashboardCardKindMenu(enabled) }
    }

    fun setContinuation(enabled: Boolean) {
        viewModelScope.launch { appSettings.setDashboardContinuationEnabled(enabled) }
    }

    fun setSectionKind(sectionId: String, kind: DashboardCardKind?) {
        viewModelScope.launch {
            appSettings.setDashboardKindOverride(sectionId, kind?.name)
        }
    }

    fun reset() {
        viewModelScope.launch {
            SampleDashboardData.dashboardSections.forEach { setting ->
                appSettings.setDashboardKindOverride(setting.id, null)
            }
            appSettings.setDashboardSectionOrder(emptyList())
            appSettings.setDashboardHiddenSections(emptySet())
            appSettings.setDashboardCardKindMenu(true)
            appSettings.setDashboardContinuationEnabled(true)
        }
    }
}
