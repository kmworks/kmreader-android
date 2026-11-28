package com.kmworks.kmreader.core.epub

import kotlin.math.roundToInt

/**
 * Where the reader sits in the whole book, counted in pages (mirrors the iOS
 * `EpubBookPageProgress`: a value type carrying `currentPage` / `totalPages`).
 *
 * The pages are the reader's own layout pages, not the server's positions: a chapter that this
 * device has laid out contributes its measured page count, and one it has not contributes an
 * estimate (see [resolveEpubBookPages]). The total therefore firms up as the reader walks the
 * book — the same bargain the iOS reader makes, where the label is an estimate until every
 * chapter has been measured.
 */
data class EpubBookPageProgress(val currentPage: Int, val totalPages: Int)

/**
 * Per-chapter page counts in reading order, plus the running offsets that turn "chapter 7, page 3"
 * into "page 421 of 8,431".
 *
 * [pagesByChapter] is index-aligned with the manifest's reading order and always at least 1 page
 * per chapter: the whole-book total must never shrink to zero when a measure pass is missing.
 */
data class EpubBookPages(val pagesByChapter: List<Int>) {

    /** Prefix sums: `prefixBefore[i]` is the number of pages in the chapters before `i`. */
    private val prefixBefore: IntArray = IntArray(pagesByChapter.size + 1).also { sums ->
        pagesByChapter.forEachIndexed { index, pages -> sums[index + 1] = sums[index] + pages }
    }

    val totalPages: Int
        get() = prefixBefore.lastOrNull() ?: 0

    /** Pages that precede [chapterIndex] (0 for the first chapter). */
    fun pagesBefore(chapterIndex: Int): Int =
        prefixBefore[chapterIndex.coerceIn(0, pagesByChapter.size)]

    /**
     * Book page of a 0-based [pageInChapter] (1-based result), clamped into the chapter: a
     * repagination that reports a page index from the port/starboard side of a page turn must not
     * push the number past the chapter's own count.
     */
    fun pageAt(chapterIndex: Int, pageInChapter: Int): Int? {
        val pages = pagesByChapter.getOrNull(chapterIndex) ?: return null
        val withinChapter = pageInChapter.coerceIn(0, pages - 1) + 1
        return pagesBefore(chapterIndex) + withinChapter
    }

    /**
     * Book page for a scrolling chapter, where the only position available is the in-chapter
     * fraction (scrolled mode reports no pages). Fraction 1 is the chapter's last page.
     */
    fun pageAt(chapterIndex: Int, chapterFraction: Float): Int? {
        val pages = pagesByChapter.getOrNull(chapterIndex) ?: return null
        val withinChapter = ((chapterFraction.coerceIn(0f, 1f) * (pages - 1)).roundToInt() + 1)
            .coerceIn(1, pages)
        return pagesBefore(chapterIndex) + withinChapter
    }
}

/**
 * Resolves the book-wide page counts: measured chapters keep their exact count, the rest are
 * estimated from the server's positions for that chapter.
 *
 * The estimate is scaled by the ratio this session has already learned (measured pages ÷ the
 * positions those same chapters carried): positions are page-like but not pages, and the ratio
 * absorbs the reader's font size, margins and viewport. Without a single measured chapter the
 * positions are taken at face value, and without positions a chapter gets the average of the
 * measured ones — anything is better than pretending a 400-page novel has 12 pages.
 */
fun resolveEpubBookPages(
    chapters: List<EpubChapter>,
    measured: Map<String, Int>,
    positionIndex: EpubPositionIndex?,
): EpubBookPages {
    if (chapters.isEmpty()) return EpubBookPages(emptyList())

    var measuredPages = 0
    var measuredPositions = 0
    var measuredChapters = 0
    chapters.forEach { chapter ->
        val pages = measured[chapter.href] ?: return@forEach
        if (pages <= 0) return@forEach
        measuredPages += pages
        measuredChapters += 1
        measuredPositions += positionIndex?.countByHref?.get(chapter.href) ?: 0
    }
    // How many of this reader's pages one server position is worth. Only meaningful once both
    // sides exist; without positions the estimate is whatever the positions themselves say.
    val pagesPerPosition = if (measuredPositions > 0) measuredPages.toFloat() / measuredPositions else 1f
    val averageMeasured = if (measuredChapters > 0) measuredPages.toFloat() / measuredChapters else null

    return EpubBookPages(
        chapters.map { chapter ->
            measured[chapter.href]?.takeIf { it > 0 } ?: estimatePages(
                href = chapter.href,
                positionIndex = positionIndex,
                pagesPerPosition = pagesPerPosition,
                averageMeasured = averageMeasured,
            )
        },
    )
}

private fun estimatePages(
    href: String,
    positionIndex: EpubPositionIndex?,
    pagesPerPosition: Float,
    averageMeasured: Float?,
): Int {
    val positions = positionIndex?.countByHref?.get(href) ?: 0
    if (positions > 0) return (positions * pagesPerPosition).roundToInt().coerceAtLeast(1)
    return averageMeasured?.roundToInt()?.coerceAtLeast(1) ?: 1
}
