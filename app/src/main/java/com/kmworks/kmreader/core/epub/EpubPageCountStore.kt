package com.kmworks.kmreader.core.epub

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cache identity of the measured page counts: the spine the counts were measured against plus the
 * layout that produced them.
 *
 * Page counts are a property of the LAYOUT, not of the book: a chapter measured at 18sp with wide
 * margins becomes a different number of pages at 26sp, so reusing those counts after a theme change
 * would print a wrong "page 421 of 8,431". The caller passes a [layout] signature of the settings
 * that decide pagination (font size, line height, margins, columns, viewport, insets), and a
 * mismatch is a cache miss — the estimates take over until the chapters are measured again.
 */
fun epubPageCountKey(chapters: List<EpubChapter>, layout: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    chapters.forEach {
        digest.update(it.href.toByteArray())
        digest.update(0)
    }
    digest.update(layout.toByteArray())
    return digest.digest().take(12).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Serialisation of `href → pages` (org.json: the reader module already depends on it). */
internal object EpubPageCountCodec {

    /** Bump when the encoding changes; older files are then ignored and re-measured. */
    const val FORMAT_VERSION = 1

    data class Stored(val key: String, val counts: Map<String, Int>)

    fun encode(key: String, counts: Map<String, Int>): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("key", key)
        val json = JSONObject()
        counts.forEach { (href, pages) -> json.put(href, pages) }
        root.put("counts", json)
        return root.toString()
    }

    fun decode(raw: String): Stored? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optInt("format", -1) != FORMAT_VERSION) return null
        val countsJson = root.optJSONObject("counts") ?: return null
        val counts = HashMap<String, Int>(countsJson.length())
        countsJson.keys().forEach { href ->
            val pages = countsJson.optInt(href, 0)
            if (pages > 0) counts[href] = pages
        }
        return Stored(root.optString("key", ""), counts)
    }
}

/**
 * Keeps the measured page counts of recently opened books: in memory for the current session and on
 * disk (cacheDir, so the OS may evict it) across launches.
 *
 * The point of the disk copy is the reopening read: the whole-book page total is the sum over every
 * chapter, and without it the reader has to lay the book out chapter by chapter before the total
 * means anything. The caller passes the identity it expects [key]; a mismatch (a re-imported book,
 * a different layout) is a miss.
 */
@Singleton
class EpubPageCountStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val memory = object : LinkedHashMap<String, Pair<String, Map<String, Int>>>(
        MEMORY_ENTRIES, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<String, Map<String, Int>>>,
        ): Boolean = size > MEMORY_ENTRIES
    }

    /** Cached counts for [bookDtoId] when they were written for [key]; null otherwise. Never throws. */
    suspend fun cached(bookDtoId: String, key: String): Map<String, Int>? =
        withContext(Dispatchers.IO) {
            synchronized(memory) {
                val hit = memory[bookDtoId]
                if (hit != null && hit.first == key) return@withContext hit.second
            }
            val file = fileFor(bookDtoId)
            if (!file.isFile) return@withContext null
            val stored = runCatching { EpubPageCountCodec.decode(file.readText()) }
                .getOrNull() ?: run {
                runCatching { file.delete() }
                return@withContext null
            }
            if (stored.key != key) return@withContext null
            synchronized(memory) { memory[bookDtoId] = key to stored.counts }
            stored.counts
        }

    /** Remembers [counts] for [bookDtoId] in memory and on disk. Never throws. */
    suspend fun remember(bookDtoId: String, key: String, counts: Map<String, Int>) {
        if (counts.isEmpty()) return
        synchronized(memory) { memory[bookDtoId] = key to counts }
        withContext(Dispatchers.IO) {
            runCatching {
                val file = fileFor(bookDtoId)
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(EpubPageCountCodec.encode(key, counts))
                if (!temp.renameTo(file)) {
                    // Windows/older Android: rename over an existing file is not atomic, so replace.
                    file.delete()
                    if (!temp.renameTo(file)) temp.delete()
                }
            }
        }
    }

    private fun fileFor(bookDtoId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(bookDtoId.toByteArray())
        val name = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xFF) } + ".json"
        return File(File(context.cacheDir, DIRECTORY), name)
    }

    private companion object {
        /** Enough for the books a reader actually moves between; each entry is one int per chapter. */
        const val MEMORY_ENTRIES = 8
        const val DIRECTORY = "epub-page-counts"
    }
}
