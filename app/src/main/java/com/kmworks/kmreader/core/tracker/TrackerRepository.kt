package com.kmworks.kmreader.core.tracker

import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.Call

/**
 * Tracker 数据（对齐 iOS TrackerService 调用方语义）：
 * 能力门控 ServerFeature.Trackers（stock Komga 全部短路）；列表失败返空、动作返 Boolean。
 */
@Singleton
class TrackerRepository @Inject constructor(
    private val sessionManager: SessionManager,
    private val serverProfileStore: ServerProfileStore,
) {

    /** kmrs Trackers 能力（无 profile/stock → false）。 */
    val supported: Boolean
        get() = serverProfileStore.hasFeature(ServerFeature.Trackers)

    private val api: TrackerApi?
        get() = sessionManager.clients.value
            ?.takeIf { supported }
            ?.let { LocalApiFactory.create(it, TrackerApi::class.java) }

    suspend fun preferences(): TrackerPreferencesDto? =
        api?.getPreferences()?.fetchOrNull()

    suspend fun savePreferences(preferences: TrackerPreferencesDto): Boolean =
        api?.updatePreferences(preferences)?.executeOrFalse() == true

    suspend fun oauthStatus(provider: String): TrackerAccountDto? =
        api?.getAccount(provider)?.fetchOrNull()

    suspend fun logoutProvider(provider: String): Boolean =
        api?.logout(provider)?.executeOrFalse() == true

    /** 某系列的绑定列表（无会话/无能力/失败 → 空）。 */
    suspend fun links(seriesDtoId: String): List<TrackerLinkDto> =
        api?.getSeriesLinks(seriesDtoId)?.fetchOrNull().orEmpty()

    suspend fun upsertLink(
        seriesDtoId: String,
        provider: String,
        trackId: String,
        title: String?,
        mode: String,
        chapterOffset: Int,
    ): Boolean = api?.upsertLink(
        seriesDtoId,
        TrackerLinkWriteDto(
            provider = provider,
            trackId = trackId,
            title = title,
            trackMode = mode,
            chapterOffset = chapterOffset,
        ),
    )?.executeOrFalse() == true

    suspend fun deleteLink(seriesDtoId: String, provider: String): Boolean =
        api?.deleteLink(seriesDtoId, provider)?.executeOrFalse() == true

    /** 远端搜索（对齐 search：name/nsfw/language 参数）。 */
    suspend fun search(provider: String, query: String): List<TrackerSearchItemDto> =
        api?.search(
            provider = provider,
            name = query,
            nsfw = true,
            language = Locale.getDefault().language,
        )?.fetchOrNull().orEmpty()

    suspend fun remoteState(provider: String, trackId: String): TrackerRemoteStateDto? =
        api?.getState(provider, trackId)?.fetchOrNull()

    /** 推送远端状态（只提交非空字段；trackId 恒携带）。 */
    suspend fun pushUpdate(
        provider: String,
        trackId: String,
        status: String? = null,
        score: Int? = null,
        lastReadChapter: Double? = null,
        lastReadVolume: Int? = null,
    ): Boolean {
        val body = buildJsonObject {
            put("trackId", trackId)
            status?.let { put("status", it) }
            score?.let { put("score", it) }
            lastReadChapter?.let { put("lastReadChapter", it) }
            lastReadVolume?.let { put("lastReadVolume", it) }
        }
        return api?.updateState(provider, body)?.executeOrFalse() == true
    }

    suspend fun ledger(page: Int = 0, size: Int = 50): List<TrackerLedgerItemDto> =
        api?.getLedger(page, size)?.fetchOrNull()?.content.orEmpty()

    private suspend fun <T> Call<T>.fetchOrNull(): T? = withContext(Dispatchers.IO) {
        try {
            val response = execute()
            if (response.isSuccessful) {
                response.body()
            } else {
                AppLog.w("Tracker", "HTTP ${response.code()} ${request().method} ${request().url}")
                null
            }
        } catch (e: Exception) {
            AppLog.e("Tracker", "${request().method} ${request().url}", e)
            null
        }
    }

    private suspend fun Call<*>.executeOrFalse(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = execute()
            if (!response.isSuccessful) {
                AppLog.w("Tracker", "HTTP ${response.code()} ${request().method} ${request().url}")
            }
            response.isSuccessful
        } catch (e: Exception) {
            AppLog.e("Tracker", "${request().method} ${request().url}", e)
            false
        }
    }
}
