package com.kmworks.kmreader.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings hub is a master/detail split only where both panes fit: a tablet in a window wide enough
 * for the hub column plus a readable page. Everything narrower keeps the single-column hub with pushed
 * pages, including a phone held in landscape.
 */
class SettingsLayoutTest {

    @Test
    fun splitsOnATabletWithRoomForBothPanes() {
        assertTrue(isSettingsSplitLayout(smallestScreenWidthDp = 600, screenWidthDp = 840))
        assertTrue(isSettingsSplitLayout(smallestScreenWidthDp = 800, screenWidthDp = 1280))
    }

    @Test
    fun keepsOneColumnOnAPhoneEvenInLandscape() {
        assertFalse(isSettingsSplitLayout(smallestScreenWidthDp = 411, screenWidthDp = 891))
    }

    @Test
    fun keepsOneColumnWhenTheWindowIsNarrowerThanTheSplit() {
        assertFalse(isSettingsSplitLayout(smallestScreenWidthDp = 600, screenWidthDp = 839))
        assertFalse(isSettingsSplitLayout(smallestScreenWidthDp = 800, screenWidthDp = 800))
    }
}
