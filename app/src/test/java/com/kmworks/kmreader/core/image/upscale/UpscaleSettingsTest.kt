package com.kmworks.kmreader.core.image.upscale

import com.kmworks.kmreader.ui.reader.UpscalePrecision
import com.kmworks.kmreader.ui.reader.UpscaleSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class UpscaleSettingsTest {

    @Test
    fun `auto is the default precision`() {
        assertEquals(UpscalePrecision.Auto, UpscaleSettings().precision)
    }

    @Test
    fun `preload ahead defaults to three and is capped at five`() {
        assertEquals(3, UpscaleSettings().preloadAhead)
        assertEquals(5, UpscaleSettings.MAX_PRELOAD_AHEAD)
    }

    @Test
    fun `auto trigger threshold defaults to the iOS value and keeps its range`() {
        // iOS AppConfig.imageUpscaleAutoTriggerScale returns 1.05 when nothing is stored, and its
        // slider runs 1.0...1.5.
        assertEquals(1.05f, UpscaleSettings().autoTriggerScale, 0f)
        assertEquals(1f, UpscaleConfig.MIN_AUTO_TRIGGER_SCALE, 0f)
        assertEquals(1.5f, UpscaleConfig.MAX_AUTO_TRIGGER_SCALE, 0f)
    }

    @Test
    fun `auto trigger threshold is clamped into range`() {
        assertEquals(1f, UpscaleConfig.clampAutoTriggerScale(0.4f), 0f)
        assertEquals(1.5f, UpscaleConfig.clampAutoTriggerScale(3f), 0f)
        assertEquals(1.2f, UpscaleConfig.clampAutoTriggerScale(1.2f), 0f)
    }

    @Test
    fun `a non-finite auto trigger threshold falls back to the default`() {
        assertEquals(
            UpscaleConfig.DEFAULT_AUTO_TRIGGER_SCALE,
            UpscaleConfig.clampAutoTriggerScale(Float.NaN),
            0f,
        )
    }
}
