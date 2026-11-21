package com.kmworks.kmreader.ui.reader

/**
 * Accumulates the drag the reader could not scroll at the end of a book, until it is clearly a
 * continuation gesture rather than the bounce of a drag that already reached the last page.
 *
 * The original app keeps every segment's cells in one flat item list, so its pager turns past the last
 * page without noticing a seam; here the leftover of that drag is the only signal, and it has to earn
 * the turn (it fires once, then the next book takes over).
 */
internal class ReaderContinuationPull(private val thresholdPx: Float) {
    private var pulled = 0f
    private var fired = false

    /** Adds [distancePx] of leftover drag; true exactly once, on the delta that crosses the threshold. */
    fun add(distancePx: Float): Boolean {
        if (fired) return false
        pulled += distancePx
        if (pulled < thresholdPx) return false
        fired = true
        pulled = 0f
        return true
    }

    /** The drag was not a continuation: the reader scrolled, or the gesture settled back. */
    fun reset() {
        pulled = 0f
    }
}
