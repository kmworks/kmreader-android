package com.kmworks.kmreader.ui.reader

import kotlin.math.abs

/**
 * One place the reader was, in the terms a jump restores: a spine index plus the fraction inside
 * that chapter (the same pair a bookmark and a link jump use).
 */
data class EpubReaderPosition(
    val chapterIndex: Int,
    val chapterFraction: Float,
    /** Chapter title at the time of the jump, for the return affordance's label. */
    val chapterTitle: String? = null,
)

/**
 * Return stack for EXPLICIT jumps (mirrors the iOS `positionBackStack`): the progress slider, the
 * table of contents, a bookmark, an in-book link — every navigation where the reader asked to be
 * somewhere else. Ordinary page turns and chapter seams never enter it: a reader who paged forward
 * ten pages and then tapped a link wants to come back to the link's origin, not to walk their own
 * page turns backwards one at a time.
 *
 * Kept as pure functions over an immutable list so the stack's rules are testable without a
 * ViewModel, and so the UI can read it as ordinary state.
 */
object EpubPositionHistory {

    /**
     * Depth cap. A reader can bounce around a book for a whole session; the stack is a convenience,
     * not a history log, and the deepest entries are the least likely to be wanted back.
     */
    const val MAX_ENTRIES = 50

    /**
     * How close two positions have to be to count as the same place when pushing. Two jumps from the
     * same page (toc entry tapped twice, link followed then a bookmark at the same spot) must not
     * stack an entry the return button would take the reader to pointlessly.
     */
    const val SAME_POSITION_EPSILON = 0.005f

    fun push(
        stack: List<EpubReaderPosition>,
        position: EpubReaderPosition,
    ): List<EpubReaderPosition> {
        val last = stack.lastOrNull()
        if (last != null &&
            last.chapterIndex == position.chapterIndex &&
            abs(last.chapterFraction - position.chapterFraction) <= SAME_POSITION_EPSILON
        ) {
            return stack
        }
        val next = stack + position
        return if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
    }

    /** Drops the newest entry and hands it back (null on an empty stack). */
    fun pop(stack: List<EpubReaderPosition>): Pair<List<EpubReaderPosition>, EpubReaderPosition?> {
        val target = stack.lastOrNull() ?: return stack to null
        return stack.dropLast(1) to target
    }
}
