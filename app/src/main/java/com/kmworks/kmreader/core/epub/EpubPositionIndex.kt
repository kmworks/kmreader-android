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
 * Compact projection of a WebPub `positions` response.
 *
 * The reader only ever needs two things from that list: how many positions each chapter holds (the
 * chapter's share of the whole book, used for the progress percentage) and that chapter's sorted
 * progression values (used to snap a reported locator onto a value the server accepts). Keeping the
 * flat list instead costs, on a long novel, megabytes: `GET /positions` for an 8,431-page book is
 * 15,865 entries / 2.5 MB, and for a 14,139-page book ~27,000 entries / 4.2 MB.
 */
data class EpubPositionIndex(
    val total: Int,
    val countByHref: Map<String, Int>,
    val progressionsByHref: Map<String, List<Float>>,
) {
    /** Share of the whole book that [href] covers, or null when the server did not place it. */
    fun weightOf(href: String): Float? {
        if (total <= 0) return null
        val count = countByHref[href] ?: return null
        return count.toFloat() / total
    }
}

/** Groups a `positions` response by chapter href (both derived values the reader consumes). */
fun buildEpubPositionIndex(positions: EpubPositionsDto): EpubPositionIndex {
    val grouped = positions.positions.groupBy { EpubHref.normalize(it.href) }
    return EpubPositionIndex(
        total = positions.total,
        countByHref = grouped.mapValues { it.value.size },
        progressionsByHref = grouped.mapValues { entry ->
            entry.value.mapNotNull { it.locations?.progression }.sorted()
        },
    )
}

/**
 * Cache identity of a positions list: the chapter hrefs the server paginated, plus the book's page
 * count. A re-imported book (different spine, different file) changes it, so a stale cache cannot
 * survive a layout change; anything that keeps the spine identical also keeps the positions valid.
 */
fun epubPositionIndexKey(chapters: List<EpubChapter>, pageCount: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    chapters.forEach {
        digest.update(it.href.toByteArray())
        digest.update(0)
    }
    digest.update(pageCount.toString().toByteArray())
    return digest.digest().take(12).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Serialisation of [EpubPositionIndex] (org.json: the reader module already depends on it). */
internal object EpubPositionIndexCodec {

    /** Bump when the encoding changes; older files are then ignored and re-fetched. */
    const val FORMAT_VERSION = 1

    /** A decoded cache file: the identity it was written for plus the index itself. */
    data class Stored(val key: String, val index: EpubPositionIndex)

    fun encode(key: String, index: EpubPositionIndex): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("key", key)
        root.put("total", index.total)
        val counts = JSONObject()
        index.countByHref.forEach { (href, count) -> counts.put(href, count) }
        root.put("counts", counts)
        val progressions = JSONObject()
        index.progressionsByHref.forEach { (href, list) ->
            // Float.toString round-trips exactly through toFloat(), so a snapped report still sends
            // back the very f32 the server advertised.
            progressions.put(href, list.joinToString(",") { it.toString() })
        }
        root.put("progressions", progressions)
        return root.toString()
    }

    fun decode(raw: String): Stored? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optInt("format", -1) != FORMAT_VERSION) return null
        val total = root.optInt("total", 0)
        if (total <= 0) return null
        val countsJson = root.optJSONObject("counts") ?: JSONObject()
        val counts = HashMap<String, Int>(countsJson.length())
        countsJson.keys().forEach { href -> counts[href] = countsJson.optInt(href) }
        val progressionsJson = root.optJSONObject("progressions") ?: JSONObject()
        val progressions = HashMap<String, List<Float>>(progressionsJson.length())
        progressionsJson.keys().forEach { href ->
            val joined = progressionsJson.optString(href, "")
            progressions[href] = if (joined.isEmpty()) {
                emptyList()
            } else {
                joined.split(',').mapNotNull { it.toFloatOrNull() }
            }
        }
        return Stored(root.optString("key", ""), EpubPositionIndex(total, counts, progressions))
    }
}

/**
 * Keeps the derived positions index of recently opened books: in memory for the current session and
 * on disk (cacheDir, so the OS may evict it) across launches.
 *
 * The caller passes the identity it expects [key]: a mismatch is treated as a miss, which keeps a
 * re-imported book from reporting percentages of its previous spine.
 */
@Singleton
class EpubPositionIndexStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val memory = object : LinkedHashMap<String, Pair<String, EpubPositionIndex>>(
        MEMORY_ENTRIES, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<String, EpubPositionIndex>>,
        ): Boolean = size > MEMORY_ENTRIES
    }

    /** Cached index for [bookDtoId] when it was written for [key]; null otherwise. Never throws. */
    suspend fun cached(bookDtoId: String, key: String): EpubPositionIndex? = withContext(Dispatchers.IO) {
        synchronized(memory) {
            val hit = memory[bookDtoId]
            if (hit != null && hit.first == key) return@withContext hit.second
        }
        val file = fileFor(bookDtoId)
        if (!file.isFile) return@withContext null
        val stored = runCatching { EpubPositionIndexCodec.decode(file.readText()) }
            .getOrNull() ?: run {
            runCatching { file.delete() }
            return@withContext null
        }
        if (stored.key != key) return@withContext null
        synchronized(memory) { memory[bookDtoId] = key to stored.index }
        stored.index
    }

    /** Remembers [index] for [bookDtoId] in memory and on disk. Never throws. */
    suspend fun remember(bookDtoId: String, key: String, index: EpubPositionIndex) {
        synchronized(memory) { memory[bookDtoId] = key to index }
        withContext(Dispatchers.IO) {
            runCatching {
                val file = fileFor(bookDtoId)
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(EpubPositionIndexCodec.encode(key, index))
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
        /** Enough for the books a reader actually moves between; each entry is small (weights + floats). */
        const val MEMORY_ENTRIES = 8
        const val DIRECTORY = "epub-positions"
    }
}
