package com.kmworks.kmreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingDirectionResolverTest {

    @Test
    fun forceDefaultWinsOverTheSeries() {
        assertEquals(
            ReadingDirection.Ltr,
            resolveReadingDirection(
                seriesDirection = ReadingDirection.Rtl,
                forceDefault = true,
                defaultDirection = ReadingDirection.Ltr,
            ),
        )
    }

    @Test
    fun seriesDirectionWinsOverTheDefault() {
        assertEquals(
            ReadingDirection.Rtl,
            resolveReadingDirection(
                seriesDirection = ReadingDirection.Rtl,
                forceDefault = false,
                defaultDirection = ReadingDirection.Ltr,
            ),
        )
    }

    @Test
    fun unknownSeriesDirectionFallsBackToTheDefault() {
        assertEquals(
            ReadingDirection.Vertical,
            resolveReadingDirection(
                seriesDirection = null,
                forceDefault = false,
                defaultDirection = ReadingDirection.Vertical,
            ),
        )
    }

    @Test
    fun seriesTokensMapToDirections() {
        assertEquals(ReadingDirection.Ltr, readingDirectionFromSeriesToken("LEFT_TO_RIGHT"))
        assertEquals(ReadingDirection.Rtl, readingDirectionFromSeriesToken("RIGHT_TO_LEFT"))
        assertEquals(ReadingDirection.Vertical, readingDirectionFromSeriesToken("VERTICAL"))
        assertEquals(ReadingDirection.Webtoon, readingDirectionFromSeriesToken("WEBTOON"))
    }

    @Test
    fun seriesTokensAreTrimmedAndCaseInsensitive() {
        assertEquals(ReadingDirection.Rtl, readingDirectionFromSeriesToken(" right_to_left "))
        assertEquals(ReadingDirection.Ltr, readingDirectionFromSeriesToken("Left_To_Right"))
    }

    @Test
    fun blankOrUnknownSeriesTokensResolveToNull() {
        assertNull(readingDirectionFromSeriesToken(null))
        assertNull(readingDirectionFromSeriesToken(""))
        assertNull(readingDirectionFromSeriesToken("SOMETHING_ELSE"))
    }

    @Test
    fun progressionTokensMapToDirections() {
        assertEquals(ReadingDirection.Ltr, readingDirectionFromProgression("ltr"))
        assertEquals(ReadingDirection.Rtl, readingDirectionFromProgression("rtl"))
        assertEquals(ReadingDirection.Vertical, readingDirectionFromProgression("ttb"))
        assertEquals(ReadingDirection.Vertical, readingDirectionFromProgression("btt"))
        assertNull(readingDirectionFromProgression("auto"))
        assertNull(readingDirectionFromProgression(null))
    }
}
