package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.BookSearch
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Book feeds (keep reading / on deck / latest / paged list); empty without a session. */
@Singleton
class BookRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private val api get() = sessionManager.clients.value?.books

    /** Thumbnail URL for a book id; null without a session. */
    fun thumbnailUrl(bookId: String): String? =
        sessionManager.clients.value?.bookThumbnailUrl(bookId)

    /** Books currently in progress, most recently read first.
     *  Uses the deprecated GET endpoint to avoid the POST /books/list condition DSL. */
    @Suppress("DEPRECATION")
    suspend fun keepReading(size: Int = 12): List<BookDto> {
        val api = api ?: return emptyList()
        return api.getAllBooksDeprecated(
            readStatus = listOf("IN_PROGRESS"),
            size = size,
            sort = listOf("readProgress.lastModified,desc"),
        ).awaitBody()?.content.orEmpty()
    }

    /** First unread book of series with at least one book read (GET api/v1/books/ondeck). */
    suspend fun onDeck(size: Int = 12): List<BookDto> {
        val api = api ?: return emptyList()
        return api.getBooksOnDeck(size = size).awaitBody()?.content.orEmpty()
    }

    /** Newly added or updated books (GET api/v1/books/latest). */
    suspend fun latest(size: Int = 12): List<BookDto> {
        val api = api ?: return emptyList()
        return api.getBooksLatest(size = size).awaitBody()?.content.orEmpty()
    }

    /** Paged book list for the library grid (POST api/v1/books/list). */
    suspend fun list(
        page: Int = 0,
        size: Int = 60,
        sort: List<String> = listOf("metadata.numberSort,asc"),
    ): List<BookDto> {
        val api = api ?: return emptyList()
        return api.getBooks(BookSearch(), page = page, size = size, sort = sort)
            .awaitBody()?.content.orEmpty()
    }
}
