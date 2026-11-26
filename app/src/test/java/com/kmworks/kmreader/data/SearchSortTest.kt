package com.kmworks.kmreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the search tab's relevance sort token: it is the tab's default, the first entry of its sort
 * card, and it never leaks into a catalogue panel (which sorts by a real field).
 */
class SearchSortTest {
    @Test
    fun searchDefaultsToRelevance() {
        assertEquals(SortRelevance, defaultSearchOptions(BrowseContentType.Series).sortField)
        assertEquals(SortRelevance, defaultSearchOptions(BrowseContentType.Books).sortField)
    }

    @Test
    fun catalogueDefaultsStayTheirOwnFirstField() {
        assertEquals("metadata.titleSort", defaultBrowseOptions(BrowseContentType.Series).sortField)
        assertEquals("series", defaultBrowseOptions(BrowseContentType.Books).sortField)
    }

    @Test
    fun searchSortCardStartsWithRelevance() {
        assertEquals(SortRelevance, searchSortFieldsFor(BrowseContentType.Series).first())
        assertEquals(SortRelevance, searchSortFieldsFor(BrowseContentType.Books).first())
        // The catalogue vocabularies themselves must not offer it
        assertFalse(SortRelevance in sortFieldsFor(BrowseContentType.Series))
        assertFalse(SortRelevance in sortFieldsFor(BrowseContentType.Books))
    }

    @Test
    fun relevanceHasNoDirection() {
        assertFalse(supportsSortDirection(SortRelevance, BrowseContentType.Series))
        assertFalse(supportsSortDirection(SortRelevance, BrowseContentType.Books))
        assertTrue(supportsSortDirection("metadata.titleSort", BrowseContentType.Series))
        assertFalse(supportsSortDirection("random", BrowseContentType.Series))
    }

    @Test
    fun normalizationKeepsRelevanceOnlyForTheSearchPanel() {
        assertEquals(SortRelevance, normalizeSortField(SortRelevance, BrowseContentType.Series, allowRelevance = true))
        assertEquals(SortRelevance, normalizeSortField(SortRelevance, BrowseContentType.Books, allowRelevance = true))
        // A catalogue panel falls back to its own default field instead of showing a dead chip
        assertEquals("metadata.titleSort", normalizeSortField(SortRelevance, BrowseContentType.Series))
        assertEquals("series", normalizeSortField(SortRelevance, BrowseContentType.Books))
    }

    @Test
    fun pickedFieldsSurviveNormalization() {
        assertEquals("random", normalizeSortField("random", BrowseContentType.Series))
        assertEquals("series", normalizeSortField("series", BrowseContentType.Books))
    }
}
