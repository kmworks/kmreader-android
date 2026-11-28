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
}
