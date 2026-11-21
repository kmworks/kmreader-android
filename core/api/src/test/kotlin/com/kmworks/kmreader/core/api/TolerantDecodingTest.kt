package com.kmworks.kmreader.core.api

import com.kmworks.kmreader.core.api.model.PageBookDto
import com.kmworks.kmreader.core.api.model.PageSeriesDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Komga server older than the pinned OpenAPI snapshot omits properties that
 * the snapshot declares required (for example `notableEntities`, which only
 * exists from Komga 1.29). The generated DTOs must decode those payloads
 * instead of throwing MissingFieldException, which is what the iOS client gets
 * for free from Codable's `decodeIfPresent` for its optional properties.
 *
 * The fixtures are real responses captured from a stock Komga 1.25 server.
 */
class TolerantDecodingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource("fixtures/$name")) { "missing fixture $name" }
            .readText()

    @Test
    fun `decodes series page from komga 1_25`() {
        val page = json.decodeFromString(
            PageSeriesDto.serializer(),
            fixture("series-page-komga-1.25.json"),
        )

        assertEquals(7659L, page.totalElements)
        assertEquals(2, page.content?.size)

        val series = page.content!!.first()
        assertEquals("老師", series.name)
        assertEquals(95, series.booksCount)
        assertEquals("ENDED", series.metadata.status)
        // Absent on 1.25: decoded as an empty value, never a missing-field failure.
        assertTrue(series.booksMetadata.notableEntities.isEmpty())
    }

    @Test
    fun `decodes book page from komga 1_25`() {
        val page = json.decodeFromString(
            PageBookDto.serializer(),
            fixture("books-page-komga-1.25.json"),
        )

        val book = page.content!!.first()
        // Absent on 1.25: decoded as an empty value, never a missing-field failure.
        assertTrue(book.metadata.notableEntities.isEmpty())
        assertFalse(book.metadata.notableEntitiesLock)
    }
}
