package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.BookPagesApi
import com.kmworks.kmreader.core.api.ReadlistBooksApi
import com.kmworks.kmreader.core.api.model.BookDto
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

    /**
     * Previous/next sibling of a book (GET /books/{bookId}/previous|next, the same endpoints the
     * original resolves through BookService.getPreviousBook/getNextBook). The server answers without a
     * body when the series has no neighbour, so a missing side is null; a failed lookup (offline)
     * throws instead, which is what keeps the end page from claiming the series is finished.
     *
     * With a read-list id the read-list-scoped siblings are used instead
     * (GET /readlists/{id}/books/{bookId}/previous|next), so a book opened from a read list continues in
     * that list's order rather than in series order — the same switch the original makes when
     * ReaderContentService has a ReaderReadListContext.
     *
     * Null when there is no session yet.
     */
    suspend fun adjacentBooks(dtoId: String, readListId: String? = null): Pair<BookDto?, BookDto?>? {
        val clients = clients ?: return null
        if (readListId != null) {
            val api = LocalApiFactory.create(clients, ReadlistBooksApi::class.java)
            val next = api.getBookSiblingNextInReadList(readListId, dtoId).awaitBodyResult().getOrThrow()
            val previous =
                api.getBookSiblingPreviousInReadList(readListId, dtoId).awaitBodyResult().getOrThrow()
            return previous to next
        }
        val books = clients.books
        val next = books.getBookSiblingNext(dtoId).awaitBodyResult().getOrThrow()
        val previous = books.getBookSiblingPrevious(dtoId).awaitBodyResult().getOrThrow()
        return previous to next
    }
}
