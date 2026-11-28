package com.kmworks.kmreader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Floating surfaces sit ON the page, so the only thing that can separate them from it is a step in
 * the page colour — these pin the direction and the size of that step for every reader theme.
 */
class ReaderChromeSurfaceTest {

    private val darkPage = Color(0xFF000000)
    private val darkInk = Color.White
    private val lightPage = Color(0xFFFFFFFF)
    private val lightInk = Color.Black

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
    fun `light page steps the fill away from white without turning it dark`() {
        val surface = readerChromeSurface(lightPage, lightInk)
        assertTrue(surface.background.luminance() < lightPage.luminance())
        assertTrue("a white page's chrome stays a light surface", surface.background.luminance() > 0.5f)
    }

    @Test
    fun `a sepia page keeps its hue`() {
        val surface = readerChromeSurface(Color(0xFFF4ECD8), Color.Black)
        assertTrue(surface.background.red > surface.background.green)
        assertTrue(surface.background.green > surface.background.blue)
    }

    @Test
    fun `a dark sepia page keeps its hue too`() {
        val surface = readerChromeSurface(Color(0xFF382E25), Color(0xFFE3D5C1))
        assertTrue(surface.background.red > surface.background.blue)
        assertTrue(surface.background.luminance() > Color(0xFF382E25).luminance())
    }

    @Test
    fun `the return pill steps further than the chrome`() {
        val chrome = readerChromeSurface(darkPage, darkInk)
        val pill = readerChromeSurface(darkPage, darkInk, lift = RETURN_LIFT)
        assertTrue(pill.background.luminance() > chrome.background.luminance())
        assertTrue(pill.background.luminance() < darkInk.luminance())
    }

    @Test
    fun `lift is clamped so no surface turns into a dialog`() {
        val surface = readerChromeSurface(darkPage, darkInk, lift = 5f)
        assertTrue(surface.background.luminance() < 0.5f)
    }

    @Test
    fun `the dark hairline is the stronger one`() {
        val dark = readerChromeSurface(darkPage, darkInk)
        val light = readerChromeSurface(lightPage, lightInk)
        assertTrue(dark.border.alpha > light.border.alpha)
    }
}
