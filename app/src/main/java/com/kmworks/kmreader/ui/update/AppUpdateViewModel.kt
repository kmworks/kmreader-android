package com.kmworks.kmreader.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.update.AppVersion
import com.kmworks.kmreader.core.update.UpdateInfo
import com.kmworks.kmreader.core.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the About page and the launch prompt render for one update check.
 *
 * [update] outlives [dialogVisible]: closing the dialog keeps the newer release so the About page can
 * still offer it, which is why the two are separate flags.
 */
data class AppUpdateState(
    val currentVersion: String = "",
    val checking: Boolean = false,
    val update: UpdateInfo? = null,
    val dialogVisible: Boolean = false,
    /** A manual check found nothing newer than the installed build. */
    val upToDate: Boolean = false,
    /** The last check failed; [error] is the reason as the client reported it. */
    val error: String? = null,
    /** Epoch millis of the last successful check, 0 before the first one. */
    val lastCheckedAt: Long = 0L,
    val autoCheckEnabled: Boolean = true,
)

/**
 * Checks GitHub for a newer release and owns the one "new version" dialog.
 *
 * Two entrances share it: the About page (switch + "Check Now") and the app root, which runs the
 * throttled launch check and renders the dialog. The launch check records its timestamp so a user who
 * opens the app several times a day does not query GitHub each time.
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: AppSettingsStore,
    private val repository: UpdateRepository,
) : ViewModel() {

    private val installedVersion = readInstalledVersion(context)

    private val _state = MutableStateFlow(AppUpdateState(currentVersion = installedVersion))
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    init {
        // Read the persisted switches once; the state below mirrors them for the UI.
        viewModelScope.launch {
            val enabled = settings.autoCheckUpdates.first()
            val lastChecked = settings.lastUpdateCheckAt.first()
            _state.update { it.copy(autoCheckEnabled = enabled, lastCheckedAt = lastChecked) }
            if (enabled) checkAutomatically()
        }
    }

    /** The automatic-check switch: persisted at once, and turning it on runs the throttled check. */
    fun setAutoCheckEnabled(enabled: Boolean) {
        _state.update { it.copy(autoCheckEnabled = enabled) }
        viewModelScope.launch {
            settings.setAutoCheckUpdates(enabled)
            // Re-enabling should not wait for the next cold start to notice a release.
            if (enabled) checkAutomatically()
        }
    }

    /** "Check Now": always tries, and reports the result even when the app is up to date. */
    fun checkNow() {
        check(manual = true)
    }

    /** A dismissed dialog and its release are still reachable from the About page. */
    fun showAvailableUpdate() {
        if (_state.value.update != null) _state.update { it.copy(dialogVisible = true) }
    }

    /** "Later": closes the dialog and keeps this release from prompting again at launch. */
    fun dismissDialog() {
        val version = _state.value.update?.version
        _state.update { it.copy(dialogVisible = false) }
        if (version != null) viewModelScope.launch { settings.setDismissedUpdateVersion(version) }
    }

    /** The launch check: silent unless a newer release appears, and at most once per [AUTO_CHECK_INTERVAL_MILLIS]. */
    private fun checkAutomatically() {
        viewModelScope.launch {
            val lastChecked = settings.lastUpdateCheckAt.first()
            if (System.currentTimeMillis() - lastChecked < AUTO_CHECK_INTERVAL_MILLIS) return@launch
            check(manual = false)
        }
    }

    private fun check(manual: Boolean) {
        if (_state.value.checking) return
        viewModelScope.launch {
            _state.update { it.copy(checking = true, error = null, upToDate = false) }
            val result = repository.latestRelease()
            val checkedAt = System.currentTimeMillis()
            result.fold(
                onSuccess = { info ->
                    val newer = AppVersion.isNewer(info.version, _state.value.currentVersion)
                    val dismissed = settings.dismissedUpdateVersion.first()
                    _state.update {
                        it.copy(
                            checking = false,
                            update = if (newer) info else null,
                            // A launch check stays quiet for a release the user already put off; a manual
                            // check reports whatever it found.
                            dialogVisible = newer && (manual || info.version != dismissed),
                            upToDate = !newer,
                            lastCheckedAt = checkedAt,
                        )
                    }
                    settings.setLastUpdateCheckAt(checkedAt)
                },
                onFailure = { failure ->
                    // Left unrecorded: the next launch retries instead of waiting a day after a flaky check.
                    _state.update {
                        it.copy(
                            checking = false,
                            error = failure.message ?: failure::class.java.simpleName,
                        )
                    }
                },
            )
        }
    }

    private companion object {
        /** A daily lookup notices a release without polling GitHub from every cold start. */
        const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

        fun readInstalledVersion(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "0.0.0" }
    }
}
