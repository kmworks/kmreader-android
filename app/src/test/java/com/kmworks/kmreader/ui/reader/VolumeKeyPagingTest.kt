package com.kmworks.kmreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeKeyPagingTest {

    @Test
    fun leftToRightVolumeUpTurnsForward() {
        assertEquals(VolumeKeyPage.Next, volumeKeyPage(volumeUp = true, rtl = false))
        assertEquals(VolumeKeyPage.Previous, volumeKeyPage(volumeUp = false, rtl = false))
    }

    @Test
    fun rightToLeftMirrorsTheKeys() {
        assertEquals(VolumeKeyPage.Previous, volumeKeyPage(volumeUp = true, rtl = true))
        assertEquals(VolumeKeyPage.Next, volumeKeyPage(volumeUp = false, rtl = true))
    }
}
