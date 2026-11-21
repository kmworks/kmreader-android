package com.kmworks.kmreader.core.download

import com.kmworks.kmreader.core.data.StableIds
import org.junit.Assert.assertEquals
import org.junit.Test

/** Card badge aggregation: a book is full or nothing, a series is full when every book is downloaded. */
class DownloadedIndexTest {

    private val bookA = StableIds.book("book-a")
    private val bookB = StableIds.book("book-b")

    @Test
    fun emptyIndexMarksNothing() {
        val index = DownloadedIndex()
        assertEquals(DownloadMark.None, index.bookMark(bookA))
        assertEquals(DownloadMark.None, index.seriesMark("series-1"))
        assertEquals(DownloadMark.None, index.seriesMark(null))
    }

    @Test
    fun downloadedBookIsFull() {
        val index = DownloadedIndex(books = setOf(bookA))
        assertEquals(DownloadMark.Full, index.bookMark(bookA))
        assertEquals(DownloadMark.None, index.bookMark(bookB))
        // A book downloaded before the series was recorded has no aggregate yet.
        assertEquals(DownloadMark.None, index.seriesMark("series-1"))
    }

    @Test
    fun seriesIsPartialUntilEveryBookIsDownloaded() {
        val partial = DownloadedIndex(
            series = mapOf("series-1" to SeriesDownloadAggregate(downloaded = 1, total = 3)),
        )
        assertEquals(DownloadMark.Partial, partial.seriesMark("series-1"))

        val full = DownloadedIndex(
            series = mapOf("series-1" to SeriesDownloadAggregate(downloaded = 3, total = 3)),
        )
        assertEquals(DownloadMark.Full, full.seriesMark("series-1"))
    }

    @Test
    fun aggregateWithUnknownTotalStaysPartial() {
        // A single-book download records the series id but no count; it must not claim the series is complete.
        val aggregate = SeriesDownloadAggregate(downloaded = 1, total = 0)
        assertEquals(DownloadMark.Partial, aggregate.mark)
        assertEquals(DownloadMark.None, SeriesDownloadAggregate().mark)
    }

    @Test
    fun oversizedAggregateStillCountsAsFull() {
        assertEquals(DownloadMark.Full, SeriesDownloadAggregate(downloaded = 5, total = 3).mark)
    }

    @Test
    fun bookMarkIgnoresUnknownStableIds() {
        val index = DownloadedIndex(books = setOf(bookA))
        // StableIds maps ids deterministically, so a different dtoId is a different stableId.
        assertEquals(DownloadMark.None, index.bookMark(StableIds.book("book-c")))
    }

    @Test
    fun queuedTaskShowsItsBadgeUntilTheDownloadCompletes() {
        assertEquals(
            DownloadMark.Downloading,
            DownloadedIndex(tasks = mapOf(bookA to DownloadMark.Downloading)).bookMark(bookA),
        )
        assertEquals(
            DownloadMark.Pending,
            DownloadedIndex(tasks = mapOf(bookA to DownloadMark.Pending)).bookMark(bookA),
        )
        assertEquals(
            DownloadMark.Failed,
            DownloadedIndex(tasks = mapOf(bookA to DownloadMark.Failed)).bookMark(bookA),
        )
        assertEquals(DownloadMark.None, DownloadedIndex(tasks = mapOf(bookA to DownloadMark.Downloading)).bookMark(bookB))
    }

    @Test
    fun completedBookWinsOverAnInFlightRestart() {
        // REPLACE re-download of a finished book: the files are complete, so the card keeps its check.
        val index = DownloadedIndex(books = setOf(bookA), tasks = mapOf(bookA to DownloadMark.Failed))
        assertEquals(DownloadMark.Full, index.bookMark(bookA))
    }

    @Test
    fun seriesWithoutCompletedBooksReflectsQueueState() {
        val downloading = DownloadedIndex(
            series = mapOf("series-1" to SeriesDownloadAggregate(total = 3, downloading = 1)),
        )
        assertEquals(DownloadMark.Downloading, downloading.seriesMark("series-1"))

        val pending = DownloadedIndex(
            series = mapOf("series-1" to SeriesDownloadAggregate(total = 3, pending = 1)),
        )
        assertEquals(DownloadMark.Pending, pending.seriesMark("series-1"))

        val failed = DownloadedIndex(
            series = mapOf("series-1" to SeriesDownloadAggregate(total = 3, failed = 1)),
        )
        assertEquals(DownloadMark.Failed, failed.seriesMark("series-1"))

        // A partly downloaded series keeps its Partial mark even while the rest is still queued.
        val partial = DownloadedIndex(
            series = mapOf("series-1" to SeriesDownloadAggregate(downloaded = 1, total = 3, downloading = 2)),
        )
        assertEquals(DownloadMark.Partial, partial.seriesMark("series-1"))
    }

    @Test
    fun queueStatusMapsToBadgeMarks() {
        assertEquals(DownloadMark.Downloading, DownloadTaskUi.Status.Downloading.toDownloadMark())
        assertEquals(DownloadMark.Pending, DownloadTaskUi.Status.Paused.toDownloadMark())
        assertEquals(DownloadMark.Failed, DownloadTaskUi.Status.Failed.toDownloadMark())
    }
}
