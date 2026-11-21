package com.kmworks.kmreader.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the dashboard's definition of a read list "in progress".
 *
 * The old rule was "the first book that is not finished", which every never-opened list satisfies, so a
 * freshly created read list was advertised in the dashboard as being read. The rules below mirror the iOS
 * continuation (DatabaseOperator+ReadListReading.resolveReadListContinuation): a list has a continuation
 * only once the user started one of its books.
 */
class ReadListContinuationTest {

    @Test
    fun `an empty list has nothing to continue`() {
        assertNull(resolveReadListContinuation(progresses = emptyList(), readDatesMs = emptyList()))
    }

    @Test
    fun `a list nobody started is not in progress`() {
        assertNull(resolveReadListContinuation(listOf(null, null, 0f), listOf(null, null, null)))
    }

    @Test
    fun `a book in progress is the continuation`() {
        val pick = resolveReadListContinuation(listOf(null, 0.4f, null), listOf(null, 1_000L, null))
        assertEquals(1, pick?.nextBookIndex)
        assertEquals(0, pick?.booksRead)
        assertEquals(1_000L, pick?.lastReadAtMs)
    }

    @Test
    fun `after a finished book the list continues with the next unread one`() {
        val pick = resolveReadListContinuation(listOf(1f, null, null), listOf(2_000L, null, null))
        assertEquals(1, pick?.nextBookIndex)
        assertEquals(1, pick?.booksRead)
    }

    @Test
    fun `a list read out of order resumes at its earliest unread book`() {
        // Every book after the finished one is finished too, so the continuation falls back to book 0.
        val pick = resolveReadListContinuation(listOf(null, 1f), listOf(0L, 3_000L))
        assertEquals(0, pick?.nextBookIndex)
        assertEquals(1, pick?.booksRead)
    }

    @Test
    fun `a fully read list has nothing to continue`() {
        assertNull(resolveReadListContinuation(listOf(1f, 1f), listOf(1_000L, 2_000L)))
    }

    @Test
    fun `lastReadAtMs is the most recent read date of the list`() {
        val pick = resolveReadListContinuation(
            progresses = listOf(1f, 0.2f),
            readDatesMs = listOf(1_000L, 5_000L),
        )
        assertEquals(5_000L, pick?.lastReadAtMs)
    }
}
