package com.kmworks.kmreader.core.pdf

/**
 * Page-order mapping between the positions androidx.pdf lays out and the physical PDF pages
 * everything user-facing speaks (page capsule, TOC, search results, jump sheet, progress and
 * the Komga server all use physical page numbers — page 1 stays page 1, matching iOS PDFKit,
 * which never renumbers pages under `displaysRTL` / `displaysAsBook`).
 *
 * Two remappings, mirroring iOS PDFView:
 * - RTL (`displaysRTL`): each row of [pagesPerRow] positions is mirrored, so spreads read
 *   right-to-left ([p2 left][p1 right]). Document order and page numbering are NOT reversed.
 * - Solo cover (`displaysAsBook`): one virtual blank position is inserted at the front of a
 *   two-page layout, so TwoPageLayoutStrategy's fixed pairing (0+1, 2+3…) pairs the blank
 *   with the cover and shifts the rest into book spreads (1+2, 3+4…). The blank renders as
 *   the reader background; it is not a page and has no page number.
 */
data class PdfPageOrder(
    /** Physical pages in the document. */
    val physicalCount: Int,
    /** Pages laid out side by side (1 or 2; PdfView allows nothing else). */
    val pagesPerRow: Int,
    /** Right-to-left reading direction. */
    val rtl: Boolean,
    /** Solo-cover setting (only takes effect in two-page layouts). */
    val coverOffset: Boolean,
) {
    /** Mirroring is a no-op on single-page rows. */
    private val mirrorRows = rtl && pagesPerRow > 1

    /** The virtual cover blank only exists in two-page layouts. */
    val virtualCover = coverOffset && pagesPerRow > 1

    /** Positions the view lays out: physical pages plus the optional virtual cover blank. */
    val displayCount = physicalCount + if (virtualCover) 1 else 0

    /** When true the decorated document is the delegate itself — no wrapping needed. */
    val isIdentity = !mirrorRows && !virtualCover

    /** Display position (0-based) → physical page index (0-based); -1 for the virtual blank. */
    fun positionToPhysical(position: Int): Int {
        val row = position / pagesPerRow
        val col = position % pagesPerRow
        val mappedCol = if (mirrorRows) pagesPerRow - 1 - col else col
        return row * pagesPerRow + mappedCol - if (virtualCover) 1 else 0
    }

    /** Physical page index (0-based) → display position (0-based). */
    fun physicalToPosition(physical: Int): Int {
        val raw = physical + if (virtualCover) 1 else 0
        val row = raw / pagesPerRow
        val col = raw % pagesPerRow
        return row * pagesPerRow + if (mirrorRows) pagesPerRow - 1 - col else col
    }
}
