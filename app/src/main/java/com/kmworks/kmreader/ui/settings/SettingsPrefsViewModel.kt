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
 * Shared settings preferences (persisted via AppSettingsStore with client-settings cloud sync).
 * The appearance, reader-defaults, and browse-card settings screens read and write through it;
 * persisting a value automatically gets it uploaded by sync.
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

    /** App icon variant (primary/classic/legacy; persisted, applied via manifest aliases). */
    val appIcon: StateFlow<String> = appSettings.appIcon
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsStore.APP_ICON_PRIMARY)

    /** Wi-Fi only preload (consumed by the reader). */
    val wifiOnlyPreload: StateFlow<Boolean> = appSettings.wifiOnlyPreload
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Network request timeout in seconds. */
    val networkTimeoutSeconds: StateFlow<Int> = appSettings.networkTimeoutSeconds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettingsStore.DEFAULT_NETWORK_TIMEOUT_SECONDS,
        )

    /** Whole-book download page-level concurrency (consumed by DownloadWorker). */
    val downloadConcurrency: StateFlow<Int> = appSettings.downloadConcurrency
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettingsStore.DEFAULT_DOWNLOAD_CONCURRENCY,
        )

    /** Image disk cache limit in MB. */
    val imageCacheLimitMb: StateFlow<Int> = appSettings.imageCacheLimitMb
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettingsStore.DEFAULT_IMAGE_CACHE_LIMIT_MB,
        )

    /** SSE event-driven dashboard auto-refresh (disabled together with the SSE master switch). */
    val sseAutoRefresh: StateFlow<Boolean> = appSettings.sseAutoRefresh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** SSE connection/task-finished notifications (persisted only until the notification infrastructure lands). */
    val sseNotify: StateFlow<Boolean> = appSettings.sseNotify
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Page-turn progress recording threshold (mirrors iOS AppConfig.progressRecordingThreshold, default 3; 0 = immediately). */
    val progressRecordingThreshold: StateFlow<Int> = appSettings.progressRecordingThreshold
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettingsStore.DEFAULT_PROGRESS_RECORDING_THRESHOLD,
        )

    /** Browse card display options (persisted; card components consume them via LocalCardDisplay). */
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
     * App icon variant (primary/classic/legacy). After persisting, immediately switches the manifest
     * activity-alias: enables the target alias and disables the rest (DONT_KILL_APP, so the current
     * session is not interrupted).
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

    fun setProgressRecordingThreshold(value: Int) {
        viewModelScope.launch { appSettings.setProgressRecordingThreshold(value) }
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
