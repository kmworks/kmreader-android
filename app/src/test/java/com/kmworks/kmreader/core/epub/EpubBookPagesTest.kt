package com.kmworks.kmreader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whole-book page counts: measured chapters exact, the rest estimated from the server's positions,
 * and a page number that never leaves the chapter it belongs to.
 */
class EpubBookPagesTest {

    private fun chapter(index: Int, href: String) = EpubChapter(index = index, href = href, title = "c$index")

    private fun positions(vararg counts: Pair<String, Int>): EpubPositionIndex {
        val byHref = counts.toMap()
        return EpubPositionIndex(
            total = counts.sumOf { it.second },
            countByHref = byHref,
            progressionsByHref = emptyMap(),
        )
    }

    @Test
    fun `measured chapters keep their count and unmeasured ones are scaled from positions`() {
        val chapters = listOf(chapter(0, "ch1.xhtml"), chapter(1, "ch2.xhtml"), chapter(2, "ch3.xhtml"))
        // ch1 measured at 10 pages but the server placed 20 positions in it: one position is worth
        // half a page here, so ch2's 10 positions become 5 pages and ch3's 30 become 15.
        val pages = resolveEpubBookPages(
            chapters = chapters,
            measured = mapOf("ch1.xhtml" to 10),
            positionIndex = positions("ch1.xhtml" to 20, "ch2.xhtml" to 10, "ch3.xhtml" to 30),
        )

        assertEquals(listOf(10, 5, 15), pages.pagesByChapter)
        assertEquals(30, pages.totalPages)
    }

    @Test
    fun `without a measured chapter the positions stand in as pages`() {
        val chapters = listOf(chapter(0, "ch1.xhtml"), chapter(1, "ch2.xhtml"))
        val pages = resolveEpubBookPages(
            chapters = chapters,
            measured = emptyMap(),
            positionIndex = positions("ch1.xhtml" to 120, "ch2.xhtml" to 80),
        )
        assertEquals(listOf(120, 80), pages.pagesByChapter)
        assertEquals(200, pages.totalPages)
    }

    @Test
    fun `without positions an unmeasured chapter gets the measured average`() {
        val chapters = listOf(chapter(0, "ch1.xhtml"), chapter(1, "ch2.xhtml"), chapter(2, "ch3.xhtml"))
        val pages = resolveEpubBookPages(
            chapters = chapters,
            measured = mapOf("ch1.xhtml" to 8, "ch2.xhtml" to 12),
            positionIndex = null,
        )
        assertEquals(listOf(8, 12, 10), pages.pagesByChapter)
    }

    @Test
    fun `every chapter counts at least one page`() {
        val chapters = listOf(chapter(0, "ch1.xhtml"), chapter(1, "ch2.xhtml"))
        val pages = resolveEpubBookPages(chapters, emptyMap(), positionIndex = null)
        assertEquals(listOf(1, 1), pages.pagesByChapter)
        assertEquals(2, pages.totalPages)
    }

    @Test
    fun `page numbers are offset by the chapters before and clamped inside their chapter`() {
        val pages = EpubBookPages(listOf(10, 20, 30))

        assertEquals(0, pages.pagesBefore(0))
        assertEquals(10, pages.pagesBefore(1))
        assertEquals(30, pages.pagesBefore(2))
        assertEquals(60, pages.totalPages)

        assertEquals(11, pages.pageAt(1, pageInChapter = 0))
        assertEquals(30, pages.pageAt(1, pageInChapter = 19))
        // A page index from the previous pagination must not push past the chapter's own count
        assertEquals(30, pages.pageAt(1, pageInChapter = 45))
        assertEquals(11, pages.pageAt(1, pageInChapter = -3))
        assertNull(pages.pageAt(3, pageInChapter = 0))
    }

    @Test
    fun `a scrolled chapter maps its fraction onto its pages`() {
        val pages = EpubBookPages(listOf(10, 20, 30))

        assertEquals(11, pages.pageAt(1, 0f))
        assertEquals(30, pages.pageAt(1, 1f))
        // Halfway through a 20-page chapter lands on its 11th page (10 pages before + 1)
        assertEquals(21, pages.pageAt(1, 0.5f))
        assertNull(pages.pageAt(-1, 0f))
    }
}
