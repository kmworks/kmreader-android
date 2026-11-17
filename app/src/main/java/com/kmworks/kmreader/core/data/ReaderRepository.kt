package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.BookPagesApi
import com.kmworks.kmreader.core.api.model.PageDto
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DIVINA reader page pipeline: page list + direct page/thumbnail URLs.
 *
 * ApiClients does not expose BookPagesApi (core/network is read-only); the service is built via
 * LocalApiFactory with the same session's authenticated OkHttpClient; URL composition follows ThumbnailUrls.
 */
@Singleton
class ReaderRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private val clients get() = sessionManager.clients.value

    private fun api(): BookPagesApi? =
        clients?.let { LocalApiFactory.create(it, BookPagesApi::class.java) }

    /** Resolve the Komga book id from the UI stable id; null for sample books. */
    fun bookDtoId(stableId: Int): String? = DetailIdRegistry.bookDtoId(stableId)

    /** Page list (number/fileName/mediaType/width/height); empty on error or no session. */
    suspend fun pages(dtoId: String): List<PageDto> =
        api()?.getBookPages(dtoId)?.awaitBody().orEmpty()

    /** Full page image URL (1-based page number). */
    fun pageUrl(dtoId: String, pageNumber: Int): String? =
        clients?.let { "${it.baseUrl}/api/v1/books/$dtoId/pages/$pageNumber" }

    /** Page thumbnail URL (300px, for the thumbnail strip). */
    fun pageThumbnailUrl(dtoId: String, pageNumber: Int): String? =
        clients?.let { "${it.baseUrl}/api/v1/books/$dtoId/pages/$pageNumber/thumbnail" }
}
