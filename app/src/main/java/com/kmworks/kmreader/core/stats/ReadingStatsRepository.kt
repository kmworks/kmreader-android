package com.kmworks.kmreader.core.stats

import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.data.awaitBody
import com.kmworks.kmreader.core.network.SessionManager
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reading statistics data source (kmrs capability; returns null on stock Komga 404, the caller hides the
 * section by capability gating).
 * The service is built locally via LocalApiFactory (ApiClients is read-only).
 */
@Singleton
class ReadingStatsRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private fun api(): ReadingStatsApi? =
        sessionManager.clients.value?.let { LocalApiFactory.create(it, ReadingStatsApi::class.java) }

    /** All endpoints support library filtering (mirrors the in-page library Picker on iOS). */
    suspend fun summary(libraryId: String? = null): ReadingStatsSummaryResponse? =
        api()?.summary(libraryId)?.awaitBody()

    suspend fun activity(libraryId: String? = null): ReadingStatsActivityResponse? =
        api()?.activity(libraryId, tzOffsetMinutes = deviceTzOffsetMinutes())?.awaitBody()

    suspend fun tops(libraryId: String? = null): ReadingStatsTopsResponse? =
        api()?.tops(libraryId)?.awaitBody()

    private suspend fun deviceTzOffsetMinutes(): Int = withContext(Dispatchers.Default) {
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
    }
}
