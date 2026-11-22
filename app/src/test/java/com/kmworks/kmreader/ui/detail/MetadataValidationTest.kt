package com.kmworks.kmreader.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Komga rejects a release date that is not `yyyy-MM-dd` with a 400, which used to surface as a save
 * that silently did nothing. The edit sheet disables save on an invalid value, so this validator is
 * the gate that keeps a bad date from ever being submitted.
 */
class MetadataValidationTest {

    @Test
    fun acceptsFullIsoDate() {
        assertTrue(isIsoLocalDate("2024-01-05"))
        assertTrue(isIsoLocalDate("1999-12-31"))
        assertTrue(isIsoLocalDate(" 2024-06-30 "))
    }

    @Test
    fun rejectsLocalizedOrLooseFormats() {
        assertFalse(isIsoLocalDate("2024/1/5"))
        assertFalse(isIsoLocalDate("05.01.2024"))
        assertFalse(isIsoLocalDate("2024-1-5"))
        assertFalse(isIsoLocalDate("Jan 5, 2024"))
        assertFalse(isIsoLocalDate("20240105"))
    }

    @Test
    fun rejectsImpossibleCalendarDates() {
        assertFalse(isIsoLocalDate("2023-02-30"))
        assertFalse(isIsoLocalDate("2024-13-01"))
        assertFalse(isIsoLocalDate("2024-00-10"))
    }

    @Test
    fun rejectsDateWithTimeSuffix() {
        assertFalse(isIsoLocalDate("2024-01-05T10:00:00"))
    }
}
