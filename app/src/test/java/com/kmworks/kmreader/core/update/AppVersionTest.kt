package com.kmworks.kmreader.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version ordering for the update check: release tags carry a `v`, builds carry a plain versionName, and
 * a numeric comparison has to see `1.10.0` as newer than `1.9.3`.
 */
class AppVersionTest {

    @Test
    fun ignoresTheTagsLeadingV() {
        assertTrue(AppVersion.isNewer("v1.3.0", "1.2.0"))
        assertTrue(AppVersion.isNewer("V1.3.0", "v1.2.0"))
        assertFalse(AppVersion.isNewer("v1.2.0", "1.2.0"))
    }

    @Test
    fun comparesSegmentsNumerically() {
        assertTrue(AppVersion.isNewer("1.10.0", "1.9.3"))
        assertTrue(AppVersion.isNewer("1.2.1", "1.2.0"))
        assertTrue(AppVersion.isNewer("2.0.0", "1.99.99"))
        assertFalse(AppVersion.isNewer("1.2.0", "1.2.0"))
    }

    @Test
    fun treatsMissingSegmentsAsZero() {
        assertFalse(AppVersion.isNewer("1.2", "1.2.0"))
        assertTrue(AppVersion.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun ranksAPreReleaseBelowItsFinalRelease() {
        assertFalse(AppVersion.isNewer("1.3.0-beta.1", "1.3.0"))
        assertTrue(AppVersion.isNewer("1.3.0", "1.3.0-beta.1"))
        assertTrue(AppVersion.isNewer("1.3.0-beta.2", "1.3.0-beta.1"))
        // A numeric identifier ranks below an alphanumeric one (semver pre-release precedence).
        assertFalse(AppVersion.isNewer("1.3.0-1", "1.3.0-alpha"))
    }

    @Test
    fun ignoresBuildMetadata() {
        assertFalse(AppVersion.isNewer("1.2.0+9", "1.2.0"))
    }

    @Test
    fun unreadableVersionsAreNeverNewer() {
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
        assertFalse(AppVersion.isNewer("latest", "1.2.0"))
        assertFalse(AppVersion.isNewer("1.3.0", "unknown"))
    }

    @Test
    fun parsesPrereleaseAndSegments() {
        val parsed = AppVersion.parse("v1.4.0-rc.2+7")
        assertEquals(listOf(1, 4, 0), parsed?.segments)
        assertEquals("rc.2", parsed?.prerelease)
    }
}
