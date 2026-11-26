package com.kmworks.kmreader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The page inset policy is the only place where the label overlay reservation and the display
 * cutout band interact; getting it wrong is either a first line behind the notch or a wasted
 * quarter of the page, so the arithmetic is pinned here instead of on a device.
 *
 * Every value asserted is an ADDITION: the page CSS adds its own 24px gutter, so the page's real
 * top padding is 24 + [EpubPageInsets.topVPx]. The pre-cutout rule was `24 + max(24, band)`; the
 * first cases pin that those numbers did not move.
 */
class EpubPageInsetsTest {

    @Test
    fun `notch device without overlay keeps text out of the cutout`() {
        val insets = computeEpubPageInsets(
            cutoutTopVPx = 44,
            statusBarTopVPx = 44,
            overlayHasContent = false,
            drawUnderCutout = false,
        )
        // 24 + 44 = 68 CSS px from the page top: the 24 gutter plus the cutout band.
        assertEquals(44, insets.topVPx)
        assertEquals(0, insets.bottomVPx)
        assertEquals(0, insets.leftVPx)
        assertEquals(0, insets.rightVPx)
    }

    @Test
    fun `overlay reserves the larger system bar on both edges`() {
        val insets = computeEpubPageInsets(
            cutoutTopVPx = 44,
            statusBarTopVPx = 44,
            navigationBarBottomVPx = 48,
            overlayHasContent = true,
            drawUnderCutout = false,
        )
        // 24 + 48 = 72 CSS px on both edges, exactly what the pre-cutout code produced (it passed
        // max(24, band) = 48 to a CSS that added its own 24). Both edges share the larger bar
        // because the footer label sits on top of the navigation bar.
        assertEquals(48, insets.topVPx)
        assertEquals(48, insets.bottomVPx)
    }

    @Test
    fun `overlay on a gesture-navigation notch phone keeps the taller top band`() {
        val insets = computeEpubPageInsets(
            cutoutTopVPx = 44,
            statusBarTopVPx = 44,
            navigationBarBottomVPx = 24,
            overlayHasContent = true,
            drawUnderCutout = false,
        )
        assertEquals(44, insets.topVPx)
        assertEquals(44, insets.bottomVPx)
    }

    @Test
    fun `a band shorter than the gutter still reserves one gutter`() {
        val insets = computeEpubPageInsets(
            statusBarTopVPx = 12,
            navigationBarBottomVPx = 0,
            overlayHasContent = true,
            drawUnderCutout = false,
        )
        // The old max(24, band) floor: 24 + 24 = 48 CSS px, same as before the cutout work.
        assertEquals(24, insets.topVPx)
        assertEquals(24, insets.bottomVPx)
    }

    @Test
    fun `drawing under the cutout drops the cutout reservation`() {
        val insets = computeEpubPageInsets(
            cutoutTopVPx = 44,
            cutoutBottomVPx = 16,
            cutoutLeftVPx = 40,
            cutoutRightVPx = 0,
            statusBarTopVPx = 44,
            navigationBarBottomVPx = 24,
            overlayHasContent = false,
            drawUnderCutout = true,
        )
        assertEquals(EpubPageInsets(), insets)
    }

    @Test
    fun `drawing under the cutout keeps the overlay band`() {
        val insets = computeEpubPageInsets(
            cutoutTopVPx = 44,
            cutoutLeftVPx = 40,
            statusBarTopVPx = 44,
            overlayHasContent = true,
            drawUnderCutout = true,
        )
        // The switch only governs the cutout: with the overlay on, text still clears the bars.
        assertEquals(44, insets.topVPx)
        assertEquals(0, insets.leftVPx)
    }

    @Test
    fun `landscape cutout pads the side the notch sits on`() {
        val insets = computeEpubPageInsets(
            cutoutLeftVPx = 44,
            cutoutRightVPx = 0,
            overlayHasContent = false,
            drawUnderCutout = false,
        )
        // Horizontal insets are pure additions to the margin gutter, not gutter-minus-something.
        assertEquals(44, insets.leftVPx)
        assertEquals(0, insets.rightVPx)
        assertEquals(0, insets.topVPx)
    }

    @Test
    fun `no cutout and no overlay changes nothing`() {
        val insets = computeEpubPageInsets(
            overlayHasContent = false,
            drawUnderCutout = false,
        )
        assertEquals(EpubPageInsets(), insets)
    }

    @Test
    fun `base gutter doubles as the overlay floor`() {
        val noOverlay = computeEpubPageInsets(
            cutoutTopVPx = 40,
            overlayHasContent = false,
            drawUnderCutout = false,
            baseGutterVPx = 16,
        )
        // Without an overlay the cutout band is added as-is; the CSS gutter is not ours to floor.
        assertEquals(40, noOverlay.topVPx)
        val withOverlay = computeEpubPageInsets(
            statusBarTopVPx = 40,
            overlayHasContent = true,
            drawUnderCutout = false,
            baseGutterVPx = 16,
        )
        assertEquals(40, withOverlay.topVPx)
        val thinBand = computeEpubPageInsets(
            statusBarTopVPx = 8,
            overlayHasContent = true,
            drawUnderCutout = false,
            baseGutterVPx = 16,
        )
        assertEquals(16, thinBand.topVPx)
    }
}
