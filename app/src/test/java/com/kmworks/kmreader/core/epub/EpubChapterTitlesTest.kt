package com.kmworks.kmreader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chapter titles for the reading order: books split one chapter across several spine files, so an
 * untitled tail has to keep the name of the chapter it belongs to.
 */
class EpubChapterTitlesTest {

    private fun link(href: String, title: String? = null, children: List<EpubLinkDto>? = null) =
        EpubLinkDto(href = href, title = title, children = children)

    private fun fallback(index: Int) = "Chapter ${index + 1}"

    @Test
    fun `split spine files inherit the title of their chapter head`() {
        val order = listOf(
            link("OEBPS/text/ch12a.xhtml", "Chapter 12"),
            link("OEBPS/text/ch12b.xhtml"),
            link("OEBPS/text/ch12c.xhtml"),
            link("OEBPS/text/ch13.xhtml", "Chapter 13"),
            link("OEBPS/text/ch13b.xhtml"),
        )
        val titles = resolveEpubChapterTitles(order, toc = emptyList(), fallback = ::fallback)
        assertEquals(
            listOf("Chapter 12", "Chapter 12", "Chapter 12", "Chapter 13", "Chapter 13"),
            titles,
        )
    }

    @Test
    fun `an untitled head before the first title falls back to its position`() {
        val order = listOf(
            link("OEBPS/text/cover.xhtml"),
            link("OEBPS/text/ch1.xhtml", "Chapter 1"),
            link("OEBPS/text/ch1b.xhtml"),
        )
        // Distinct fallback labels, so borrowing forward from the next chapter would show up here
        val titles = resolveEpubChapterTitles(order, toc = emptyList()) { index -> "P$index" }
        assertEquals(listOf("P0", "Chapter 1", "Chapter 1"), titles)
    }

    @Test
    fun `the toc names spine items the reading order left untitled`() {
        val order = listOf(
            link("OEBPS/text/ch1.xhtml"),
            link("OEBPS/text/ch2.xhtml"),
        )
        val toc = listOf(
            link("OEBPS/text/ch1.xhtml#p1", "序章"),
            link("OEBPS/text/ch2.xhtml#p1", "第一章"),
        )
        val titles = resolveEpubChapterTitles(order, toc, fallback = ::fallback)
        assertEquals(listOf("序章", "第一章"), titles)
    }

    @Test
    fun `a deeper toc entry wins over its container`() {
        val order = listOf(link("OEBPS/text/part1.xhtml"))
        val toc = listOf(
            link(
                "OEBPS/text/part1.xhtml",
                "Part One",
                children = listOf(link("OEBPS/text/part1.xhtml#ch1", "Chapter 1")),
            ),
        )
        // The file is both the part's landing page and chapter 1's start: name it after the chapter
        assertEquals(listOf("Chapter 1"), resolveEpubChapterTitles(order, toc, fallback = ::fallback))
    }

    @Test
    fun `inheritance stops at the next titled chapter`() {
        val order = listOf(
            link("a.xhtml", "Alpha"),
            link("b.xhtml"),
            link("c.xhtml", "Gamma"),
            link("d.xhtml"),
            link("e.xhtml"),
        )
        val titles = resolveEpubChapterTitles(order, toc = emptyList(), fallback = ::fallback)
        assertEquals(listOf("Alpha", "Alpha", "Gamma", "Gamma", "Gamma"), titles)
    }
}
