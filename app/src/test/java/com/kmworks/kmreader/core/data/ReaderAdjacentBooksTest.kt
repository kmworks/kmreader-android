package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.data.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAdjacentBooksTest {

    private fun book(id: Int) = Book(
        id = id,
        number = "$id",
        title = "Book $id",
        seriesTitle = "Series",
        hue = 0f,
        pageCount = 10,
        progress = null,
    )

    @Test
    fun nullPairMeansTheLookupFailed() {
        val adjacent = (null as Pair<Int?, Int?>?).toReaderAdjacentBooks { book(it) }

        assertTrue(adjacent.failed)
        assertFalse(adjacent.loaded)
        assertNull(adjacent.previous)
        assertNull(adjacent.next)
    }

    @Test
    fun emptyPairMeansTheSeriesHasNoNeighbours() {
        val adjacent = (null as Int? to null as Int?).toReaderAdjacentBooks { book(it) }

        assertTrue(adjacent.loaded)
        assertFalse(adjacent.failed)
        assertNull(adjacent.previous)
        assertNull(adjacent.next)
    }

    @Test
    fun pairKeepsPreviousAndNextInOrder() {
        val adjacent = (1 as Int? to 2 as Int?).toReaderAdjacentBooks { book(it) }

        assertTrue(adjacent.loaded)
        assertEquals(1, adjacent.previous?.id)
        assertEquals(2, adjacent.next?.id)
    }

    @Test
    fun missingSideLeavesOnlyTheOther() {
        val adjacent = (null as Int? to 7 as Int?).toReaderAdjacentBooks { book(it) }

        assertTrue(adjacent.loaded)
        assertNull(adjacent.previous)
        assertEquals(7, adjacent.next?.id)
    }
}
