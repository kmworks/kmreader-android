package com.kmworks.kmreader.ui.reader

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The margin notes (EPUB header/footer info) sit in the same band the page CSS reserves for them,
 * so the same cutout policy has to be resolved here. The readers are immersive — the bar insets are
 * 0 while reading — which is exactly the state where a first header line printed inside the notch
 * went unnoticed: with "draw under cutout" off the notes must still clear the cutout.
 */
class ReaderOverlayInsetsTest {

    @Test
    fun `drawing under the cutout keeps the old placement`() {
        val insets = readerOverlayInsets(
            drawUnderCutout = true,
            // Immersive: the bars report nothing while the chrome is down.
            statusBarTop = 0.dp,
            navigationBarBottom = 0.dp,
            cutoutTop = 44.dp,
            cutoutBottom = 0.dp,
            cutoutLeft = 0.dp,
            cutoutRight = 0.dp,
        )
        assertEquals(0.dp, insets.top)
        assertEquals(0.dp, insets.bottom)
        assertEquals(0.dp, insets.start)
        assertEquals(0.dp, insets.end)
    }

    @Test
    fun `notch device with the bars hidden keeps the header below the cutout`() {
        val insets = readerOverlayInsets(
            drawUnderCutout = false,
            statusBarTop = 0.dp,
            navigationBarBottom = 0.dp,
            cutoutTop = 44.dp,
        )
        // 44dp, not 0: the status-bar padding the overlay used before is 0 in immersive reading,
        // so the cutout band is the only thing that can hold the header line out of the notch.
        assertEquals(44.dp, insets.top)
        assertEquals(0.dp, insets.bottom)
    }

    @Test
    fun `a visible bar that already clears the cutout is not counted twice`() {
        val insets = readerOverlayInsets(
            drawUnderCutout = false,
            statusBarTop = 48.dp,
            navigationBarBottom = 40.dp,
            cutoutTop = 44.dp,
            cutoutBottom = 24.dp,
        )
        assertEquals(48.dp, insets.top)
        assertEquals(40.dp, insets.bottom)
    }

    @Test
    fun `landscape notch pads the physical edge and swaps under RTL`() {
        val ltr = readerOverlayInsets(
            drawUnderCutout = false,
            cutoutLeft = 44.dp,
            cutoutRight = 8.dp,
        )
        assertEquals(44.dp, ltr.start)
        assertEquals(8.dp, ltr.end)

        val rtl = readerOverlayInsets(
            drawUnderCutout = false,
            cutoutLeft = 44.dp,
            cutoutRight = 8.dp,
            rtl = true,
        )
        assertEquals(8.dp, rtl.start)
        assertEquals(44.dp, rtl.end)
    }
}
