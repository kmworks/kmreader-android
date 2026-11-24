package com.kmworks.kmreader.core.image.upscale

import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.UpscaleMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trigger-decision contract (docs/waifu2x-upscaling-plan.md §3.2): Off never upscales; Always
 * upscales anything that fits a texture; Auto upscales only pages the screen would enlarge by
 * more than the trigger scale (iOS ReaderUpscaleDecision semantics).
 */
class UpscaleDecisionTest {

    private val auto = UpscaleConfig(mode = UpscaleMode.Auto, denoise = DenoiseLevel.Low)
    private val always = auto.copy(mode = UpscaleMode.Always)
    private val off = auto.copy(mode = UpscaleMode.Off)

    @Test
    fun `off never upscales`() {
        assertFalse(UpscaleDecision.shouldUpscale(400, 600, 1200, 2400, off))
    }

    @Test
    fun `auto upscales low-resolution pages enlarged on screen`() {
        // 400x600 page on a 1200x2400 screen: display scale 3.0 > 1.5.
        assertTrue(UpscaleDecision.shouldUpscale(400, 600, 1200, 2400, auto))
    }

    @Test
    fun `auto leaves pages at or below the trigger scale alone`() {
        // Display scale exactly at the trigger does not upscale (iOS uses strict >).
        assertFalse(UpscaleDecision.shouldUpscale(800, 1200, 1200, 2400, auto))
        // High-resolution pages displayed below the trigger scale are not reprocessed.
        assertFalse(UpscaleDecision.shouldUpscale(1600, 2400, 1200, 2400, auto))
    }

    @Test
    fun `auto honors a custom trigger scale`() {
        val sensitive = auto.copy(autoTriggerScale = 1.2f)
        assertTrue(UpscaleDecision.shouldUpscale(800, 1200, 1200, 2400, sensitive))
    }

    @Test
    fun `always upscales any page that fits a texture`() {
        assertTrue(UpscaleDecision.shouldUpscale(1600, 2400, 1200, 2400, always))
    }

    @Test
    fun `texture limit rejects a 2x output that could not be drawn`() {
        // 2x of 5000 would exceed an 8192-px texture.
        assertFalse(UpscaleDecision.shouldUpscale(5000, 3000, 1200, 2400, always, maxTextureWidth = 8192, maxTextureHeight = 8192))
        assertTrue(UpscaleDecision.shouldUpscale(4000, 3000, 1200, 2400, always, maxTextureWidth = 8192, maxTextureHeight = 8192))
    }

    @Test
    fun `invalid dimensions never upscale`() {
        assertFalse(UpscaleDecision.shouldUpscale(0, 600, 1200, 2400, always))
        assertFalse(UpscaleDecision.shouldUpscale(400, 600, 0, 2400, always))
    }
}
