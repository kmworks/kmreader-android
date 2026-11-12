package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.network.SessionManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** In-process map from UI stable id (Int) to DTO id (String); mappers register while lists load. */
object DetailIdRegistry {
    private val series = ConcurrentHashMap<Int, String>()
    private val books = ConcurrentHashMap<Int, String>()
    private val collections = ConcurrentHashMap<Int, String>()
    private val readLists = ConcurrentHashMap<Int, String>()
    private val smartLists = ConcurrentHashMap<Int, String>()

    fun registerSeries(stableId: Int, dtoId: String) { series[stableId] = dtoId }
    fun registerBook(stableId: Int, dtoId: String) { books[stableId] = dtoId }
    fun registerCollection(stableId: Int, dtoId: String) { collections[stableId] = dtoId }
    fun registerReadList(stableId: Int, dtoId: String) { readLists[stableId] = dtoId }
    fun registerSmartList(stableId: Int, dtoId: String) { smartLists[stableId] = dtoId }

    fun seriesDtoId(stableId: Int): String? = series[stableId]
    fun bookDtoId(stableId: Int): String? = books[stableId]
    fun collectionDtoId(stableId: Int): String? = collections[stableId]
    fun readListDtoId(stableId: Int): String? = readLists[stableId]
    fun smartListDtoId(stableId: Int): String? = smartLists[stableId]
}

/** Single-entity fetches for detail screens; resolves DTO ids through DetailIdRegistry. */
@Singleton
class DetailRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    fun seriesThumbnailUrl(seriesId: String): String? =
        sessionManager.clients.value?.seriesThumbnailUrl(seriesId)

    fun bookThumbnailUrl(bookId: String): String? =
        sessionManager.clients.value?.bookThumbnailUrl(bookId)

    fun seriesDtoId(stableId: Int): String? = DetailIdRegistry.seriesDtoId(stableId)

    fun bookDtoId(stableId: Int): String? = DetailIdRegistry.bookDtoId(stableId)

    fun collectionDtoId(stableId: Int): String? = DetailIdRegistry.collectionDtoId(stableId)

    fun readListDtoId(stableId: Int): String? = DetailIdRegistry.readListDtoId(stableId)

    suspend fun seriesDetail(dtoId: String): SeriesDto? {
        val api = sessionManager.clients.value?.series ?: return null
        return api.getSeriesById(dtoId).awaitBody()
    }

    suspend fun seriesBooks(dtoId: String): List<BookDto> {
        val api = sessionManager.clients.value?.series ?: return emptyList()
        return api.getBooksBySeriesId(
            seriesId = dtoId,
            unpaged = true,
            sort = listOf("metadata.numberSort,asc"),
        ).awaitBody()?.content.orEmpty()
    }

    suspend fun bookDetail(dtoId: String): BookDto? {
        val api = sessionManager.clients.value?.books ?: return null
        return api.getBookById(dtoId).awaitBody()
    }

    suspend fun collectionDetail(dtoId: String): CollectionDto? {
        val api = sessionManager.clients.value?.collections ?: return null
        return api.getCollectionById(dtoId).awaitBody()
    }

    suspend fun collectionSeries(seriesIds: List<String>): List<SeriesDto> {
        val api = sessionManager.clients.value?.series ?: return emptyList()
        return coroutineScope {
            seriesIds
                .map { id -> async { api.getSeriesById(id).awaitBody() } }
                .mapNotNull { it.await() }
        }
    }

    suspend fun readListDetail(dtoId: String): ReadListDto? {
        val api = sessionManager.clients.value?.readlists ?: return null
        return api.getReadListById(dtoId).awaitBody()
    }

    suspend fun readListBooks(bookIds: List<String>): List<BookDto> {
        val api = sessionManager.clients.value?.books ?: return emptyList()
        return coroutineScope {
            bookIds
                .map { id -> async { id to api.getBookById(id).awaitBody() } }
                .mapNotNull { it.await().second }
        }
    }
}
