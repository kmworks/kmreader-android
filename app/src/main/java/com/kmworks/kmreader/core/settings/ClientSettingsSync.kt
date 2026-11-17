package com.kmworks.kmreader.core.settings

import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.network.ApiClients
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * client-settings cloud sync (mirrors iOS ClientSettingsService semantics).
 * Downlink: after a session is established, fetch remote keys and apply them to AppSettingsStore (via
 * write*, so no uplink echo is triggered);
 * uplink: subscribes to AppSettingsStore.changes, aggregates same-window changes and debounces a PATCH
 * (one PATCH carries all pending keys).
 */
@Singleton
class ClientSettingsSync @Inject constructor(
    private val sessionManager: SessionManager,
    private val appSettings: AppSettingsStore,
    private val scope: CoroutineScope,
) {

    private val pendingLock = Any()
    private val pending = mutableMapOf<String, String>()
    private var flushJob: Job? = null

    init {
        scope.launch {
            sessionManager.clients.collect { clients ->
                if (clients != null) pull(clients)
            }
        }
        scope.launch {
            appSettings.changes.collect { change ->
                synchronized(pendingLock) { pending[change.remoteKey] = change.value }
                flushJob?.cancel()
                flushJob = scope.launch {
                    delay(UPLOAD_DEBOUNCE_MS)
                    flush()
                }
            }
        }
    }

    /** Downlink: applies only known keys from the mapping table, unknown keys are ignored; failures are logged (fetched again at next login). */
    private suspend fun pull(clients: ApiClients) = withContext(Dispatchers.IO) {
        val remote = try {
            val response = api(clients).getUserSettings().execute()
            if (response.isSuccessful) {
                response.body().orEmpty()
            } else {
                AppLog.w("SettingsSync", "downlink failed: HTTP ${response.code()}")
                return@withContext
            }
        } catch (e: Exception) {
            AppLog.w("SettingsSync", "downlink failed: ${e.message}")
            return@withContext
        }
        remote[ClientSettingKeys.SSE_ENABLED]?.value
            ?.let { appSettings.writeSseEnabled(it == "true") }
        remote[ClientSettingKeys.THEME_MODE]?.value
            ?.let { if (it in THEME_VALUES) appSettings.writeThemeMode(it) }
        remote[ClientSettingKeys.TRUE_BLACK]?.value
            ?.let { appSettings.writeTrueBlackDark(it == "true") }
        remote[ClientSettingKeys.DIVINA_DIRECTION]?.value
            ?.let { if (it in DIRECTION_VALUES) appSettings.writeDivinaDefaultDirection(it) }
        AppLog.d("SettingsSync", "downlink done: ${remote.size} remote keys")
    }

    /** Uplink: one PATCH carries all pending keys; without a session they are dropped, network failures wait for the next change round. */
    private suspend fun flush() {
        val clients = sessionManager.clients.value ?: run {
            val dropped = synchronized(pendingLock) {
                pending.size.also { pending.clear() }
            }
            if (dropped > 0) AppLog.d("SettingsSync", "no session, dropped $dropped pending uplink keys")
            return
        }
        val body = synchronized(pendingLock) {
            pending.toMap().also { pending.clear() }
        }
        if (body.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val response = api(clients)
                    .saveUserSettings(body.mapValues { ClientSettingWriteDto(it.value) })
                    .execute()
                if (response.isSuccessful) {
                    AppLog.d("SettingsSync", "uplink done: ${body.size} keys")
                } else {
                    AppLog.w("SettingsSync", "uplink failed: HTTP ${response.code()} (${body.size} keys)")
                }
            } catch (e: Exception) {
                // Best effort; the values stay in the local DataStore and are carried again by the next change
                AppLog.w("SettingsSync", "uplink failed: ${e.message} (${body.size} keys)")
            }
        }
    }

    private fun api(clients: ApiClients): ClientSettingsApi =
        LocalApiFactory.create(clients, ClientSettingsApi::class.java)

    private companion object {
        const val UPLOAD_DEBOUNCE_MS = 2_000L
        val THEME_VALUES = setOf("system", "light", "dark")
        val DIRECTION_VALUES = setOf("ltr", "rtl", "vertical", "webtoon")
    }
}
