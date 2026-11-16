package com.kmworks.kmreader.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.ui.components.CardDisplayOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val themeMode: StateFlow<String> = appSettings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsStore.THEME_SYSTEM)

    val trueBlackDark: StateFlow<Boolean> = appSettings.trueBlackDark
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val divinaDefaultDirection: StateFlow<String> = appSettings.divinaDefaultDirection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsStore.DEFAULT_DIRECTION)

    /** 应用图标变体（primary/classic/legacy；持久化，切换由 manifest alias 落地）。 */
    val appIcon: StateFlow<String> = appSettings.appIcon
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsStore.APP_ICON_PRIMARY)

    /** 仅 Wi-Fi 预加载（阅读器消费）。 */
    val wifiOnlyPreload: StateFlow<Boolean> = appSettings.wifiOnlyPreload
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 网络请求超时（秒）。 */
    val networkTimeoutSeconds: StateFlow<Int> = appSettings.networkTimeoutSeconds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettingsStore.DEFAULT_NETWORK_TIMEOUT_SECONDS,
        )

    /** 整本下载页级并发数（DownloadWorker 消费）。 */
    val downloadConcurrency: StateFlow<Int> = appSettings.downloadConcurrency
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettingsStore.DEFAULT_DOWNLOAD_CONCURRENCY,
        )

    /** 图片磁盘缓存上限（MB）。 */
    val imageCacheLimitMb: StateFlow<Int> = appSettings.imageCacheLimitMb
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettingsStore.DEFAULT_IMAGE_CACHE_LIMIT_MB,
        )

    /** SSE 事件驱动仪表盘自动刷新（随 SSE 总开关联动禁用）。 */
    val sseAutoRefresh: StateFlow<Boolean> = appSettings.sseAutoRefresh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** SSE 连接/任务完成通知（通知基础设施接入前仅持久化）。 */
    val sseNotify: StateFlow<Boolean> = appSettings.sseNotify
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    /**
     * 应用图标变体（primary/classic/legacy）。持久化后立即切换 manifest
     * activity-alias：启用目标 alias、禁用其余（DONT_KILL_APP，不打断当前会话）。
     */
    fun setAppIcon(value: String) {
        viewModelScope.launch { appSettings.setAppIcon(value) }
        applyAppIcon(value)
    }

    private fun applyAppIcon(value: String) {
        val aliases = mapOf(
            AppSettingsStore.APP_ICON_PRIMARY to ".AppIconPrimary",
            "classic" to ".AppIconClassic",
            "legacy" to ".AppIconLegacy",
        )
        val pm = appContext.packageManager
        aliases.forEach { (key, alias) ->
            pm.setComponentEnabledSetting(
                ComponentName(appContext.packageName, appContext.packageName + alias),
                if (key == value) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun setWifiOnlyPreload(enabled: Boolean) {
        viewModelScope.launch { appSettings.setWifiOnlyPreload(enabled) }
    }

    fun setNetworkTimeoutSeconds(seconds: Int) {
        viewModelScope.launch { appSettings.setNetworkTimeoutSeconds(seconds) }
    }

    fun setDownloadConcurrency(value: Int) {
        viewModelScope.launch { appSettings.setDownloadConcurrency(value) }
    }

    fun setImageCacheLimitMb(value: Int) {
        viewModelScope.launch { appSettings.setImageCacheLimitMb(value) }
    }

    fun setSseAutoRefresh(enabled: Boolean) {
        viewModelScope.launch { appSettings.setSseAutoRefresh(enabled) }
    }

    fun setSseNotify(enabled: Boolean) {
        viewModelScope.launch { appSettings.setSseNotify(enabled) }
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
