package com.kmworks.kmreader.core.stats

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmworks.kmreader.core.network.ServerConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reading-stats cache DataStore (independent of server_config; keys are isolated per instance+library). */
private val Context.readingStatsDataStore by preferencesDataStore(name = "reading_stats")

/** Snapshot of the three endpoint responses + persist time (mirrors iOS ReadingStatsSnapshot). */
@Serializable
data class ReadingStatsSnapshot(
    val summary: ReadingStatsSummaryResponse? = null,
    val activity: ReadingStatsActivityResponse? = null,
    val tops: ReadingStatsTopsResponse? = null,
    val savedAtEpochMs: Long = 0L,
)

/**
 * Reading-stats cache (mirrors iOS ReadingStatsCacheStore):
 * caches the last successful response per "instance : library"; usable directly within 24 hours,
 * stale entries are shown first by the ViewModel while refreshing in the background, falling back to the
 * cache when the refresh fails.
 */
@Singleton
class ReadingStatsCacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ServerConfigStore,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Cache freshness window (mirrors the iOS 24-hour policy). */
    val ttlMs: Long = 24L * 60 * 60 * 1000

    suspend fun read(libraryId: String?): ReadingStatsSnapshot? {
        val key = key(libraryId) ?: return null
        val raw = context.readingStatsDataStore.data.map { it[key] }.first() ?: return null
        return runCatching { json.decodeFromString<ReadingStatsSnapshot>(raw) }.getOrNull()
    }

    suspend fun write(
        libraryId: String?,
        summary: ReadingStatsSummaryResponse?,
        activity: ReadingStatsActivityResponse?,
        tops: ReadingStatsTopsResponse?,
    ) {
        val key = key(libraryId) ?: return
        val snapshot = ReadingStatsSnapshot(summary, activity, tops, System.currentTimeMillis())
        val raw = runCatching { json.encodeToString(snapshot) }.getOrNull() ?: return
        context.readingStatsDataStore.edit { it[key] = raw }
    }

    /** Per-instance key (mirrors the iOS `instanceId:libraryId` key space). */
    private suspend fun key(libraryId: String?): androidx.datastore.preferences.core.Preferences.Key<String>? {
        val config = configStore.config.first() ?: return null
        val instanceKey = "${config.normalizedBaseUrl}#${config.username}"
        // v3: DTOs match the actual kmrs fields (flat summary structure, timeSeries point.date/pagesRead)
        return stringPreferencesKey("reading_stats_cache_v3_$instanceKey:${libraryId ?: "all"}")
    }
}
