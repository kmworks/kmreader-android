package com.kmworks.kmreader.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One flattened table-of-contents row parsed from the PDF outline.
 *
 * @param title display title (may be blank for malformed outlines)
 * @param pageIndex zero-based target page
 * @param depth nesting depth, root entries are 0
 */
data class PdfTocEntry(
    val title: String,
    val pageIndex: Int,
    val depth: Int,
)

/**
 * Extracts the PDF outline (bookmarks) with PdfBox-Android.
 *
 * androidx.pdf beta01 ships no outline API (verified by decompiling the AARs; see
 * docs/pdf-native-reader-plan.md §3), so TOC parsing is isolated here. Failures —
 * malformed files, password-protected documents, missing destinations — degrade to an
 * empty list so the caller can simply hide the TOC entry point.
 *
 * Results are cached per (path, lastModified) in a small LRU because the same file is
 * re-extracted on every reader open.
 */
@Singleton
class PdfOutlineExtractor @Inject constructor() {

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, List<PdfTocEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PdfTocEntry>>?): Boolean =
            size > MAX_CACHED_DOCUMENTS
    }

    /** Parse the outline of [file]; empty list when the file has none or cannot be parsed. */
    suspend fun extract(file: File): List<PdfTocEntry> {
        val key = "${file.absolutePath}:${file.lastModified()}"
        synchronized(cacheLock) { cache[key] }?.let { return it }
        val parsed = withContext(Dispatchers.IO) { runCatching { parseOutline(file) }.getOrNull() }
        val result = parsed.orEmpty()
        synchronized(cacheLock) { cache[key] = result }
        return result
    }

    private fun parseOutline(file: File): List<PdfTocEntry> {
        PDDocument.load(file, "", MemoryUsageSetting.setupTempFileOnly()).use { document ->
            val outline = document.documentCatalog.documentOutline ?: return emptyList()
            val entries = mutableListOf<PdfTocEntry>()
            var item = outline.firstChild
            while (item != null) {
                appendItem(item, document, depth = 0, entries)
                item = item.nextSibling
            }
            return entries
        }
    }

    private fun appendItem(
        item: PDOutlineItem,
        document: PDDocument,
        depth: Int,
        entries: MutableList<PdfTocEntry>,
    ) {
        // findDestinationPage resolves named destinations and GoTo actions; null when the
        // entry has no page target (e.g. a pure grouping node or external action).
        val page = runCatching { item.findDestinationPage(document) }.getOrNull()
        if (page != null) {
            val pageIndex = document.pages.indexOf(page)
            if (pageIndex >= 0) {
                entries += PdfTocEntry(
                    title = item.title.orEmpty(),
                    pageIndex = pageIndex,
                    depth = depth,
                )
            }
        }
        var child = item.firstChild
        while (child != null) {
            appendItem(child, document, depth + 1, entries)
            child = child.nextSibling
        }
    }

    private companion object {
        const val MAX_CACHED_DOCUMENTS = 8
    }
}
