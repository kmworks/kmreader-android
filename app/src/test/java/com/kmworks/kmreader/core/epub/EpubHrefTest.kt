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
