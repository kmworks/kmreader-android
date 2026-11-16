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
 * client-settings 云端同步（对齐 iOS ClientSettingsService 语义）。
 * 下行：会话建立后拉取远端键应用到 AppSettingsStore（走 write*，不触发上行回显）；
 * 上行：订阅 AppSettingsStore.changes，聚合同期变更后防抖 PATCH（一次 PATCH 携带全部待写键）。
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

    /** 下行：只应用映射表内的已知键，未知键忽略；失败记日志（下次登录再拉）。 */
    private suspend fun pull(clients: ApiClients) = withContext(Dispatchers.IO) {
        val remote = try {
            val response = api(clients).getUserSettings().execute()
            if (response.isSuccessful) {
                response.body().orEmpty()
            } else {
                AppLog.w("SettingsSync", "下行失败：HTTP ${response.code()}")
                return@withContext
            }
        } catch (e: Exception) {
            AppLog.w("SettingsSync", "下行失败：${e.message}")
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
        AppLog.d("SettingsSync", "下行完成：远端 ${remote.size} 键")
    }

    /** 上行：一次 PATCH 携带全部待写键；无会话丢弃，网络失败等下轮变更再试。 */
    private suspend fun flush() {
        val clients = sessionManager.clients.value ?: run {
            val dropped = synchronized(pendingLock) {
                pending.size.also { pending.clear() }
            }
            if (dropped > 0) AppLog.d("SettingsSync", "无会话，丢弃 $dropped 个待上行键")
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
                    AppLog.d("SettingsSync", "上行完成：${body.size} 键")
                } else {
                    AppLog.w("SettingsSync", "上行失败：HTTP ${response.code()}（${body.size} 键）")
                }
            } catch (e: Exception) {
                // best effort；键值仍在本地 DataStore，下次变更会重新携带
                AppLog.w("SettingsSync", "上行失败：${e.message}（${body.size} 键）")
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
