package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire contract of the series metadata PATCH: Komga reads an explicit null as "unset this
 * field" and an omitted field as "leave it alone", so the two must not collapse — which is exactly
 * what the typed DTO would do on its own.
 */
class MetadataPatchTest {

    @Test
    fun `a field that is not being updated is omitted`() {
        val body = SeriesMetadataUpdateDto().toPatchJson()

        assertTrue(body.isEmpty())
    }

    @Test
    fun `a new value is sent as that value`() {
        val body = SeriesMetadataUpdateDto(ageRating = 12).toPatchJson()

        assertEquals(JsonPrimitive(12), body["ageRating"])
    }

    @Test
    fun `a cleared number is an explicit null`() {
        val body = SeriesMetadataUpdateDto().toPatchJson(setOf("ageRating", "totalBookCount"))

        assertEquals(JsonNull, body["ageRating"])
        assertEquals(JsonNull, body["totalBookCount"])
    }

    @Test
    fun `a cleared number rides along with the other updates`() {
        val body = SeriesMetadataUpdateDto(title = "New title").toPatchJson(setOf("totalBookCount"))

        assertEquals(JsonPrimitive("New title"), body["title"])
        assertEquals(JsonNull, body["totalBookCount"])
        assertNull(body["ageRating"])
    }

    @Test
    fun `an explicit unlock is sent as false`() {
        val body = SeriesMetadataUpdateDto(titleLock = false).toPatchJson()

        assertEquals(JsonPrimitive(false), body["titleLock"])
    }
}
