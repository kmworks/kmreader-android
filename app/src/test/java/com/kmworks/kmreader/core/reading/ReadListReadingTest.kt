package com.kmworks.kmreader.core.reading

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-list continuation state: the client-settings wire format and the pull-side merge. Mirrors the
 * original ReadListReadingService tests: both apps must read each other's keys, and a newer change must
 * win regardless of which side it came from.
 */
class ReadListReadingTest {

    @Test
    fun `key lowercases the id and rejects ids that cannot be a key segment`() {
        assertEquals("kmreader.readlist.abc123", ReadListReadingWire.key("ABC123"))
        assertNull(ReadListReadingWire.key(""))
        assertNull(ReadListReadingWire.key("not/a/segment"))
        assertNull(ReadListReadingWire.key("with space"))
    }

    @Test
    fun `encode round trips through decode`() {
        val entry = ReadListReadingEntry(
            readListId = "abc123",
            bookId = "book1",
            readAtMs = 1_700_000_000_000L,
            dirty = true,
        )
        val key = ReadListReadingWire.key(entry.readListId)
        assertNotNull(key)
        val decoded = ReadListReadingWire.decode(key!!, ReadListReadingWire.encode(entry))
        assertNotNull(decoded)
        assertEquals(entry.readListId, decoded!!.readListId)
        assertEquals(entry.bookId, decoded.bookId)
        assertEquals(0L, abs(entry.readAtMs - decoded.readAtMs))
        // A pulled change is clean: only local edits are uploaded
        assertEquals(false, decoded.dirty)
        assertEquals(false, decoded.stopped)
    }

    @Test
    fun `decode keeps sub-second precision within a millisecond`() {
        val entry = ReadListReadingEntry(readListId = "abc123", bookId = "b", readAtMs = 1_700_000_000_123L)
        val key = ReadListReadingWire.key(entry.readListId)!!
        val decoded = ReadListReadingWire.decode(key, ReadListReadingWire.encode(entry))!!
        assertTrue(abs(entry.readAtMs - decoded.readAtMs) <= 1L)
    }

    @Test
    fun `decode rejects foreign, mismatched and malformed keys`() {
        val encoded = ReadListReadingWire.encode(
            ReadListReadingEntry(readListId = "abc123", bookId = "book1", readAtMs = 1L),
        )
        assertNull(ReadListReadingWire.decode("kmreader.sse.enabled", encoded))
        assertNull(ReadListReadingWire.decode("kmreader.readlist.other", encoded))
        assertNull(ReadListReadingWire.decode("kmreader.readlist.abc123", "not json"))
        assertNull(
            ReadListReadingWire.decode(
                "kmreader.readlist.abc123",
                """{"readListId":"abc123","bookId":"","readAt":1}""",
            ),
        )
    }

    @Test
    fun `merge lets the newer change win in both directions`() {
        val local = listOf(
            ReadListReadingEntry(readListId = "a", bookId = "local-newer", readAtMs = 20, dirty = true),
            ReadListReadingEntry(readListId = "b", bookId = "local-older", readAtMs = 10),
        )
        val remote = listOf(
            ReadListReadingEntry(readListId = "a", bookId = "remote-older", readAtMs = 15),
            ReadListReadingEntry(readListId = "b", bookId = "remote-newer", readAtMs = 30),
            ReadListReadingEntry(readListId = "c", bookId = "remote-only", readAtMs = 5),
        )

        val merged = ReadListReadingMerge.applyRemote(local, remote).associateBy { it.readListId }

        assertEquals("local-newer", merged.getValue("a").bookId)
        // The local newer change stays dirty so the next upload pushes it
        assertTrue(merged.getValue("a").dirty)
        assertEquals("remote-newer", merged.getValue("b").bookId)
        assertEquals(false, merged.getValue("b").dirty)
        assertEquals("remote-only", merged.getValue("c").bookId)
    }

    @Test
    fun `merge sorts by most recent first and keeps a local stop that is newer`() {
        val local = listOf(
            ReadListReadingEntry(readListId = "stopped", readAtMs = 40, stopped = true, dirty = true),
        )
        val remote = listOf(
            ReadListReadingEntry(readListId = "stopped", bookId = "old-read", readAtMs = 30),
            ReadListReadingEntry(readListId = "recent", bookId = "recent-read", readAtMs = 50),
        )

        val merged = ReadListReadingMerge.applyRemote(local, remote)

        assertEquals(listOf("recent", "stopped"), merged.map { it.readListId })
        assertTrue(merged.first { it.readListId == "stopped" }.stopped)
    }
}
