package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.data.TriState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

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

    // ---- 库浏览筛选（BrowseOptionsSheet → POST 手写条件，对齐 iOS buildCondition）----

    /** 系列浏览：库范围+筛选+排序真实作用于 POST series/list；失败返回 null。 */
    suspend fun browseSeries(
        libraryIds: List<String>?,
        options: BookBrowseOptions,
        size: Int = 100,
    ): List<Series>? {
        val api = listSearchApi() ?: return null
        val body = searchBody(
            condition = combinedCondition(seriesConditions(libraryIds, options)),
        )
        return api.series(body, size = size, sort = listOf(seriesSort(options)))
            .awaitBody()?.content?.map { it.toUiSeries() }
    }

    /** 书籍浏览：库范围+筛选+排序真实作用于 POST books/list；失败返回 null。 */
    suspend fun browseBooks(
        libraryIds: List<String>?,
        options: BookBrowseOptions,
        size: Int = 200,
    ): List<Book>? {
        val api = listSearchApi() ?: return null
        val body = searchBody(
            condition = combinedCondition(bookConditions(libraryIds, options)),
        )
        return api.books(body, size = size, sort = listOf(bookSort(options)))
            .awaitBody()?.content?.map { it.toUiBook() }
    }

    /** 书籍筛选条件（对齐 iOS BookSearch.buildCondition 的条件键与操作符）。 */
    private fun bookConditions(
        libraryIds: List<String>?,
        options: BookBrowseOptions,
    ): List<JsonObject> {
        val conditions = mutableListOf<JsonObject>()
        libraryIds?.takeIf { it.isNotEmpty() }?.let { conditions += libraryCondition(it) }
        conditions += readStatusConditions(options)
        if (options.oneshot == TriState.Include) {
            conditions += flagLeafCondition("oneshot", "isTrue")
        }
        if (options.deleted == TriState.Include) {
            conditions += flagLeafCondition("deleted", "isTrue")
        }
        if (options.authors.isNotEmpty()) {
            val authorLeaves = options.authors.map(::authorCondition)
            conditions += if (options.authorLogicAnd) {
                allOfCondition(authorLeaves)
            } else {
                anyOfCondition(authorLeaves)
            }
        }
        tagCondition(options)?.let { conditions += it }
        return conditions
    }

    /** 系列筛选条件（作者条件不适用系列，其余键与书籍一致）。 */
    private fun seriesConditions(
        libraryIds: List<String>?,
        options: BookBrowseOptions,
    ): List<JsonObject> {
        val conditions = mutableListOf<JsonObject>()
        libraryIds?.takeIf { it.isNotEmpty() }?.let { conditions += libraryCondition(it) }
        conditions += readStatusConditions(options)
        if (options.oneshot == TriState.Include) {
            conditions += flagLeafCondition("oneshot", "isTrue")
        }
        if (options.deleted == TriState.Include) {
            conditions += flagLeafCondition("deleted", "isTrue")
        }
        tagCondition(options)?.let { conditions += it }
        return conditions
    }

    /** 阅读状态条件：包含项 anyOf is，排除项各挂一个 isNot（对齐原作）。 */
    private fun readStatusConditions(options: BookBrowseOptions): List<JsonObject> {
        val includes = options.readStatuses
            .filterValues { it == TriState.Include }
            .keys
            .map { leafCondition("readStatus", "is", readStatusApiName(it)) }
        val excludes = options.readStatuses
            .filterValues { it == TriState.Exclude }
            .keys
            .map { leafCondition("readStatus", "isNot", readStatusApiName(it)) }
        return listOfNotNull(combinedCondition(includes)) + excludes
    }

    /** ReadStatus → Komga API 值（UNREAD/IN_PROGRESS/READ）。 */
    private fun readStatusApiName(status: com.kmworks.kmreader.data.ReadStatus): String =
        when (status) {
            com.kmworks.kmreader.data.ReadStatus.Unread -> "UNREAD"
            com.kmworks.kmreader.data.ReadStatus.InProgress -> "IN_PROGRESS"
            com.kmworks.kmreader.data.ReadStatus.Read -> "READ"
        }

    /** 标签条件：∧=allOf(tag is)，∨=anyOf（对齐原作 tag 分支）。 */
    private fun tagCondition(options: BookBrowseOptions): JsonObject? {
        if (options.tags.isEmpty()) return null
        val leaves = options.tags.map { leafCondition("tag", "is", it) }
        return if (options.tagLogicAnd) allOfCondition(leaves) else anyOfCondition(leaves)
    }

    /** 书籍排序映射（Komga books/list sort 字段）。 */
    private fun bookSort(options: BookBrowseOptions): String {
        val field = when (options.sortField) {
            "标题" -> "metadata.titleSort"
            "发布日期" -> "metadata.releaseDate"
            "添加日期" -> "createdDate"
            "更新日期" -> "lastModifiedDate"
            "文件大小" -> "fileSize"
            "页数" -> "media.pagesCount"
            else -> "metadata.numberSort"
        }
        return "$field,${if (options.sortAscending) "asc" else "desc"}"
    }

    /** 系列排序映射（Komga series/list 字段；无对应字段回退 booksCount/titleSort）。 */
    private fun seriesSort(options: BookBrowseOptions): String {
        val field = when (options.sortField) {
            "发布日期" -> "booksMetadata.releaseDate"
            "添加日期" -> "createdDate"
            "更新日期" -> "lastModifiedDate"
            "文件大小", "页数" -> "booksCount"
            else -> "metadata.titleSort"
        }
        return "$field,${if (options.sortAscending) "asc" else "desc"}"
    }
}
