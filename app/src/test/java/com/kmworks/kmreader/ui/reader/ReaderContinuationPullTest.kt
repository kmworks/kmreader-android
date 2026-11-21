package com.kmworks.kmreader.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContinuationPullTest {
    @Test
    fun `below the threshold nothing happens`() {
        val pull = ReaderContinuationPull(thresholdPx = 72f)

        assertFalse(pull.add(20f))
        assertFalse(pull.add(50f))
    }

    @Test
    fun `the delta that crosses the threshold turns the page once`() {
        val pull = ReaderContinuationPull(thresholdPx = 72f)

        assertFalse(pull.add(40f))
        assertTrue(pull.add(32f))
        // The book is being swapped, so a still-running drag must not turn twice
        assertFalse(pull.add(500f))
    }

    @Test
    fun `a reset drag starts the pull over`() {
        val pull = ReaderContinuationPull(thresholdPx = 72f)

        assertFalse(pull.add(60f))
        pull.reset()
        assertFalse(pull.add(60f))
        assertTrue(pull.add(60f))
    }

    @Test
    fun `a drag that settles keeps the allowance for the next gesture`() {
        val pull = ReaderContinuationPull(thresholdPx = 72f)

        assertTrue(pull.add(72f))
        // reset() drops the accumulated distance but not the one turn this pull already spent
        pull.reset()
        assertFalse(pull.add(72f))
    }
}
