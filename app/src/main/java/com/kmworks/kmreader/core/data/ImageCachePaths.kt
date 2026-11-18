package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.network.ServerConfig
import java.io.File

/**
 * On-disk layout of the two image caches, mirroring the iOS split between page images
 * (ImageCache) and covers (ThumbnailCache):
 *
 * ```
 * cache/images/<instanceKey>/pages    reader page images
 * cache/images/<instanceKey>/covers   book / series / collection / read list covers
 * ```
 *
 * Every server instance gets its own namespace (same identity the instance list dedupes on:
 * normalized base URL + username), so switching servers neither mixes nor evicts the other
 * instance's images, and "clear current server" can leave the others untouched.
 *
 * Coil's disk cache stores one entry as two files inside the namespace directory named
 * `<sha256-of-url>.0` and `<sha256-of-url>.1` (plus a `journal`); an entry being written has
 * `.tmp` files instead. Counting the distinct sha prefixes therefore yields the entry count
 * without keeping a side index that could drift from what is actually on disk.
 */
object ImageCachePaths {

    const val KIND_PAGES = "pages"
    const val KIND_COVERS = "covers"

    private const val LEGACY_SHARED_DIR = "image_cache"
    private const val ROOT_DIR = "images"

    /** Root directory holding one namespace directory per server instance. */
    fun root(cacheDir: File): File = File(cacheDir, ROOT_DIR)

    /** Stable namespace name for an instance configuration. */
    fun instanceKey(config: ServerConfig): String = sanitize(config.cacheNamespace)

    /** Stable namespace name from the raw `baseUrl#username` identity of a live client. */
    fun instanceKey(namespace: String): String = sanitize(namespace)

    /** Stable namespace name from the raw `baseUrl#username` identity. */
    fun sanitize(raw: String): String {
        val cleaned = raw.map { ch ->
            if (ch.isLetterOrDigit() || ch in "-._#") ch else '_'
        }.joinToString("")
        return cleaned.trim('.', '_', '#').take(96).ifBlank { "default" }
    }

    /** Directory of one cache kind for one instance (`kinds` are [KIND_PAGES] / [KIND_COVERS]). */
    fun directory(cacheDir: File, instanceKey: String, kind: String): File =
        File(File(root(cacheDir), instanceKey), kind)

    /** The single shared cache directory used before pages and covers were split (removed on upgrade). */
    fun legacyDirectory(cacheDir: File): File = File(cacheDir, LEGACY_SHARED_DIR)

    /** Cached bytes and committed entry count of one namespace directory. */
    data class Stats(val bytes: Long = 0L, val count: Int = 0)

    fun stats(directory: File): Stats {
        val files = directory.listFiles() ?: return Stats()
        var bytes = 0L
        val entries = HashSet<String>(files.size)
        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            if (name.startsWith("journal")) continue
            bytes += file.length()
            // A `.tmp` file belongs to an unfinished write and must not be counted as an entry.
            if (name.endsWith(".tmp")) continue
            entries += name.substringBeforeLast('.', name)
        }
        return Stats(bytes = bytes, count = entries.size)
    }

    /** Remove one instance's directory of one kind (used for instances other than the active one). */
    fun delete(cacheDir: File, instanceKey: String, kind: String) {
        runCatching { directory(cacheDir, instanceKey, kind).deleteRecursively() }
    }

    /** Remove every namespace except [keepInstanceKey] (the active one is cleared through Coil instead). */
    fun deleteOtherInstances(cacheDir: File, kind: String, keepInstanceKey: String?) {
        val instances = root(cacheDir).listFiles() ?: return
        instances.forEach { instanceDir ->
            if (!instanceDir.isDirectory) return@forEach
            if (instanceDir.name == keepInstanceKey) return@forEach
            runCatching { File(instanceDir, kind).deleteRecursively() }
        }
    }
}
