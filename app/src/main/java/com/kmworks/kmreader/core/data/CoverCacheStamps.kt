package com.kmworks.kmreader.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.coverStampStore by preferencesDataStore(name = "cover_cache_stamps")

/**
 * The `id → timestamp` table behind the cover refresh interval (mirrors iOS `coverCacheExpirationDays`).
 *
 * The stamp is handed to Coil as the `cv` query parameter of the cover URL, so a new stamp is also a
 * new cache key: bumping it makes the next load miss the disk cache and refetch from the server while
 * the plain URL stays stable for everything else. iOS implements the same behaviour with a version
 * parameter; keeping the timestamp in a side table (instead of probing the modification time of the
 * cache file, whose name is the SHA-256 of the URL) lets a composable decide before it starts loading.
 *
 * The in-memory [stamps] map mirrors the DataStore copy so cover rendering never waits on disk; writes
 * are fire-and-forget, and an interrupted write only costs one extra cover fetch after a restart.
 */
@Singleton
class CoverCacheStamps @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _stamps = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Cover identity → last allowed display time (epoch millis). */
    val stamps: StateFlow<Map<String, Long>> = _stamps.asStateFlow()

    init {
        scope.launch {
            runCatching {
                context.coverStampStore.data.collect { prefs ->
                    _stamps.value = buildMap {
                        prefs.asMap().forEach { (key, value) ->
                            val name = key.name
                            if (name.startsWith(KEY_PREFIX) && value is Long) {
                                put(name.removePrefix(KEY_PREFIX), value)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Current stamp of [identity]; a cover seen for the first time is stamped now. */
    fun stamp(identity: String): Long {
        _stamps.value[identity]?.let { return it }
        val now = System.currentTimeMillis()
        _stamps.update { it + (identity to now) }
        persist(identity, now)
        return now
    }

    /**
     * New stamp when the cached cover of [identity] is older than [days], null while it is still fresh.
     * Callers evict [Refresh.previous]'s cache entry before rendering the new key.
     */
    fun refreshIfExpired(identity: String, days: Int): Refresh? {
        val previous = _stamps.value[identity] ?: return null
        val now = System.currentTimeMillis()
        if (now - previous <= days.coerceAtLeast(1) * DAY_MS) return null
        _stamps.update { it + (identity to now) }
        persist(identity, now)
        return Refresh(previous = previous, current = now)
    }

    private fun persist(identity: String, stamp: Long) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.coverStampStore.edit { prefs ->
                    prefs[longPreferencesKey(KEY_PREFIX + identity)] = stamp
                }
            }
        }
    }

    /** The stamp of a cover that just expired, and the replacement to render with. */
    data class Refresh(val previous: Long, val current: Long)

    companion object {
        private const val KEY_PREFIX = "cover:"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
