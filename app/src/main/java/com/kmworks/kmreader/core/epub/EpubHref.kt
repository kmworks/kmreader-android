package com.kmworks.kmreader.core.epub

import java.net.URI
import java.net.URLDecoder

/**
 * Normalizes WebPub hrefs so the same resource is recognized no matter how it was written:
 * `%20` vs space, `./` prefixes, `../` segments, fragments (`#id`), query strings, Windows
 * separators, or a host pasted in front of the path.
 *
 * The reader needs this in three places, all of which used to compare hrefs verbatim:
 * restoring the saved progression (Komga may answer with a different spelling than the
 * manifest), matching a TOC entry to a reading-order chapter, and looking a resource up in
 * the downloaded `book.epub` zip (zip entry names are plain paths).
 */
object EpubHref {

    /** Path-style form (no fragment, no query, no host, no leading slash, dot segments resolved). */
    fun normalize(href: String?): String {
        if (href.isNullOrBlank()) return ""
        var path = href.trim().substringBefore('#').substringBefore('?')
        path = path.replace('\\', '/')
        // An absolute URL is reduced to its path first; the fake-domain reader never uses hosts.
        if (path.contains("://")) {
            path = runCatching { URI(path).path.orEmpty() }.getOrDefault(path)
        }
        path = decode(path).trimStart('/')
        val segments = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    /** True when both hrefs point at the same resource (blank hrefs never match). */
    fun matches(first: String?, second: String?): Boolean {
        val normalized = normalize(first)
        return normalized.isNotEmpty() && normalized == normalize(second)
    }

    private fun decode(value: String): String =
        runCatching {
            // Plus signs are literal in paths; the URL form-encoding rule does not apply here.
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(value)
}
