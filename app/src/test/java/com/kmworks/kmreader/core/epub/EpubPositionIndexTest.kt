package com.kmworks.kmreader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPositionIndexTest {

    private fun locator(href: String, progression: Float) = EpubLocatorDto(
        href = href,
        type = "application/xhtml+xml",
        locations = EpubLocatorLocationsDto(progression = progression),
    )

    private fun positions(vararg entries: Pair<String, Float>, total: Int) = EpubPositionsDto(
        positions = entries.map { locator(it.first, it.second) },
        total = total,
    )

    private fun chapter(index: Int, href: String) = EpubChapter(index = index, href = href, title = "c$index")

    @Test
    fun `build groups positions per chapter and derives the chapter weight`() {
        val index = buildEpubPositionIndex(
            positions(
                "OEBPS/Text/ch1.xhtml" to 0.0f,
                "OEBPS/Text/ch1.xhtml" to 0.5f,
                "OEBPS/Text/ch1.xhtml" to 1.0f,
                "OEBPS/Text/ch2.xhtml" to 0.0f,
                total = 4,
            ),
        )

        assertEquals(4, index.total)
        assertEquals(0.75f, index.weightOf("OEBPS/Text/ch1.xhtml")!!, 1e-6f)
        assertEquals(0.25f, index.weightOf("OEBPS/Text/ch2.xhtml")!!, 1e-6f)
        assertEquals(listOf(0.0f, 0.5f, 1.0f), index.progressionsByHref["OEBPS/Text/ch1.xhtml"])
    }

    @Test
    fun `build normalizes hrefs and sorts progressions`() {
        val index = buildEpubPositionIndex(
            positions(
                "OEBPS/Text/ch1.xhtml#p9" to 0.9f,
                "./OEBPS/Text/ch1.xhtml" to 0.1f,
                total = 2,
            ),
        )

        assertEquals(listOf(0.1f, 0.9f), index.progressionsByHref["OEBPS/Text/ch1.xhtml"])
        assertEquals(1.0f, index.weightOf("OEBPS/Text/ch1.xhtml")!!, 1e-6f)
    }

    @Test
    fun `weight is null without positions or for a chapter the server did not place`() {
        assertNull(buildEpubPositionIndex(positions(total = 0)).weightOf("OEBPS/Text/ch1.xhtml"))
        assertNull(buildEpubPositionIndex(positions("a.xhtml" to 0.0f, total = 1)).weightOf("b.xhtml"))
    }

    @Test
    fun `codec round-trips exact float proportions`() {
        // Values that are awkward in decimal: the snapped report must send back the very f32 the
        // server advertised, so the text form has to survive Float.toString/parseFloat untouched.
        val authored = listOf(0.0f, 0.00006303183f, 0.34285715f, 0.9999999f, 1.0f)
        val index = buildEpubPositionIndex(
            positions(*authored.map { "OEBPS/Text/ch1.xhtml" to it }.toTypedArray(), total = authored.size),
        )

        val stored = EpubPositionIndexCodec.decode(EpubPositionIndexCodec.encode("k1", index))!!

        assertEquals("k1", stored.key)
        assertEquals(index.total, stored.index.total)
        assertEquals(authored, stored.index.progressionsByHref["OEBPS/Text/ch1.xhtml"])
        assertEquals(1.0f, stored.index.weightOf("OEBPS/Text/ch1.xhtml")!!, 1e-6f)
    }

    @Test
    fun `codec rejects unknown formats and garbage`() {
        val index = buildEpubPositionIndex(positions("a.xhtml" to 0.0f, total = 1))
        val encoded = EpubPositionIndexCodec.encode("k", index)
        val bumped = encoded.replace("\"format\":1", "\"format\":99")

        assertNull(EpubPositionIndexCodec.decode(bumped))
        assertNull(EpubPositionIndexCodec.decode("not json at all"))
        assertNull(EpubPositionIndexCodec.decode("{}"))
    }

    @Test
    fun `cache key follows the spine and the page count`() {
        val chapters = listOf(chapter(0, "a.xhtml"), chapter(1, "b.xhtml"))
        val key = epubPositionIndexKey(chapters, 120)

        assertEquals(key, epubPositionIndexKey(chapters, 120))
        assertNotEquals(key, epubPositionIndexKey(listOf(chapter(0, "a.xhtml"), chapter(1, "c.xhtml")), 120))
        assertNotEquals(key, epubPositionIndexKey(chapters, 121))
        assertNotEquals(key, epubPositionIndexKey(chapters + chapter(2, "extra.xhtml"), 120))
        assertTrue(key.length == 24)
    }
}
