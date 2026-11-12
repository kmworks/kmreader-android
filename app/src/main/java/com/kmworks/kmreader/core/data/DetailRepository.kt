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

    /** 反向：由系列 DTO id 找已注册的 stableId（不存在返回 null）。 */
    fun seriesStableId(dtoId: String): Int? =
        series.entries.firstOrNull { it.value == dtoId }?.key
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

    /** 真实阅读器路由格式：按服务器 mediaType 判定（EPUB/PDF/其余 DIVINA）。 */
    suspend fun bookFormat(stableId: Int): String? {
        val dtoId = DetailIdRegistry.bookDtoId(stableId) ?: return null
        val mediaType = bookDetail(dtoId)?.media?.mediaType?.lowercase() ?: return null
        return when {
            "epub" in mediaType -> "EPUB"
            "pdf" in mediaType -> "PDF"
            else -> "CBZ"
        }
    }

    /**
     * 书籍 → 所属系列 stableId（「访问系列」导航用）。
     * 注册表已有直接用；否则注册 seriesId.hashCode() 稳定 id（与 Mappers 一致）。
     */
    suspend fun seriesStableIdForBook(bookStableId: Int): Int? {
        val bookDtoId = DetailIdRegistry.bookDtoId(bookStableId) ?: return null
        val seriesId = bookDetail(bookDtoId)?.seriesId ?: return null
        DetailIdRegistry.seriesStableId(seriesId)?.let { return it }
        val stableId = seriesId.hashCode()
        DetailIdRegistry.registerSeries(stableId, seriesId)
        return stableId
    }

    suspend fun collectionDetail(dtoId: String): CollectionDto? {
        val api = sessionManager.clients.value?.collections ?: return null
        return api.getCollectionById(dtoId).awaitBody()
    }

    /** Collection members in server order (GET api/v1/collections/{id}/series).
     *  ApiClients 未暴露 CollectionSeriesApi，经 LocalApiFactory 本地构建。 */
    suspend fun collectionSeries(dtoId: String): List<SeriesDto> {
        val clients = sessionManager.clients.value ?: return emptyList()
        val api = LocalApiFactory.create(clients, CollectionSeriesApi::class.java)
        return api.getSeriesByCollectionId(dtoId, unpaged = true)
            .awaitBody()?.content.orEmpty()
    }

    suspend fun readListDetail(dtoId: String): ReadListDto? {
        val api = sessionManager.clients.value?.readlists ?: return null
        return api.getReadListById(dtoId).awaitBody()
    }

    /** Read list books in list order (GET api/v1/readlists/{id}/books). */
    suspend fun readListBooks(dtoId: String): List<BookDto> {
        val clients = sessionManager.clients.value ?: return emptyList()
        val api = LocalApiFactory.create(clients, ReadlistBooksApi::class.java)
        return api.getBooksByReadListId(dtoId, unpaged = true)
            .awaitBody()?.content.orEmpty()
    }

    /** 系列所属收藏（失败返回空，归属展示不阻塞详情）。 */
    suspend fun seriesCollections(dtoId: String): List<CollectionDto> {
        val api = sessionManager.clients.value?.series ?: return emptyList()
        return api.getCollectionsBySeriesId(dtoId).awaitBody().orEmpty()
    }

    /** 书籍所属阅读列表（失败返回空，归属展示不阻塞详情）。 */
    suspend fun bookReadLists(dtoId: String): List<ReadListDto> {
        val api = sessionManager.clients.value?.books ?: return emptyList()
        return api.getReadListsByBookId(dtoId).awaitBody().orEmpty()
    }
}
