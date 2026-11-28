package com.kmworks.kmreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Return stack of explicit jumps: one entry per distinct origin, newest last, capped at
 * [EpubPositionHistory.MAX_ENTRIES].
 */
class EpubPositionHistoryTest {

    private fun position(chapter: Int, fraction: Float = 0f) =
        EpubReaderPosition(chapterIndex = chapter, chapterFraction = fraction, chapterTitle = "c$chapter")

    @Test
    fun `push appends and pop returns the newest entry`() {
        var stack = emptyList<EpubReaderPosition>()
        stack = EpubPositionHistory.push(stack, position(1, 0.2f))
        stack = EpubPositionHistory.push(stack, position(4, 0.7f))
        assertEquals(2, stack.size)

        val (afterPop, target) = EpubPositionHistory.pop(stack)
        assertEquals(listOf(position(1, 0.2f)), afterPop)
        assertEquals(position(4, 0.7f), target)
    }

    @Test
    fun `pushing the same place twice keeps one entry`() {
        val stack = EpubPositionHistory.push(
            EpubPositionHistory.push(emptyList(), position(3, 0.5f)),
            // The scroll tick under the tap moved the fraction a hair; it is still the same place
            position(3, 0.502f),
        )
        assertEquals(1, stack.size)
    }

    @Test
    fun `a different page of the same chapter is a new entry`() {
        val stack = EpubPositionHistory.push(
            EpubPositionHistory.push(emptyList(), position(3, 0.5f)),
            position(3, 0.9f),
        )
        assertEquals(2, stack.size)
    }

    @Test
    fun `the stack is capped and drops the oldest entries`() {
        var stack = emptyList<EpubReaderPosition>()
        repeat(EpubPositionHistory.MAX_ENTRIES + 5) { index ->
            stack = EpubPositionHistory.push(stack, position(index, 0f))
        }
        assertEquals(EpubPositionHistory.MAX_ENTRIES, stack.size)
        // The newest is still the last one pushed; the first five fell off
        assertEquals(EpubPositionHistory.MAX_ENTRIES + 4, stack.last().chapterIndex)
        assertEquals(5, stack.first().chapterIndex)
    }

    @Test
    fun `popping an empty stack changes nothing`() {
        val (stack, target) = EpubPositionHistory.pop(emptyList())
        assertTrue(stack.isEmpty())
        assertNull(target)
    }
}
