package com.kmworks.kmreader.core.epub

import android.util.Xml
import java.io.StringReader
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

/**
 * Reads a downloaded `book.epub` directly instead of asking the server for every resource.
 *
 * The EPUB is a zip: resources are looked up by normalized path, and the WebPub manifest the
 * reader needs while offline is rebuilt from `META-INF/container.xml` + the OPF package document
 * (spine → reading order, manifest → resources, EPUB3 nav or EPUB2 NCX → table of contents).
 */
object EpubZip {

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "svg")

    /** Resource bytes for [href], or null when the archive does not contain it. */
    fun resource(epub: File, href: String): EpubResource? {
        val path = resolveEntryName(epub, href) ?: return null
        return readEntry(epub, path)?.let { data ->
            val mime = EpubWeb.mediaTypeFor(path)
            val encoding = if (isTextMediaType(mime)) "utf-8" else null
            EpubResource(data, mime, encoding)
        }
    }

    /** WebPub manifest rebuilt from the package document; null when it cannot be parsed. */
    fun manifest(epub: File): EpubManifestDto? {
        val opfPath = containerRootPath(epub) ?: return null
        val rawOpf = readEntry(epub, opfPath) ?: return null
        val opfDir = opfPath.substringBeforeLast('/', "")
        val items = mutableListOf<PackageItem>()
        val spineIds = mutableListOf<String>()
        val navHrefs = mutableListOf<String>()
        val ncxHrefs = mutableListOf<String>()
        var spineTocId: String? = null
        var readingProgression: String? = null
        try {
            val parser = newParser(rawOpf.decodeToString())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "item" -> {
                            val href = parser.attributeValue("href") ?: ""
                            val mediaType = parser.attributeValue("media-type") ?: ""
                            val properties = parser.attributeValue("properties") ?: ""
                            val id = parser.attributeValue("id") ?: ""
                            if (href.isNotBlank()) {
                                items += PackageItem(id = id, href = combine(opfDir, href), mediaType = mediaType)
                                if (properties.split(' ').any { it.equals("nav", ignoreCase = true) }) {
                                    navHrefs += combine(opfDir, href)
                                }
                                if (mediaType.equals("application/x-dtbncx+xml", ignoreCase = true)) {
                                    ncxHrefs += combine(opfDir, href)
                                }
                            }
                        }
                        "spine" -> {
                            spineTocId = parser.attributeValue("toc")
                            val direction = parser.attributeValue("page-progression-direction")
                            if (!direction.isNullOrBlank()) readingProgression = direction
                        }
                        "itemref" -> parser.attributeValue("idref")?.takeIf { it.isNotBlank() }?.let { spineIds += it }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            return null
        }
        if (items.isEmpty() || spineIds.isEmpty()) return null

        val byId = items.associateBy { it.id }
        val readingOrder = spineIds.mapNotNull { id ->
            byId[id]?.let { EpubLinkDto(href = it.href, type = it.mediaType) }
        }
        if (readingOrder.isEmpty()) return null

        val toc = readToc(
            epub = epub,
            navHrefs = navHrefs,
            ncxHref = ncxHrefs.firstOrNull()
                ?: spineTocId?.let { byId[it]?.href },
        )
        val resources = items
            .filterNot { item -> readingOrder.any { EpubHref.matches(it.href, item.href) } }
            .map { EpubLinkDto(href = it.href, type = it.mediaType) }
        return EpubManifestDto(
            readingOrder = readingOrder,
            resources = resources,
            toc = toc,
            metadata = EpubManifestMetadataDto(readingProgression = readingProgression),
        )
    }

    /** True when [href] is an image inside the archive (used for synthetic cover entries). */
    fun isImageEntry(href: String): Boolean =
        href.substringAfterLast('/').substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private data class PackageItem(val id: String, val href: String, val mediaType: String)

    private fun containerRootPath(epub: File): String? {
        val raw = readEntry(epub, CONTAINER_PATH) ?: return null
        return Regex("""full-path\s*=\s*"([^"]+)"""").find(raw.decodeToString())?.groupValues?.getOrNull(1)
    }

    private fun readToc(epub: File, navHrefs: List<String>, ncxHref: String?): List<EpubLinkDto> {
        navHrefs.forEach { navHref ->
            val data = readEntry(epub, navHref) ?: return@forEach
            val links = parseNav(data.decodeToString(), navHref.substringBeforeLast('/', ""))
            if (links.isNotEmpty()) return links
        }
        if (ncxHref != null) {
            val data = readEntry(epub, ncxHref) ?: return emptyList()
            return parseNcx(data.decodeToString(), ncxHref.substringBeforeLast('/', ""))
        }
        return emptyList()
    }

    /** EPUB3 nav document: the `toc` nav (else the first nav) → its anchors, flattened. */
    private fun parseNav(raw: String, baseDir: String): List<EpubLinkDto> {
        return runCatching {
            val parser = newParser(raw)
            val links = mutableListOf<EpubLinkDto>()
            var event = parser.eventType
            var insideTocNav = false
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "nav" -> {
                            val type = parser.attributeValue("type")?.lowercase()
                            // The first nav (or the one explicitly marked toc) is the table of contents.
                            insideTocNav = type == null || type.contains("toc")
                        }
                        "a" -> if (insideTocNav) {
                            val href = parser.attributeValue("href")
                            if (!href.isNullOrBlank() && !href.startsWith("http")) {
                                links += EpubLinkDto(href = combine(baseDir, href), title = parser.nextText().trim().ifBlank { null })
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name.equals("nav", ignoreCase = true)) {
                    insideTocNav = false
                }
                event = parser.next()
            }
            links
        }.getOrDefault(emptyList())
    }

    /** EPUB2 NCX: navPoint/navLabel/value + content/src, flattened. */
    private fun parseNcx(raw: String, baseDir: String): List<EpubLinkDto> {
        return runCatching {
            val parser = newParser(raw)
            val links = mutableListOf<EpubLinkDto>()
            var event = parser.eventType
            var pendingTitle: String? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "text" -> pendingTitle = parser.nextText().trim().ifBlank { null }
                        "content" -> {
                            val src = parser.attributeValue("src")
                            if (!src.isNullOrBlank()) {
                                links += EpubLinkDto(href = combine(baseDir, src), title = pendingTitle)
                                pendingTitle = null
                            }
                        }
                    }
                }
                event = parser.next()
            }
            links
        }.getOrDefault(emptyList())
    }

    private fun newParser(raw: String): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(raw))
        return parser
    }

    private fun XmlPullParser.attributeValue(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).equals(name, ignoreCase = true)) return getAttributeValue(index)
        }
        return null
    }

    private fun readEntry(epub: File, path: String): ByteArray? = runCatching {
        ZipFile(epub).use { zip ->
            val entry = entryFor(zip, path) ?: return@use null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull()

    private fun resolveEntryName(epub: File, href: String): String? {
        val normalized = EpubHref.normalize(href)
        if (normalized.isEmpty()) return null
        return runCatching {
            ZipFile(epub).use { zip ->
                entryFor(zip, normalized)?.let { EpubHref.normalize(it.name) }
            }
        }.getOrNull()
    }

    /** Exact entry first, then a case-insensitive match (EPUB zips are not always written consistently). */
    private fun entryFor(zip: ZipFile, path: String): ZipEntry? {
        zip.getEntry(path)?.let { return it }
        val lower = path.lowercase()
        return zip.entries().asSequence().firstOrNull {
            !it.isDirectory && EpubHref.normalize(it.name).lowercase() == lower
        }
    }

    private fun combine(baseDir: String, href: String): String {
        val path = EpubHref.normalize(href)
        val base = EpubHref.normalize(baseDir)
        return if (base.isEmpty()) path else EpubHref.normalize("$base/$path")
    }

    private fun isTextMediaType(mime: String): Boolean =
        mime.startsWith("text/") || mime == "application/xhtml+xml" ||
            mime == "application/xml" || mime == "application/javascript" ||
            mime == "image/svg+xml"
}
