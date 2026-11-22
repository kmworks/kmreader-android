package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.data.BrowseContentType
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.data.ReadStatus
import com.kmworks.kmreader.data.SeriesStatus
import com.kmworks.kmreader.data.TriState
import com.kmworks.kmreader.data.defaultBrowseOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartListFilterMapperTest {

    private fun documentOf(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun emptyDocumentMatchesEverything() {
        val state = SmartListFilterMapper.filterState(BrowseContentType.Books, JsonObject(emptyMap()))

        assertFalse(state.lossy)
        assertEquals(0, state.activeFilterCount)
        assertEquals("", state.fullTextSearch)
        assertTrue(state.libraryIds.isEmpty())
    }

    @Test
    fun nullDocumentMatchesEverything() {
        val state = SmartListFilterMapper.filterState(BrowseContentType.Series, null)

        assertFalse(state.lossy)
        assertEquals(0, state.activeFilterCount)
    }

    @Test
    fun forwardEmitsIosConditionShape() {
        val options = defaultBrowseOptions(BrowseContentType.Books).copy(
            deleted = TriState.Off,
            oneshot = TriState.Include,
            readStatuses = mapOf(ReadStatus.Unread to TriState.Include),
        )

        val doc = SmartListFilterMapper.searchDocument(BrowseContentType.Books, options, emptyList(), "")

        assertEquals(
            """{"condition":{"allOf":[{"anyOf":[{"readStatus":{"operator":"is","value":"UNREAD"}}]},{"oneShot":{"operator":"isTrue"}}]}}""",
            doc.toString(),
        )
    }

    @Test
    fun fullTextSearchIsTrimmedAndOmittedWhenBlank() {
        val bookOptions = defaultBrowseOptions(BrowseContentType.Books).copy(deleted = TriState.Off)
        val blank = SmartListFilterMapper.searchDocument(BrowseContentType.Books, bookOptions, emptyList(), "   ")
        assertTrue(blank.isEmpty())

        val full = SmartListFilterMapper.searchDocument(BrowseContentType.Books, bookOptions, emptyList(), "  dune  ")
        assertEquals("""{"fullTextSearch":"dune"}""", full.toString())
    }

    @Test
    fun bookOptionsRoundTrip() {
        val options = defaultBrowseOptions(BrowseContentType.Books).copy(
            deleted = TriState.Off,
            readStatuses = mapOf(
                ReadStatus.Unread to TriState.Include,
                ReadStatus.InProgress to TriState.Include,
                ReadStatus.Read to TriState.Exclude,
            ),
            oneshot = TriState.Exclude,
            authors = listOf("Frank Herbert", "Brian Herbert"),
            authorLogicAnd = false,
            tags = listOf("dune"),
            tagLogicAnd = true,
            notableEntities = listOf(NotableEntity(name = "Paul", kind = "character")),
            notableEntitiesAllMatch = false,
        )
        val libraries = listOf("library-1", "library-2")

        val doc = SmartListFilterMapper.searchDocument(BrowseContentType.Books, options, libraries, " spice ")
        val state = SmartListFilterMapper.filterState(BrowseContentType.Books, doc)

        assertFalse(state.lossy)
        assertEquals(libraries, state.libraryIds)
        assertEquals("spice", state.fullTextSearch)
        assertEquals(options, state.options)
    }

    @Test
    fun seriesOptionsRoundTrip() {
        val options = defaultBrowseOptions(BrowseContentType.Series).copy(
            deleted = TriState.Include,
            readStatuses = mapOf(ReadStatus.Read to TriState.Include),
            oneshot = TriState.Include,
            complete = TriState.Exclude,
            seriesStatuses = listOf(SeriesStatus.Ongoing, SeriesStatus.Ended),
            excludedSeriesStatuses = listOf(SeriesStatus.Hiatus),
            seriesStatusAllMatch = false,
            authors = listOf("Frank Herbert"),
            tags = listOf("dune", "space"),
            tagLogicAnd = false,
            publishers = listOf("Chilton"),
            genres = listOf("Science Fiction"),
            languages = listOf("en"),
            ageRatings = listOf("16", "18"),
            releaseYears = listOf("1965", "1969"),
        )

        val doc = SmartListFilterMapper.searchDocument(BrowseContentType.Series, options, emptyList(), "")
        val state = SmartListFilterMapper.filterState(BrowseContentType.Series, doc)

        assertFalse(state.lossy)
        assertEquals(options, state.options)
        assertEquals(2, state.options.releaseYears.size)
    }

    @Test
    fun singleLibraryIsABareLeaf() {
        val options = defaultBrowseOptions(BrowseContentType.Books).copy(deleted = TriState.Off)
        val doc = SmartListFilterMapper.searchDocument(BrowseContentType.Books, options, listOf("lib"), "")
        assertEquals("""{"condition":{"libraryId":{"operator":"is","value":"lib"}}}""", doc.toString())
    }

    @Test
    fun yearPairFromIosDocumentIsRecognized() {
        // iOS wraps a lone year filter in one allOf; the pair is the year interval.
        val state = SmartListFilterMapper.filterState(
            BrowseContentType.Series,
            documentOf(
                """{"condition":{"allOf":[{"allOf":[{"releaseDate":{"operator":"after","dateTime":"1964-12-31T12:00:00Z"}},{"releaseDate":{"operator":"before","dateTime":"1966-01-01T12:00:00Z"}}]}]}}""",
            ),
        )

        assertFalse(state.lossy)
        assertEquals(listOf("1965"), state.options.releaseYears)
    }

    @Test
    fun anyOfYearGroupIsLossy() {
        val state = SmartListFilterMapper.filterState(
            BrowseContentType.Series,
            documentOf(
                """{"condition":{"anyOf":[{"allOf":[{"releaseDate":{"operator":"after","dateTime":"1964-12-31T12:00:00Z"}},{"releaseDate":{"operator":"before","dateTime":"1966-01-01T12:00:00Z"}}]}]}}""",
            ),
        )

        assertTrue(state.lossy)
        assertEquals(listOf("1965"), state.options.releaseYears)
    }

    @Test
    fun scopingIdIsLossy() {
        val state = SmartListFilterMapper.filterState(
            BrowseContentType.Books,
            documentOf("""{"condition":{"seriesId":{"operator":"is","value":"series-1"}}}"""),
        )

        assertTrue(state.lossy)
        assertEquals(0, state.activeFilterCount)
    }

    @Test
    fun completeFlagOnBookIsLossy() {
        val state = SmartListFilterMapper.filterState(
            BrowseContentType.Books,
            documentOf("""{"condition":{"complete":{"operator":"isTrue"}}}"""),
        )

        assertTrue(state.lossy)
    }

    @Test
    fun nonYearReleaseDateIsLossy() {
        val state = SmartListFilterMapper.filterState(
            BrowseContentType.Series,
            documentOf("""{"condition":{"releaseDate":{"operator":"after","dateTime":"2020-01-01T00:00:00Z"}}}"""),
        )

        assertTrue(state.lossy)
    }

    @Test
    fun mixedGroupIsLossy() {
        val state = SmartListFilterMapper.filterState(
            BrowseContentType.Books,
            documentOf(
                """{"condition":{"anyOf":[{"tag":{"operator":"is","value":"a"}},{"author":{"operator":"is","value":{"name":"b"}}}]}}""",
            ),
        )

        assertTrue(state.lossy)
    }

    @Test
    fun nonObjectConditionIsLossy() {
        val state = SmartListFilterMapper.filterState(
            BrowseContentType.Books,
            documentOf("""{"condition":"oops"}"""),
        )

        assertTrue(state.lossy)
    }

    @Test
    fun activeFilterCountCountsValuesAndFlags() {
        val options = defaultBrowseOptions(BrowseContentType.Series).copy(
            deleted = TriState.Exclude,
            readStatuses = mapOf(ReadStatus.Unread to TriState.Include, ReadStatus.Read to TriState.Exclude),
            oneshot = TriState.Include,
            tags = listOf("a", "b"),
            releaseYears = listOf("2020"),
        )
        val state = SmartListFilterMapper.filterState(
            BrowseContentType.Series,
            SmartListFilterMapper.searchDocument(BrowseContentType.Series, options, emptyList(), "x"),
        )

        // 2 read statuses + oneshot + deleted + 2 tags + 1 release year; full text is not a filter.
        assertEquals(7, state.activeFilterCount)
    }
}
