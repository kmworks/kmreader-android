package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Cross-type search (series/books/read lists/collections); empty without a session. */
@Singleton
class SearchRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private fun listSearchApi(): ListSearchApi? =
        sessionManager.clients.value?.let { LocalApiFactory.create(it, ListSearchApi::class.java) }

    /** Thumbnail URL for a series id; null without a session. */
    fun seriesThumbnailUrl(seriesId: String): String? =
        sessionManager.clients.value?.seriesThumbnailUrl(seriesId)

    /** Thumbnail URL for a book id; null without a session. */
    fun bookThumbnailUrl(bookId: String): String? =
        sessionManager.clients.value?.bookThumbnailUrl(bookId)

    /** Series full-text search (POST api/v1/series/list; library filter is a
     *  hand-built condition, matching the iOS POST-only search). */
    suspend fun searchSeries(
        term: String,
        libraryId: String? = null,
        size: Int = 30,
    ): List<SeriesDto> {
        val api = listSearchApi() ?: return emptyList()
        return api.series(
            searchBody(
                condition = libraryId?.let { leafCondition("libraryId", "is", it) },
                fullTextSearch = term.ifBlank { null },
            ),
            size = size,
            sort = listOf("metadata.titleSort,asc"),
        ).awaitBody()?.content.orEmpty()
    }

    /** Books full-text search (POST api/v1/books/list; same library condition). */
    suspend fun searchBooks(
        term: String,
        libraryId: String? = null,
        size: Int = 30,
    ): List<BookDto> {
        val api = listSearchApi() ?: return emptyList()
        return api.books(
            searchBody(
                condition = libraryId?.let { leafCondition("libraryId", "is", it) },
                fullTextSearch = term.ifBlank { null },
            ),
            size = size,
            sort = listOf("metadata.numberSort,asc"),
        ).awaitBody()?.content.orEmpty()
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
