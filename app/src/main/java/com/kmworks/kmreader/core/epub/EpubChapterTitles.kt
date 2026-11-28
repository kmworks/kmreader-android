package com.kmworks.kmreader.core.epub

/**
 * Titles for the manifest's reading order, resolved so that split chapters keep their name.
 *
 * A spine item is not a chapter: books routinely split one chapter across several files (a 4 MB
 * "chapter" becomes `ch12a.xhtml`, `ch12b.xhtml`), and only the first file carries the toc entry —
 * Komga's `readingOrder` links then have a title for the head and none for the rest. Numbering
 * those tails by position produced a table of contents reading "Chapter 12, Chapter 13, Chapter
 * 14" for what is one chapter, and a progress label naming the wrong chapter.
 *
 * Resolution order per spine item: the manifest's own link title, then the title the toc gives that
 * file, then the title of the nearest preceding spine item that HAD one, and only then the
 * positional fallback. Inheritance deliberately stops at chapters with an explicit title, so an
 * untitled front-matter file before the first chapter still falls back to its position instead of
 * borrowing the chapter that follows it.
 *
 * [fallback] supplies the positional label (already localized) for spine items nothing named.
 */
fun resolveEpubChapterTitles(
    order: List<EpubLinkDto>,
    toc: List<EpubLinkDto>,
    fallback: (Int) -> String,
): List<String> {
    val tocTitles = tocTitlesByHref(toc)
    var inherited: String? = null
    return order.mapIndexed { index, link ->
        val href = EpubHref.normalize(link.href)
        val explicit = link.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: tocTitles[href]?.takeIf { it.isNotEmpty() }
        if (explicit != null) inherited = explicit
        explicit ?: inherited ?: fallback(index)
    }
}

/**
 * First title the toc gives each file, deeper entries winning over their containers: a file that is
 * both a part's landing page and a chapter start should be named after the chapter.
 */
private fun tocTitlesByHref(toc: List<EpubLinkDto>): Map<String, String> {
    val titles = HashMap<String, String>()
    fun walk(links: List<EpubLinkDto>) {
        links.forEach { link ->
            val href = EpubHref.normalize(link.href)
            val title = link.title?.trim()
            if (href.isNotEmpty() && !title.isNullOrEmpty()) titles[href] = title
            link.children?.let(::walk)
        }
    }
    walk(toc)
    return titles
}
