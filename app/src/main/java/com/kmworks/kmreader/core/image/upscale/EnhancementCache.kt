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
 * Kept separate from Coil's page cache because keys include [UpscaleConfig.configHash]
 * (model architecture + scale + denoise weights + bundled-model version): changing any of
 * them automatically misses every old entry, so no manual purge is ever needed. Stored as
 * PNG to stay lossless; capped at [MAX_SIZE_BYTES] (LRU eviction inside Coil's DiskCache).
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

    companion object {
        private const val TAG = "EnhancementCache"

        /** Upscaled pages are ~4x the source pixels; 512 MB holds roughly one long series. */
        const val MAX_SIZE_BYTES = 512L * 1024 * 1024

        /** sha256(url + configHash) — never reversible to the URL, consistent with Coil's page keys. */
        fun cacheKey(pageUrl: String, configHash: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(pageUrl.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(configHash.toByteArray(Charsets.UTF_8))
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
                        diskCache.value = DiskCache.Builder()
                            .directory(dir.toOkioPath())
                            .maxSizeBytes(MAX_SIZE_BYTES)
                            .build()
                    }
                }
        }
    }

    /** The cached enhanced bitmap for [key], or null on a miss / unreadable entry. */
    suspend fun read(key: String): Bitmap? = withContext(Dispatchers.IO) {
        val cache = diskCache.value ?: return@withContext null
        runCatching {
            cache.openSnapshot(key)?.use { snapshot ->
                BitmapFactory.decodeFile(snapshot.data.toFile().absolutePath)
            }
        }.onFailure { Log.w(TAG, "read failed: $key", it) }.getOrNull()
    }

    suspend fun write(key: String, bitmap: Bitmap): Unit = withContext(Dispatchers.IO) {
        val cache = diskCache.value ?: return@withContext
        runCatching {
            val editor = cache.openEditor(key) ?: return@runCatching
            try {
                FileOutputStream(editor.data.toFile()).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                editor.commit()
            } catch (e: Throwable) {
                runCatching { editor.abort() }
                throw e
            }
        }.onFailure { Log.w(TAG, "write failed: $key", it) }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { diskCache.value?.clear() }
    }

    /** Evict every denoise variant of the given page URLs (used before a book is deleted). */
    suspend fun evictPages(pageUrls: List<String>): Unit = withContext(Dispatchers.IO) {
        val cache = diskCache.value ?: return@withContext
        for (url in pageUrls) {
            for (denoise in DenoiseLevel.entries) {
                runCatching { cache.remove(cacheKey(url, UpscaleConfig.configHashFor(denoise))) }
            }
        }
    }
}
