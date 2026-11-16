package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookMetadataUpdateDto
import com.kmworks.kmreader.core.api.model.CollectionUpdateDto
import com.kmworks.kmreader.core.api.model.ReadListUpdateDto
import com.kmworks.kmreader.core.api.model.ReadProgressUpdateDto
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** 写操作仓库：标记已读/未读、阅读进度上报、加入收藏/阅读列表。 */
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

    /** 批量加入阅读列表；保留服务端顺序并过滤已存在项。 */
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

    /** Komga 的书籍删除操作对应删除书籍文件（DELETE /api/v1/books/{bookId}/file）。 */
    suspend fun deleteBook(bookDtoId: String): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.books.deleteBookFile(bookDtoId).awaitBody() != null
    }

    /** 更新书籍元数据（PATCH /api/v1/books/{bookId}/metadata；只带上锁字段）。 */
    suspend fun updateBookMetadata(bookDtoId: String, body: BookMetadataUpdateDto): Boolean {
        val clients = sessionManager.clients.value ?: return false
        return clients.books.updateBookMetadata(bookDtoId, body).awaitBody() != null
    }

    /** 更新系列元数据（PATCH /api/v1/series/{seriesId}/metadata；只带上锁字段）。 */
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
