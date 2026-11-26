package com.kmworks.kmreader.core.epub

/**
 * Extra page insets for the EPUB page CSS, in CSS px.
 *
 * The page CSS already adds the base gutter itself (`24 + insetV` vertically, `marginH`
 * horizontally), so these values are only the part that has to be added on top of it: the
 * display-cutout band and the label overlay band. Keeping the arithmetic here (instead of in the
 * screen) makes the "overlay vs cutout" policy unit-testable — it is the only place where the
 * two reservations interact.
 */
internal data class EpubPageInsets(
    val topVPx: Int = 0,
    val bottomVPx: Int = 0,
    val leftVPx: Int = 0,
    val rightVPx: Int = 0,
)

/**
 * Resolves the extra top/bottom/left/right page insets.
 *
 * Policy:
 *  - with a header/footer label, both edges reserve the larger system bar (never less than one
 *    base gutter, which is what the pre-cutout code did with `max(24, band)`) plus the cutout
 *    band unless content may draw under it. The edges intentionally share the larger bar: the
 *    footer label sits ON TOP of the navigation bar, so reserving only `navigationBarBottom`
 *    put its last line underneath the label;
 *  - without a label the page keeps its base gutter and, unless content may draw under the
 *    cutout, the cutout band as well — a notch phone whose overlay is switched off must not
 *    print the first line behind the clock;
 *  - horizontal insets only ever come from the cutout (in landscape the notch sits on a short
 *    edge), and are dropped entirely when content may draw under it.
 *
 * The returned value is an ADDITION: the CSS has already applied the base gutter, so with no
 * cutout and no overlay every field is 0 and the page layout is byte-for-byte the old one.
 *
 * @param baseGutterVPx the gutter the CSS adds on its own (24 in the current page CSS); it
 *   doubles as the minimum overlay reservation, exactly like the old `max(24, band)`.
 * @param overlayHasContent whether any header/footer slot shows content.
 * @param drawUnderCutout whether content may run into the cutout band.
 */
internal fun computeEpubPageInsets(
    cutoutTopVPx: Int = 0,
    cutoutBottomVPx: Int = 0,
    cutoutLeftVPx: Int = 0,
    cutoutRightVPx: Int = 0,
    statusBarTopVPx: Int = 0,
    navigationBarBottomVPx: Int = 0,
    overlayHasContent: Boolean,
    drawUnderCutout: Boolean,
    baseGutterVPx: Int = 24,
): EpubPageInsets {
    val base = baseGutterVPx.coerceAtLeast(0)
    // The status bar already clears the cutout on every device that reports one, so the max()
    // keeps the two bands from being counted twice.
    val overlayBandVPx = maxOf(statusBarTopVPx.coerceAtLeast(0), navigationBarBottomVPx.coerceAtLeast(0))
    fun verticalExtra(cutoutVPx: Int): Int {
        val cutout = if (drawUnderCutout) 0 else cutoutVPx.coerceAtLeast(0)
        // Additive, not "larger of": the page needs its gutter AND clearance. The old rule
        // passed max(24, band) to a CSS that added its own 24, i.e. 24 + max(24, band) — the
        // same numbers come out here, so the default layout is unchanged.
        return if (overlayHasContent) maxOf(base, overlayBandVPx, cutout) else cutout
    }
    return EpubPageInsets(
        topVPx = verticalExtra(cutoutTopVPx),
        bottomVPx = verticalExtra(cutoutBottomVPx),
        leftVPx = if (drawUnderCutout) 0 else cutoutLeftVPx.coerceAtLeast(0),
        rightVPx = if (drawUnderCutout) 0 else cutoutRightVPx.coerceAtLeast(0),
    )
}
