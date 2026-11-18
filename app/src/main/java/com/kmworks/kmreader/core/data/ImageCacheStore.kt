package com.kmworks.kmreader.core.data

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/**
 * The two authenticated image caches, mirroring the iOS split between page images and covers:
 *
 * - [pageLoader] serves reader page images and the reader's page-thumbnail strip (iOS `ImageCache`).
 * - [coverLoader] serves book / series / collection / read list covers and thumbnails (iOS `ThumbnailCache`).
 *
 * Each loader owns a disk cache under [ImageCachePaths] namespaced by the active server instance and
 * capped by its own setting, so a large page cache can never push covers out of the cache (and vice
 * versa). Loaders are rebuilt when the session or the corresponding size cap changes; the previous
 * loader is shut down first because two disk caches must never keep the same directory open at once
 * (they would both rewrite its journal).
 *
 * The pre-split single cache (`cache/image_cache`) is removed once on first run: its entries were keyed
 * by URL and are still valid, but they cannot be attributed to the page or cover kind any more, and
 * Coil would otherwise keep serving them from a directory nothing caps or clears.
 */
@Singleton
class ImageCacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val appSettings: AppSettingsStore,
    private val readerRepository: ReaderRepository,
    val coverStamps: CoverCacheStamps,
    private val scope: CoroutineScope,
) {

    private val pageLoaderState = MutableStateFlow<ImageLoader?>(null)
    private val coverLoaderState = MutableStateFlow<ImageLoader?>(null)

    /** Days after which a cached cover is refreshed (mirrors iOS coverCacheExpirationDays). */
    val coverExpirationDays: StateFlow<Int> = appSettings.coverCacheExpirationDays
        .stateIn(scope, SharingStarted.Eagerly, AppSettingsStore.DEFAULT_COVER_CACHE_EXPIRATION_DAYS)

    /** Authenticated loader for reader page images (disk cache: `<instance>/pages`). */
    val pageLoader: StateFlow<ImageLoader?> = pageLoaderState

    /** Authenticated loader for covers and thumbnails (disk cache: `<instance>/covers`). */
    val coverLoader: StateFlow<ImageLoader?> = coverLoaderState

    init {
        rebuildOnChange(
            state = pageLoaderState,
            limits = appSettings.pageCacheLimitMb,
            kind = ImageCachePaths.KIND_PAGES,
        )
        rebuildOnChange(
            state = coverLoaderState,
            limits = appSettings.coverCacheLimitMb,
            kind = ImageCachePaths.KIND_COVERS,
        )
        // One-time migration away from the shared cache that preceded the page/cover split.
        scope.launch(Dispatchers.IO) {
            runCatching { ImageCachePaths.legacyDirectory(context.cacheDir).deleteRecursively() }
        }
    }

    private fun rebuildOnChange(
        state: MutableStateFlow<ImageLoader?>,
        limits: kotlinx.coroutines.flow.Flow<Int>,
        kind: String,
    ) {
        scope.launch {
            combine(sessionManager.clients, limits) { clients, limitMb -> clients to limitMb }
                .distinctUntilChanged { old, new ->
                    old.first === new.first && old.second == new.second
                }
                .collect { (clients, limitMb) ->
                    val previous = state.value
                    state.value = null
                    // Shut down before opening the replacement: the directory (and its journal) may be shared.
                    runCatching { previous?.shutdown() }
                    if (clients == null) return@collect
                    state.value = buildLoader(ImageCachePaths.instanceKey(clients.cacheNamespace), kind, clients.okHttpClient, limitMb)
                }
        }
    }

    private fun buildLoader(
        instanceKey: String,
        kind: String,
        okHttpClient: okhttp3.OkHttpClient,
        limitMb: Int,
    ): ImageLoader = ImageLoader.Builder(context)
        .diskCache(
            DiskCache.Builder()
                .directory(ImageCachePaths.directory(context.cacheDir, instanceKey, kind).apply { mkdirs() }.toOkioPath())
                .maxSizeBytes(limitMb * 1024L * 1024L)
                .build(),
        )
        .components { add(OkHttpNetworkFetcherFactory(callFactory = okHttpClient)) }
        .build()

    /** Namespace of the active instance, or null when no session is configured. */
    fun currentInstanceKey(): String? =
        sessionManager.clients.value?.cacheNamespace?.let(ImageCachePaths::sanitize)

    private fun loaderFor(kind: String): ImageLoader? =
        if (kind == ImageCachePaths.KIND_PAGES) pageLoaderState.value else coverLoaderState.value

    /** Cached bytes / entry count of one kind for the active instance (0 when there is no session). */
    suspend fun stats(kind: String): ImageCachePaths.Stats = withContext(Dispatchers.IO) {
        val instanceKey = currentInstanceKey() ?: return@withContext ImageCachePaths.Stats()
        ImageCachePaths.stats(ImageCachePaths.directory(context.cacheDir, instanceKey, kind))
    }

    /**
     * Clear one kind. [allServers] also drops every other instance's directory of that kind, mirroring the
     * iOS "Clear All" alert; the active instance is always cleared through its loader so Coil's journal
     * and in-memory index stay consistent.
     */
    suspend fun clear(kind: String, allServers: Boolean) = withContext(Dispatchers.IO) {
        val loader = loaderFor(kind)
        runCatching { loader?.memoryCache?.clear() }
        runCatching { loader?.diskCache?.clear() }
        if (allServers) {
            ImageCachePaths.deleteOtherInstances(context.cacheDir, kind, currentInstanceKey())
        }
    }

    /** Drop a single cover URL from memory and disk (used when its refresh interval expired the stamp). */
    fun evictCover(url: String?) {
        if (url == null) return
        val loader = coverLoaderState.value ?: return
        runCatching { loader.memoryCache?.remove(MemoryCache.Key(url)) }
        runCatching { loader.diskCache?.remove(url) }
    }

    /**
     * Drop the cached page images and page thumbnails of one book.
     *
     * Coil keys a disk entry by URL, so a book can only be targeted by reconstructing its page URLs,
     * and that needs the page list — which is gone once the book is deleted. Callers therefore run this
     * *before* deleting: one extra request per deletion buys an exact purge, whereas the alternative
     * (clearing the whole page cache) would discard every other book's pages.
     *
     * Deletions that cannot resolve a page list (offline, or a series-level delete that only knows the
     * series id) leave their entries to the page cache's own eviction. That is the trade-off of not
     * keeping a bookId → cache-key side index, which would have to be written on every page load.
     */
    suspend fun clearBookPages(bookDtoId: String) = withContext(Dispatchers.IO) {
        val clients = sessionManager.clients.value ?: return@withContext
        val loader = pageLoaderState.value ?: return@withContext
        val pages = runCatching { readerRepository.pages(bookDtoId) }.getOrDefault(emptyList())
        for (page in pages) {
            val url = "${clients.baseUrl}/api/v1/books/$bookDtoId/pages/${page.number}"
            runCatching { loader.memoryCache?.remove(MemoryCache.Key(url)) }
            runCatching { loader.diskCache?.remove(url) }
            val thumbnailUrl = "$url/thumbnail"
            runCatching { loader.memoryCache?.remove(MemoryCache.Key(thumbnailUrl)) }
            runCatching { loader.diskCache?.remove(thumbnailUrl) }
        }
    }
}
