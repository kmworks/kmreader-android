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
 * Dashboard customization (aligned with the AppStorage DashboardConfiguration of SettingsDashboardView):
 * visible sections = an ordered id list; hidden sections = ids not in that list.
 * Everything is persisted in AppSettingsStore (DataStore); the dashboard renders from the same configuration.
 */
data class DashboardSettingsUiState(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val cardKindMenu: Boolean = true,
    val continuation: Boolean = true,
    val overrides: Map<String, DashboardCardKind> = emptyMap(),
) {
    /** Visible sections in persisted order; sections never customized are appended in default order. */
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

    /** Commits the whole visible-section order once drag reordering ends (aligned with the persistence timing of iOS .onMove). */
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
