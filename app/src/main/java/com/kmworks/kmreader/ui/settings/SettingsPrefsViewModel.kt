package com.kmworks.kmreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.ui.components.CardDisplayOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页共享偏好（AppSettingsStore 持久化 + client-settings 云同步）。
 * 外观/阅读器默认/浏览卡片等设置页通过它读写，落盘即自动获得上行同步。
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

    /** 浏览卡片显示选项（持久化，卡片组件通过 LocalCardDisplay 消费）。 */
    val cardDisplay: StateFlow<CardDisplayOptions> = combine(
        appSettings.cardCoverOnly,
        appSettings.cardTextOverlay,
        appSettings.cardShowBookSeriesTitle,
        appSettings.thumbnailPreserveAspectRatio,
        appSettings.thumbnailShowShadow,
        appSettings.thumbnailShowUnreadIndicator,
        appSettings.thumbnailShowProgressBar,
        appSettings.thumbnailBlurUnreadCovers,
    ) { values ->
        CardDisplayOptions(
            coverOnly = values[0],
            textOverlay = values[1],
            showBookSeriesTitle = values[2],
            preserveAspectRatio = values[3],
            showShadow = values[4],
            showUnreadIndicator = values[5],
            showProgressBar = values[6],
            blurUnreadCovers = values[7],
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardDisplayOptions())

    fun setThemeMode(mode: String) {
        viewModelScope.launch { appSettings.setThemeMode(mode) }
    }

    fun setTrueBlackDark(enabled: Boolean) {
        viewModelScope.launch { appSettings.setTrueBlackDark(enabled) }
    }

    fun setDivinaDefaultDirection(direction: String) {
        viewModelScope.launch { appSettings.setDivinaDefaultDirection(direction) }
    }

    fun setCardCoverOnly(enabled: Boolean) {
        viewModelScope.launch { appSettings.setCardCoverOnly(enabled) }
    }

    fun setCardTextOverlay(enabled: Boolean) {
        viewModelScope.launch { appSettings.setCardTextOverlay(enabled) }
    }

    fun setCardShowBookSeriesTitle(enabled: Boolean) {
        viewModelScope.launch { appSettings.setCardShowBookSeriesTitle(enabled) }
    }

    fun setThumbnailPreserveAspectRatio(enabled: Boolean) {
        viewModelScope.launch { appSettings.setThumbnailPreserveAspectRatio(enabled) }
    }

    fun setThumbnailShowShadow(enabled: Boolean) {
        viewModelScope.launch { appSettings.setThumbnailShowShadow(enabled) }
    }

    fun setThumbnailShowUnreadIndicator(enabled: Boolean) {
        viewModelScope.launch { appSettings.setThumbnailShowUnreadIndicator(enabled) }
    }

    fun setThumbnailShowProgressBar(enabled: Boolean) {
        viewModelScope.launch { appSettings.setThumbnailShowProgressBar(enabled) }
    }

    fun setThumbnailBlurUnreadCovers(enabled: Boolean) {
        viewModelScope.launch { appSettings.setThumbnailBlurUnreadCovers(enabled) }
    }
}
