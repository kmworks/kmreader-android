package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.CollectionSeriesApi
import com.kmworks.kmreader.core.api.ReadlistBooksApi
import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.network.SessionManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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

    /** Reverse: find the registered stableId from a DTO id (null when absent). */
    private fun stableId(map: ConcurrentHashMap<Int, String>, dtoId: String): Int? =
        map.entries.firstOrNull { it.value == dtoId }?.key

    fun seriesStableId(dtoId: String): Int? = stableId(series, dtoId)
    fun bookStableId(dtoId: String): Int? = stableId(books, dtoId)
    fun collectionStableId(dtoId: String): Int? = stableId(collections, dtoId)
    fun readListStableId(dtoId: String): Int? = stableId(readLists, dtoId)
    fun smartListStableId(dtoId: String): Int? = stableId(smartLists, dtoId)
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

    fun collectionThumbnailUrl(collectionId: String): String? =
        sessionManager.clients.value?.collectionThumbnailUrl(collectionId)

    fun readListThumbnailUrl(readListId: String): String? =
        sessionManager.clients.value?.readListThumbnailUrl(readListId)

    fun seriesDtoId(stableId: Int): String? = DetailIdRegistry.seriesDtoId(stableId)

    fun bookDtoId(stableId: Int): String? = DetailIdRegistry.bookDtoId(stableId)

    fun collectionDtoId(stableId: Int): String? = DetailIdRegistry.collectionDtoId(stableId)

    fun readListDtoId(stableId: Int): String? = DetailIdRegistry.readListDtoId(stableId)

    /**
     * The stable ids a content change refers to, resolved through the registry. Empty when the change names no
     * entities or none of them was ever displayed; such a screen has nothing to patch and ignores the change.
     */
    fun stableIdsOf(change: ContentChange): Set<Int> = change.dtoIds.mapNotNull { dtoId ->
        when (change) {
            is ContentChange.Books -> DetailIdRegistry.bookStableId(dtoId)
            is ContentChange.Series -> DetailIdRegistry.seriesStableId(dtoId)
            is ContentChange.Collections -> DetailIdRegistry.collectionStableId(dtoId)
            is ContentChange.ReadLists -> DetailIdRegistry.readListStableId(dtoId)
            is ContentChange.SmartLists -> DetailIdRegistry.smartListStableId(dtoId)
            ContentChange.Unknown -> null
        }
    }.toSet()

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

    /**
     * Book → owning series stableId (used by the "Visit Series" navigation).
     * Uses the registry when present; otherwise assigns a unique stable id via StableIds (consistent with Mappers).
     */
    suspend fun seriesStableIdForBook(bookStableId: Int): Int? {
        val bookDtoId = DetailIdRegistry.bookDtoId(bookStableId) ?: return null
        val seriesId = bookDetail(bookDtoId)?.seriesId ?: return null
        DetailIdRegistry.seriesStableId(seriesId)?.let { return it }
        val stableId = StableIds.series(seriesId)
        DetailIdRegistry.registerSeries(stableId, seriesId)
        // The cover registry must stay in sync with DetailIdRegistry: if this series was never loaded as a
        // series object (e.g. continue-reading only carries books), CoverArt on the detail page cannot resolve
        // a URL without registration and the first open shows a placeholder.
        CoverUrlRegistry.registerSeries(stableId, seriesId)
        return stableId
    }

    suspend fun collectionDetail(dtoId: String): CollectionDto? {
        val api = sessionManager.clients.value?.collections ?: return null
        return api.getCollectionById(dtoId).awaitBody()
    }

    /** Collection members in server order (GET api/v1/collections/{id}/series).
     *  ApiClients does not expose CollectionSeriesApi, so it is built locally via LocalApiFactory. */
    suspend fun collectionSeries(dtoId: String, page: Int = 0, size: Int = 50): Paged<SeriesDto> {
        val clients = sessionManager.clients.value ?: return Paged(emptyList(), hasMore = false)
        val api = LocalApiFactory.create(clients, CollectionSeriesApi::class.java)
        val body = api.getSeriesByCollectionId(dtoId, page = page, size = size).awaitBody()
        val items = body?.content.orEmpty()
        return Paged(items, hasMore = items.size >= size && body?.last != true)
    }

    suspend fun readListDetail(dtoId: String): ReadListDto? {
        val api = sessionManager.clients.value?.readlists ?: return null
        return api.getReadListById(dtoId).awaitBody()
    }

    /** Read list books in list order (GET api/v1/readlists/{id}/books). */
    suspend fun readListBooks(dtoId: String, page: Int = 0, size: Int = 50): Paged<BookDto> {
        val clients = sessionManager.clients.value ?: return Paged(emptyList(), hasMore = false)
        val api = LocalApiFactory.create(clients, ReadlistBooksApi::class.java)
        val body = api.getBooksByReadListId(dtoId, page = page, size = size).awaitBody()
        val items = body?.content.orEmpty()
        return Paged(items, hasMore = items.size >= size && body?.last != true)
    }

    /** Collections containing the series (empty on failure — ownership display must not block the detail). */
    suspend fun seriesCollections(dtoId: String): List<CollectionDto> {
        val api = sessionManager.clients.value?.series ?: return emptyList()
        return api.getCollectionsBySeriesId(dtoId).awaitBody().orEmpty()
    }

    /** Read lists containing the book (empty on failure — ownership display must not block the detail). */
    suspend fun bookReadLists(dtoId: String): List<ReadListDto> {
        val api = sessionManager.clients.value?.books ?: return emptyList()
        return api.getReadListsByBookId(dtoId).awaitBody().orEmpty()
    }
}
