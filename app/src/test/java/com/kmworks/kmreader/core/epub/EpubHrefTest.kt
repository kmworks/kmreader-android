package com.kmworks.kmreader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubHrefTest {

    @Test
    fun `normalize drops fragment and query`() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubHref.normalize("OEBPS/text/ch1.xhtml#p3"))
        assertEquals("OEBPS/text/ch1.xhtml", EpubHref.normalize("OEBPS/text/ch1.xhtml?x=1&y=2"))
    }

    @Test
    fun `normalize resolves dot segments and percent escapes`() {
        assertEquals("OEBPS/images/a b.png", EpubHref.normalize("OEBPS/text/../images/a%20b.png"))
        assertEquals("ch1.xhtml", EpubHref.normalize("./ch1.xhtml"))
        assertEquals("ch1.xhtml", EpubHref.normalize("../text/../ch1.xhtml"))
    }

    @Test
    fun `normalize unifies slashes and strips the leading slash`() {
        assertEquals("dir/sub/file.xhtml", EpubHref.normalize("dir\\sub\\file.xhtml"))
        assertEquals("leading/slash.xhtml", EpubHref.normalize("/leading/slash.xhtml"))
        assertEquals("dir/file.xhtml", EpubHref.normalize("dir//file.xhtml"))
    }

    @Test
    fun `normalize reduces an absolute URL to its path`() {
        assertEquals("a/b.xhtml", EpubHref.normalize("https://example.com/a/b.xhtml#frag"))
    }

    @Test
    fun `normalize strips the Komga API resource route`() {
        assertEquals(
            "OEBPS/Text/cover.xhtml",
            EpubHref.normalize("/api/v1/books/0RX2Y7PCCC89R/resource/OEBPS/Text/cover.xhtml"),
        )
        assertEquals(
            "OEBPS/Text/cover.xhtml",
            EpubHref.normalize(
                "https://komga.example.com/api/v1/books/0RX2Y7PCCC89R/resource/OEBPS/Text/cover.xhtml",
            ),
        )
        // Sub-path deployment: the base-URL path stays ahead of the API route.
        assertEquals(
            "OEBPS/Text/cover.xhtml",
            EpubHref.normalize("https://example.com/komga/api/v1/books/abc/resource/OEBPS/Text/cover.xhtml"),
        )
    }

    @Test
    fun `normalize keeps in-book paths that merely resemble the route`() {
        // Positions/progression hrefs are already plain in-book paths and must pass through.
        assertEquals("OEBPS/Text/cover.xhtml", EpubHref.normalize("OEBPS/Text/cover.xhtml"))
        // Only the full api/v1/books/{id}/resource sequence is stripped.
        assertEquals("api/v1/books/x/OEBPS/a.xhtml", EpubHref.normalize("api/v1/books/x/OEBPS/a.xhtml"))
    }

    @Test
    fun `normalize keeps plus signs literal`() {
        assertEquals("a+b.xhtml", EpubHref.normalize("a+b.xhtml"))
        assertEquals("a+b.xhtml", EpubHref.normalize("a%2Bb.xhtml"))
    }

    @Test
    fun `blank hrefs normalize to empty`() {
        assertEquals("", EpubHref.normalize(null))
        assertEquals("", EpubHref.normalize("  "))
        assertEquals("", EpubHref.normalize("#fragment"))
    }

    @Test
    fun `matches accepts different spellings of the same resource`() {
        assertTrue(EpubHref.matches("OEBPS/ch1.xhtml#a", "/OEBPS/ch1.xhtml"))
        assertTrue(EpubHref.matches("a/b.xhtml", "a/./b.xhtml"))
        assertTrue(EpubHref.matches("a%20b.xhtml", "a b.xhtml"))
    }

    @Test
    fun `matches rejects blanks and different resources`() {
        assertFalse(EpubHref.matches(null, ""))
        assertFalse(EpubHref.matches("", ""))
        assertFalse(EpubHref.matches("a/b.xhtml", "a/c.xhtml"))
        assertFalse(EpubHref.matches("a/b.xhtml", "b/a.xhtml"))
    }
}
