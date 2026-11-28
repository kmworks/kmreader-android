package com.kmworks.kmreader.core.image.upscale

import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.UpscaleMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trigger-decision contract: Off never upscales; Auto upscales only pages the screen would enlarge
 * by more than the trigger scale (iOS ReaderUpscaleDecision semantics) and never past the texture
 * limit.
 */
class UpscaleDecisionTest {

    private val auto = UpscaleConfig(mode = UpscaleMode.Auto, denoise = DenoiseLevel.Low)
    private val off = auto.copy(mode = UpscaleMode.Off)

    @Test
    fun `off never upscales`() {
        assertFalse(UpscaleDecision.shouldUpscale(400, 600, 1200, 2400, off))
    }

    @Test
    fun `auto upscales low-resolution pages enlarged on screen`() {
        // 400x600 page on a 1200x2400 screen: display scale 3.0, well past the 1.05 default.
        assertTrue(UpscaleDecision.shouldUpscale(400, 600, 1200, 2400, auto))
    }

    @Test
    fun `auto leaves pages at or below the trigger scale alone`() {
        // Display scale exactly 1.0: the screen is not enlarging the page at all.
        assertFalse(UpscaleDecision.shouldUpscale(1200, 2400, 1200, 2400, auto))
        // 1.04 is below the 1.05 default (iOS compares strictly greater).
        assertFalse(UpscaleDecision.shouldUpscale(1150, 2300, 1200, 2400, auto))
        // High-resolution pages displayed below the trigger scale are not reprocessed.
        assertFalse(UpscaleDecision.shouldUpscale(1600, 2400, 1200, 2400, auto))
    }

    @Test
    fun `auto honors a custom trigger scale`() {
        // 800x1200 on 1200x2400: required scale is exactly 1.5, so the result depends only on the
        // threshold — the default upscales it, the top of the iOS slider does not.
        assertTrue(UpscaleDecision.shouldUpscale(800, 1200, 1200, 2400, auto))
        assertTrue(UpscaleDecision.shouldUpscale(800, 1200, 1200, 2400, auto.copy(autoTriggerScale = 1.2f)))
        assertFalse(UpscaleDecision.shouldUpscale(800, 1200, 1200, 2400, auto.copy(autoTriggerScale = 1.5f)))
    }

    @Test
    fun `texture limit rejects a 2x output that could not be drawn`() {
        // The guard is independent of the ratio check: 2x of 5000 would exceed an 8192-px texture,
        // 2x of 4000 would still fit. The display size is synthetic — no real screen is this wide.
        assertFalse(
            UpscaleDecision.shouldUpscale(
                5000, 3000, 8000, 6000, auto,
                maxTextureWidth = 8192, maxTextureHeight = 8192,
            ),
        )
        assertTrue(
            UpscaleDecision.shouldUpscale(
                4000, 3000, 8000, 6000, auto,
                maxTextureWidth = 8192, maxTextureHeight = 8192,
            ),
        )
    }

    @Test
    fun `invalid dimensions never upscale`() {
        assertFalse(UpscaleDecision.shouldUpscale(0, 600, 1200, 2400, auto))
        assertFalse(UpscaleDecision.shouldUpscale(400, 600, 0, 2400, auto))
    }
}
