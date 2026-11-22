package com.kmworks.kmreader.core.settings

import com.kmworks.kmreader.ui.reader.ReadingDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-book remembered reading direction ("bookDtoId:Direction||…", mirrors the divinaSoloPages format). */
class BookDirectionsTest {

    @Test
    fun parsesRememberedDirectionsPerBook() {
        val parsed = parseDirectionMap("book-a:Rtl||book-b:Vertical")

        assertEquals(
            mapOf("book-a" to ReadingDirection.Rtl, "book-b" to ReadingDirection.Vertical),
            parsed,
        )
    }

    @Test
    fun missingOrBlankValueParsesToEmpty() {
        assertEquals(emptyMap<String, ReadingDirection>(), parseDirectionMap(null))
        assertEquals(emptyMap<String, ReadingDirection>(), parseDirectionMap(""))
    }

    @Test
    fun unknownNamesAndBrokenEntriesAreSkipped() {
        // A build that dropped a mode, a missing separator, an empty id and an empty name all drop out
        // instead of failing the whole map (one bad entry must not forget every remembered book)
        val parsed = parseDirectionMap("book-a:Gone||book-b||:Ltr||book-c:||book-d:Webtoon")

        assertEquals(mapOf("book-d" to ReadingDirection.Webtoon), parsed)
    }

    @Test
    fun formatDropsEmptyMapsAndRoundTrips() {
        assertNull(formatDirectionMap(emptyMap()))

        val directions = mapOf("book-b" to ReadingDirection.Ltr, "book-a" to ReadingDirection.Rtl)
        val raw = formatDirectionMap(directions)

        assertEquals("book-a:Rtl||book-b:Ltr", raw)
        assertEquals(directions, parseDirectionMap(raw))
    }

    @Test
    fun bookExceptionWinsOverTheSeriesValue() {
        val remembered = resolveRememberedDirection(
            bookDirections = mapOf("book-2" to ReadingDirection.Ltr),
            seriesDirections = mapOf("series-1" to ReadingDirection.Rtl),
            bookDtoId = "book-2",
            seriesDtoId = "series-1",
        )

        assertEquals(RememberedDirection(ReadingDirection.Ltr, perBook = true), remembered)
    }

    @Test
    fun seriesValueAppliesToEveryVolumeWithoutAnException() {
        // Volume 3 has no exception, so continuing the series uses the series-wide choice
        val remembered = resolveRememberedDirection(
            bookDirections = mapOf("book-2" to ReadingDirection.Ltr),
            seriesDirections = mapOf("series-1" to ReadingDirection.Rtl),
            bookDtoId = "book-3",
            seriesDtoId = "series-1",
        )

        assertEquals(RememberedDirection(ReadingDirection.Rtl, perBook = false), remembered)
    }

    @Test
    fun nothingRememberedFallsBackToTheResolver() {
        assertNull(
            resolveRememberedDirection(
                bookDirections = emptyMap(),
                seriesDirections = emptyMap(),
                bookDtoId = "book-1",
                seriesDtoId = "series-1",
            ),
        )
        // Unknown ids (e.g. a book without series metadata) must not throw or match something else
        assertNull(
            resolveRememberedDirection(
                bookDirections = mapOf("book-1" to ReadingDirection.Ltr),
                seriesDirections = mapOf("series-1" to ReadingDirection.Ltr),
                bookDtoId = null,
                seriesDtoId = null,
            ),
        )
    }
}
