package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookMetadataUpdateDto
import com.kmworks.kmreader.core.api.model.CollectionUpdateDto
import com.kmworks.kmreader.core.api.model.ReadListUpdateDto
import com.kmworks.kmreader.core.api.model.ReadProgressUpdateDto
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Write operations repository: mark read/unread, read-progress reporting, add to collection/read list. */
@Singleton
class ActionRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    suspend fun markSeriesRead(seriesDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.series.markSeriesAsRead(seriesDtoId).awaitBody() != null
    }

    suspend fun markSeriesUnread(seriesDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.series.markSeriesAsUnread(seriesDtoId).awaitBody() != null
    }

    suspend fun markBookRead(bookDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.books
            .markBookReadProgress(bookDtoId, ReadProgressUpdateDto(completed = true))
            .awaitBody() != null
    }

    suspend fun markBookUnread(bookDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.books.deleteBookReadProgress(bookDtoId).awaitBody() != null
    }

    suspend fun updateBookReadProgress(bookDtoId: String, page: Int, completed: Boolean = false): Boolean {
        val clients = sessionManager.clients.value ?: return false
        val body = ReadProgressUpdateDto(completed = if (completed) true else null, page = page)
        return clients.books.markBookReadProgress(bookDtoId, body).awaitBody() != null
    }

    suspend fun addSeriesToCollection(collectionDtoId: String, seriesDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        val current = clients.collections.getCollectionById(collectionDtoId).awaitBody() ?: return false
        if (seriesDtoId in current.seriesIds) return true
        val ids = current.seriesIds + seriesDtoId
        return clients.collections
            .updateCollectionById(collectionDtoId, CollectionUpdateDto(seriesIds = ids))
            .awaitBody() != null
    }

    suspend fun addBookToReadList(readListDtoId: String, bookDtoId: String): Boolean {
        return addBooksToReadList(readListDtoId, listOf(bookDtoId))
    }

    /** Batch-add to a read list; keeps server order and filters already-present entries. */
    suspend fun addBooksToReadList(readListDtoId: String, bookDtoIds: List<String>): Boolean {
        val clients = sessionManager.clients.value ?: return false
        val current = clients.readlists.getReadListById(readListDtoId).awaitBody() ?: return false
        val newIds = bookDtoIds.distinct().filterNot { it in current.bookIds }
        if (newIds.isEmpty()) return true
        val ids = current.bookIds + newIds
        return clients.readlists
            .updateReadListById(readListDtoId, ReadListUpdateDto(bookIds = ids))
            .awaitBody() != null
    }

    /** Re-analyze a book (POST /api/v1/books/{bookId}/analyze, admin). */
    suspend fun analyzeBook(bookDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.books.bookAnalyze(bookDtoId).awaitBody() != null
    }

    /** Refresh book metadata (POST /api/v1/books/{bookId}/metadata/refresh, admin). */
    suspend fun refreshBookMetadata(bookDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.books.bookRefreshMetadata(bookDtoId).awaitBody() != null
    }

    /** Re-analyze a series (POST /api/v1/series/{seriesId}/analyze, admin). */
    suspend fun analyzeSeries(seriesDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.series.seriesAnalyze(seriesDtoId).awaitBody() != null
    }

    /** Refresh series metadata (POST /api/v1/series/{seriesId}/metadata/refresh, admin). */
    suspend fun refreshSeriesMetadata(seriesDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.series.seriesRefreshMetadata(seriesDtoId).awaitBody() != null
    }

    /** Delete the series file (DELETE /api/v1/series/{seriesId}/file, admin). */
    suspend fun deleteSeries(seriesDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.series.deleteSeriesFile(seriesDtoId).awaitBody() != null
    }

    /** Komga's book deletion maps to deleting the book file (DELETE /api/v1/books/{bookId}/file). */
    suspend fun deleteBook(bookDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.books.deleteBookFile(bookDtoId).awaitBody() != null
    }

    /** Update book metadata (PATCH /api/v1/books/{bookId}/metadata; only locked fields are sent). */
    suspend fun updateBookMetadata(bookDtoId: String, body: BookMetadataUpdateDto): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.books.updateBookMetadata(bookDtoId, body).awaitBody() != null
    }

    /** Update series metadata (PATCH /api/v1/series/{seriesId}/metadata; only locked fields are sent). */
    suspend fun updateSeriesMetadata(seriesDtoId: String, body: SeriesMetadataUpdateDto): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.series.updateSeriesMetadata(seriesDtoId, body).awaitBody() != null
    }

    suspend fun removeSeriesFromCollection(collectionDtoId: String, seriesDtoIds: List<String>): Boolean {
        val clients = sessionManager.clients.value ?: return false
        val current = clients.collections.getCollectionById(collectionDtoId).awaitBody() ?: return false
        val ids = current.seriesIds.filterNot { it in seriesDtoIds }
        if (ids.size == current.seriesIds.size) return true
        return clients.collections
            .updateCollectionById(collectionDtoId, CollectionUpdateDto(seriesIds = ids))
            .awaitBody() != null
    }

    suspend fun removeBooksFromReadList(readListDtoId: String, bookDtoIds: List<String>): Boolean {
        val clients = sessionManager.clients.value ?: return false
        val current = clients.readlists.getReadListById(readListDtoId).awaitBody() ?: return false
        val ids = current.bookIds.filterNot { it in bookDtoIds }
        if (ids.size == current.bookIds.size) return true
        return clients.readlists
            .updateReadListById(readListDtoId, ReadListUpdateDto(bookIds = ids))
            .awaitBody() != null
    }
}
