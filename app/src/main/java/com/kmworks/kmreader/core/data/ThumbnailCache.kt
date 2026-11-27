package com.kmworks.kmreader.core.data

import coil3.memory.MemoryCache
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover cache operations (mirrors iOS ThumbnailCache.refreshThumbnail).
 * "Refresh cover" = drop the memory and disk caches for that cover; the next display re-fetches it from the server.
 *
 * Covers live in [ImageCacheStore.coverLoader]'s disk cache, separate from the reader page images.
 * CoverArt may store a cover under several cache keys (plain URL, plus the `cv` refresh stamp and the
 * manual `v` version), so every variant is dropped here instead of only the bare URL.
 */
@Singleton
class ThumbnailCache @Inject constructor(
    private val sessionManager: SessionManager,
    private val imageCacheStore: ImageCacheStore,
) {

    /** Invalidate the cover cache of one entry; false means no session / not registered (demo data). */
    fun refresh(stableId: Int): Boolean {
        val clients = sessionManager.clients.value ?: return false
        val loader = imageCacheStore.coverLoader.value ?: return false
        val url = CoverUrlRegistry.url(stableId, clients) ?: return false
        val absolute = if (url.startsWith("http")) url else "${clients.baseUrl}/${url.trimStart('/')}"
        val stamp = imageCacheStore.coverStamps.stamps.value?.get("${clients.baseUrl}#cover:$stableId")
        val keys = buildList {
            add(absolute)
            if (stamp != null) add(withParameter(absolute, "cv=$stamp"))
        }
        keys.forEach { key ->
            runCatching { loader.memoryCache?.remove(MemoryCache.Key(key)) }
            runCatching { loader.diskCache?.remove(key) }
        }
        return true
    }

    private fun withParameter(url: String, parameter: String): String =
        url + (if (url.contains('?')) "&" else "?") + parameter
}
