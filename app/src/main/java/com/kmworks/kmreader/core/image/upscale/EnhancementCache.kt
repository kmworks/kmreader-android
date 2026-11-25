package com.kmworks.kmreader.core.image.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import coil3.disk.DiskCache
import com.kmworks.kmreader.core.data.ImageCachePaths
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.ui.reader.DenoiseLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/**
 * Disk cache for waifu2x-upscaled page images (docs/waifu2x-upscaling-plan.md §6).
 *
 * Kept separate from Coil's page cache because the key is not a plain URL: it is
 * [pageKey] (page URL + [UpscaleConfig.MODEL_SIGNATURE]) with a denoise *variant* suffix, so
 * changing the model architecture, the scale or the bundled-model version automatically misses
 * every old entry — no manual purge is ever needed. Stored as PNG to stay lossless; capped at
 * [MAX_SIZE_BYTES] (LRU eviction inside Coil's DiskCache).
 *
 * The denoise level is a variant axis, not part of the page identity:
 *
 * - a switch reads the requested variant, and on a miss falls back to the nearest cached one
 *   ([readNearest]) so the reader never waits for a full re-run of the book;
 * - the requested variant is then regenerated in the background and the reader swaps it in;
 * - [MAX_VARIANTS_PER_PAGE] variants per page are kept, so toggling levels back and forth stays a
 *   cache hit without letting the disk footprint grow unbounded.
 *
 * Namespaced per server instance exactly like the page cache, and rebuilt when the session
 * changes (the previous cache is shut down first — two DiskCaches must never share a journal).
 */
@Singleton
class EnhancementCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
) {

    /** One decoded denoise variant plus the level it was produced at. */
    class Hit(val bitmap: Bitmap, val denoise: DenoiseLevel)

    companion object {
        private const val TAG = "EnhancementCache"

        /** Upscaled pages are ~4x the source pixels; 512 MB holds roughly one long series. */
        const val MAX_SIZE_BYTES = 512L * 1024 * 1024

        /**
         * Denoise variants kept for one page: the level in use plus the previous one, so A→B→A stays
         * a hit. Bounded because each variant is a full 2x page (~4x the source pixels) and the disk
         * budget has to cover whole series.
         */
        const val MAX_VARIANTS_PER_PAGE = 2

        /**
         * Layout marker for [cacheKey]'s shape. Bumping it clears the enhanced cache once, so a key
         * scheme change never leaves unreachable entries occupying the 512 MB budget until LRU
         * happens to evict them.
         */
        private const val PREFS = "enhancement-cache"
        private const val KEY_LAYOUT = "layout"
        private const val LAYOUT = 2

        /**
         * Stable per-page part of the cache key: page URL + model signature (architecture, scale,
         * bundled-model version). Deliberately excludes the denoise level — every variant of a page
         * shares this key, which is what lets a denoise switch fall back to a cached variant.
         */
        fun pageKey(pageUrl: String): String =
            sha256(pageUrl, UpscaleConfig.MODEL_SIGNATURE)

        /** One denoise variant of [pageKey]. */
        fun variantKey(pageKey: String, denoise: DenoiseLevel): String = "$pageKey#${denoise.name}"

        /** Full cache key for [pageUrl] at [denoise] — what [EnhancementDecoder] looks up. */
        fun cacheKey(pageUrl: String, denoise: DenoiseLevel): String =
            variantKey(pageKey(pageUrl), denoise)

        /**
         * sha256 over the parts, NUL-separated so no two distinct inputs can collide in the framing.
         * Never reversible to the URL, consistent with Coil's page keys.
         */
        private fun sha256(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEachIndexed { index, part ->
                if (index > 0) digest.update(0)
                digest.update(part.toByteArray(Charsets.UTF_8))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val diskCache = MutableStateFlow<DiskCache?>(null)

    init {
        scope.launch {
            sessionManager.clients
                .distinctUntilChanged { old, new -> old?.cacheNamespace == new?.cacheNamespace }
                .collect { clients ->
                    val previous = diskCache.value
                    diskCache.value = null
                    runCatching { previous?.shutdown() }
                    if (clients != null) {
                        val dir = ImageCachePaths.directory(
                            context.cacheDir,
                            ImageCachePaths.instanceKey(clients.cacheNamespace),
                            ImageCachePaths.KIND_ENHANCED,
                        ).apply { mkdirs() }
                        val cache = DiskCache.Builder()
                            .directory(dir.toOkioPath())
                            .maxSizeBytes(MAX_SIZE_BYTES)
                            .build()
                        // Purge entries written under an earlier key layout (they are unreachable).
                        withContext(Dispatchers.IO) { migrateLayout(cache, clients.cacheNamespace) }
                        diskCache.value = cache
                    }
                }
        }
    }

    private fun migrateLayout(cache: DiskCache, namespace: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$KEY_LAYOUT:$namespace"
        if (prefs.getInt(key, 0) >= LAYOUT) return
        Log.i(TAG, "layout migration: clearing enhanced cache of $namespace (layout < $LAYOUT)")
        runCatching { cache.clear() }
            .onFailure { Log.w(TAG, "layout migration: clear failed", it) }
        prefs.edit().putInt(key, LAYOUT).apply()
    }

    /** The cached bitmap for [cacheKey], or null on a miss / unreadable entry. */
    suspend fun read(cacheKey: String): Bitmap? = withContext(Dispatchers.IO) { readKey(cacheKey) }

    /** Exact-variant hit for [pageUrl] at [denoise]. */
    suspend fun read(pageUrl: String, denoise: DenoiseLevel): Bitmap? =
        read(cacheKey(pageUrl, denoise))

    /**
     * Nearest cached variant when [denoise] itself is not cached, ordered by distance in
     * [DenoiseLevel] so the closest look wins. Null means the page has no variant at all.
     *
     * Reading a handful of keys costs a journal lookup each; only a hit is decoded.
     */
    suspend fun readNearest(pageUrl: String, denoise: DenoiseLevel): Hit? =
        withContext(Dispatchers.IO) {
            val page = pageKey(pageUrl)
            val candidates = DenoiseLevel.entries.sortedBy { abs(it.ordinal - denoise.ordinal) }
            for (candidate in candidates) {
                if (candidate == denoise) continue
                val bitmap = readKey(variantKey(page, candidate)) ?: continue
                return@withContext Hit(bitmap, candidate)
            }
            null
        }

    suspend fun write(cacheKey: String, bitmap: Bitmap): Unit = withContext(Dispatchers.IO) {
        val cache = diskCache.value ?: return@withContext
        runCatching {
            val editor = cache.openEditor(cacheKey) ?: return@runCatching
            try {
                FileOutputStream(editor.data.toFile()).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                editor.commit()
            } catch (e: Throwable) {
                runCatching { editor.abort() }
                throw e
            }
        }.onFailure { Log.w(TAG, "write failed: $cacheKey", it) }
    }

    /**
     * Drops the denoise variants of [pageUrl] that are not in [keep], bounding the per-page footprint
     * to [MAX_VARIANTS_PER_PAGE]. Called right after a variant is written; entries in use by another
     * decode are left alone by the disk cache until that snapshot closes.
     */
    suspend fun pruneVariants(pageUrl: String, keep: Set<DenoiseLevel>): Unit =
        withContext(Dispatchers.IO) {
            val cache = diskCache.value ?: return@withContext
            val page = pageKey(pageUrl)
            for (denoise in DenoiseLevel.entries) {
                if (denoise in keep) continue
                runCatching { cache.remove(variantKey(page, denoise)) }
                    .onFailure { Log.w(TAG, "prune failed: $page#${denoise.name}", it) }
            }
        }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { diskCache.value?.clear() }
    }

    /** Evict every denoise variant of the given page URLs (used before a book is deleted). */
    suspend fun evictPages(pageUrls: List<String>): Unit = withContext(Dispatchers.IO) {
        val cache = diskCache.value ?: return@withContext
        for (url in pageUrls) {
            val page = pageKey(url)
            for (denoise in DenoiseLevel.entries) {
                runCatching { cache.remove(variantKey(page, denoise)) }
            }
        }
    }

    private fun readKey(cacheKey: String): Bitmap? {
        val cache = diskCache.value ?: return null
        return runCatching {
            cache.openSnapshot(cacheKey)?.use { snapshot ->
                BitmapFactory.decodeFile(snapshot.data.toFile().absolutePath)
            }
        }.onFailure { Log.w(TAG, "read failed: $cacheKey", it) }.getOrNull()
    }
}
