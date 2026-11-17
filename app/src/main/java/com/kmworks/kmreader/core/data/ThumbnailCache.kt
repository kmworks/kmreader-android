package com.kmworks.kmreader.core.data

import coil3.memory.MemoryCache
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover cache operations (mirrors iOS ThumbnailCache.refreshThumbnail).
 * "Refresh cover" = drop the memory and disk caches for that cover; the next display re-fetches it from the server.
 */
@Singleton
class ThumbnailCache @Inject constructor(
    private val sessionManager: SessionManager,
) {

    /** Invalidate the cover cache of one entry; false means no session / not registered (demo data). */
    fun refresh(stableId: Int): Boolean {
        val clients = sessionManager.clients.value ?: return false
        val loader = sessionManager.imageLoader.value ?: return false
        val url = CoverUrlRegistry.url(stableId, clients) ?: return false
        loader.memoryCache?.remove(MemoryCache.Key(url))
        runCatching { loader.diskCache?.remove(url) }
        return true
    }
}
