package com.kmworks.kmreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "record progress after N pages" gate shared by the three readers. Mirror of the original iOS
 * ReaderProgressRecordingGateTests: the whole point is that opening a book without moving records nothing.
 */
class ReaderProgressRecordingGateTest {

    @Test
    fun `threshold zero disables the gate`() {
        assertEquals(0, ReaderProgressRecordingGate.requiredDistance(0, 100, wasInProgress = false))
        assertEquals(0, ReaderProgressRecordingGate.requiredDistance(0, 100, wasInProgress = true))
    }

    @Test
    fun `unread book needs the configured threshold`() {
        assertEquals(3, ReaderProgressRecordingGate.requiredDistance(3, 100, wasInProgress = false))
        assertEquals(5, ReaderProgressRecordingGate.requiredDistance(5, 100, wasInProgress = false))
        assertEquals(10, ReaderProgressRecordingGate.requiredDistance(10, 100, wasInProgress = false))
    }

    @Test
    fun `short book clamps the threshold to its page count`() {
        assertEquals(4, ReaderProgressRecordingGate.requiredDistance(5, 5, wasInProgress = false))
        assertEquals(1, ReaderProgressRecordingGate.requiredDistance(3, 2, wasInProgress = false))
        assertEquals(0, ReaderProgressRecordingGate.requiredDistance(3, 1, wasInProgress = false))
    }

    @Test
    fun `unknown page count leaves the threshold unclamped`() {
        assertEquals(3, ReaderProgressRecordingGate.requiredDistance(3, 0, wasInProgress = false))
        assertEquals(1, ReaderProgressRecordingGate.requiredDistance(3, 0, wasInProgress = true))
    }

    @Test
    fun `book already in progress needs one page`() {
        assertEquals(1, ReaderProgressRecordingGate.requiredDistance(3, 100, wasInProgress = true))
        assertEquals(1, ReaderProgressRecordingGate.requiredDistance(10, 100, wasInProgress = true))
    }
}
