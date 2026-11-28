package com.kmworks.kmreader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Floating surfaces sit ON the page, so the separation has to come from the surface itself: a fill
 * step on a dark page, a hairline everywhere. These pin both, per reader theme.
 */
class ReaderChromeSurfaceTest {

    private val darkPage = Color(0xFF000000)
    private val darkInk = Color.White
    private val lightPage = Color(0xFFFFFFFF)
    private val lightInk = Color.Black

    @Test
    fun `light page keeps the page colour as the fill`() {
        val surface = readerChromeSurface(lightPage, lightInk)
        // The chrome on a light page is the page colour with a hairline, exactly as before the dark
        // theme fix: a grey disc on white paper is not what this reader wants.
        assertEquals(lightPage.red, surface.background.red, 0.001f)
        assertEquals(lightPage.green, surface.background.green, 0.001f)
        assertEquals(lightPage.blue, surface.background.blue, 0.001f)
        // (alpha lands on an 8-bit channel: 0.10 -> 26/255, hence the tolerance)
        assertEquals(0.10f, surface.border.alpha, 0.005f)
    }

    @Test
    fun `dark page steps the fill toward the foreground`() {
        val surface = readerChromeSurface(darkPage, darkInk)
        // 10% of the channel range on black is #1A1A1A; an Oklab interpolation would land near
        // #090909 here, which is exactly the invisible step this replaced. (The tolerance covers
        // the pack to 8-bit channels: 0.10 -> 26/255.)
        assertEquals(0.10f, surface.background.red, 0.005f)
        assertEquals(surface.background.red, surface.background.green, 0.001f)
        assertEquals(surface.background.red, surface.background.blue, 0.001f)
        assertTrue(surface.background.luminance() > darkPage.luminance())
    }

    @Test
    fun `a dark sepia page keeps its hue`() {
        val page = Color(0xFF382E25)
        val surface = readerChromeSurface(page, Color(0xFFE3D5C1))
        assertTrue(surface.background.luminance() > page.luminance())
        assertTrue(surface.background.red > surface.background.blue)
    }

    @Test
    fun `the return pill steps further than the chrome on a dark page`() {
        val chrome = readerChromeSurface(darkPage, darkInk)
        val pill = readerChromeSurface(darkPage, darkInk, prominent = true)
        assertTrue(pill.background.luminance() > chrome.background.luminance())
        // Still a dark surface: the pill is a control on the page, not a dialog over it.
        assertTrue(pill.background.luminance() < 0.5f)
    }

    @Test
    fun `the return pill earns a heavier hairline on a light page`() {
        val chrome = readerChromeSurface(lightPage, lightInk)
        val pill = readerChromeSurface(lightPage, lightInk, prominent = true)
        // No fill difference there — the edge is all the pill has, so it is the stronger one.
        assertEquals(lightPage.red, pill.background.red, 0.001f)
        assertTrue(pill.border.alpha > chrome.border.alpha)
    }

    @Test
    fun `the dark hairline is the stronger one`() {
        val dark = readerChromeSurface(darkPage, darkInk)
        val light = readerChromeSurface(lightPage, lightInk)
        assertTrue(dark.border.alpha > light.border.alpha)
    }
}
