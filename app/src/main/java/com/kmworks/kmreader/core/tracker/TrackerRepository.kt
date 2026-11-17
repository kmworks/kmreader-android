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
 * Tracker data (call-site semantics mirror iOS TrackerService):
 * capability-gated on ServerFeature.Trackers (everything short-circuits on stock Komga); list failures
 * return empty, actions return Boolean.
 */
@Singleton
class TrackerRepository @Inject constructor(
    private val sessionManager: SessionManager,
    private val serverProfileStore: ServerProfileStore,
) {

    /** kmrs Trackers capability (no profile/stock → false). */
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

    /** Global binding list (binding management on the settings page; filterable by provider and paged). */
    suspend fun links(
        page: Int = 0,
        size: Int = 50,
        provider: String? = null,
    ): TrackerLinkPageDto? = api?.getLinks(page, size, provider)?.fetchOrNull()

    /** Binding list of one series (no session / no capability / failure → empty). */
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

    /** Remote search (mirrors search: name/nsfw/language parameters). */
    suspend fun search(provider: String, query: String): List<TrackerSearchItemDto> {
        val trackerApi = api
        if (trackerApi == null) {
            AppLog.w("TrackerRepository", "search skipped: api unavailable provider=$provider query=$query")
            return emptyList()
        }
        AppLog.d("TrackerRepository", "search request provider=$provider query=$query")
        val result = trackerApi.search(
            provider = provider,
            name = query,
            nsfw = true,
            language = Locale.getDefault().language,
        ).fetchOrNull().orEmpty()
        AppLog.d("TrackerRepository", "search result provider=$provider query=$query count=${result.size}")
        return result
    }

    suspend fun remoteState(provider: String, trackId: String): TrackerRemoteStateDto? =
        api?.getState(provider, trackId)?.fetchOrNull()

    /** Push remote state (only non-null fields are submitted; trackId is always included). */
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
