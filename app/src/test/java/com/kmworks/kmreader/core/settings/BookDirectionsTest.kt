package com.kmworks.kmreader.core.settings

import com.kmworks.kmreader.ui.reader.ReadingDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-book remembered reading direction ("bookDtoId:Direction||…", mirrors the divinaSoloPages format). */
class BookDirectionsTest {

    @Test
    fun parsesRememberedDirectionsPerBook() {
        val parsed = parseBookDirections("book-a:Rtl||book-b:Vertical")

        assertEquals(
            mapOf("book-a" to ReadingDirection.Rtl, "book-b" to ReadingDirection.Vertical),
            parsed,
        )
    }

    @Test
    fun missingOrBlankValueParsesToEmpty() {
        assertEquals(emptyMap<String, ReadingDirection>(), parseBookDirections(null))
        assertEquals(emptyMap<String, ReadingDirection>(), parseBookDirections(""))
    }

    @Test
    fun unknownNamesAndBrokenEntriesAreSkipped() {
        // A build that dropped a mode, a missing separator, an empty id and an empty name all drop out
        // instead of failing the whole map (one bad entry must not forget every remembered book)
        val parsed = parseBookDirections("book-a:Gone||book-b||:Ltr||book-c:||book-d:Webtoon")

        assertEquals(mapOf("book-d" to ReadingDirection.Webtoon), parsed)
    }

    @Test
    fun formatDropsEmptyMapsAndRoundTrips() {
        assertNull(formatBookDirections(emptyMap()))

        val directions = mapOf("book-b" to ReadingDirection.Ltr, "book-a" to ReadingDirection.Rtl)
        val raw = formatBookDirections(directions)

        assertEquals("book-a:Rtl||book-b:Ltr", raw)
        assertEquals(directions, parseBookDirections(raw))
    }
}
