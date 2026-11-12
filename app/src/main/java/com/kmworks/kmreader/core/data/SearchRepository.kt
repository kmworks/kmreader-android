package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.BookSearch
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.api.model.SeriesSearch
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Cross-type search (series/books/read lists/collections); empty without a session. */
@Singleton
class SearchRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    /** Thumbnail URL for a series id; null without a session. */
    fun seriesThumbnailUrl(seriesId: String): String? =
        sessionManager.clients.value?.seriesThumbnailUrl(seriesId)

    /** Thumbnail URL for a book id; null without a session. */
    fun bookThumbnailUrl(bookId: String): String? =
        sessionManager.clients.value?.bookThumbnailUrl(bookId)

    /** Series full-text search (POST api/v1/series/list).
     *  Library filter falls back to the deprecated GET endpoint: the POST
     *  condition DSL is generated with all 21 fields required. */
    @Suppress("DEPRECATION")
    suspend fun searchSeries(
        term: String,
        libraryId: String? = null,
        size: Int = 30,
    ): List<SeriesDto> {
        val api = sessionManager.clients.value?.series ?: return emptyList()
        return if (libraryId == null) {
            api.getSeries(
                SeriesSearch(fullTextSearch = term),
                size = size,
                sort = listOf("metadata.titleSort,asc"),
            ).awaitBody()?.content.orEmpty()
        } else {
            api.getSeriesDeprecated(
                search = term,
                libraryId = listOf(libraryId),
                size = size,
                sort = listOf("metadata.titleSort,asc"),
            ).awaitBody()?.content.orEmpty()
        }
    }

    /** Books full-text search (POST api/v1/books/list); same deprecated
     *  GET fallback when a library filter is active. */
    @Suppress("DEPRECATION")
    suspend fun searchBooks(
        term: String,
        libraryId: String? = null,
        size: Int = 30,
    ): List<BookDto> {
        val api = sessionManager.clients.value?.books ?: return emptyList()
        return if (libraryId == null) {
            api.getBooks(
                BookSearch(fullTextSearch = term),
                size = size,
                sort = listOf("metadata.numberSort,asc"),
            ).awaitBody()?.content.orEmpty()
        } else {
            api.getAllBooksDeprecated(
                search = term,
                libraryId = listOf(libraryId),
                size = size,
                sort = listOf("metadata.numberSort,asc"),
            ).awaitBody()?.content.orEmpty()
        }
    }

    /** Read lists search (GET api/v1/readlists, native search + library_id). */
    suspend fun searchReadLists(
        term: String,
        libraryId: String? = null,
        size: Int = 20,
    ): List<ReadListDto> {
        val api = sessionManager.clients.value?.readlists ?: return emptyList()
        return api.getReadLists(
            search = term.ifBlank { null },
            libraryId = libraryId?.let { listOf(it) },
            size = size,
        ).awaitBody()?.content.orEmpty()
    }

    /** Collections search (GET api/v1/collections, native search + library_id). */
    suspend fun searchCollections(
        term: String,
        libraryId: String? = null,
        size: Int = 20,
    ): List<CollectionDto> {
        val api = sessionManager.clients.value?.collections ?: return emptyList()
        return api.getCollections(
            search = term.ifBlank { null },
            libraryId = libraryId?.let { listOf(it) },
            size = size,
        ).awaitBody()?.content.orEmpty()
    }
}
