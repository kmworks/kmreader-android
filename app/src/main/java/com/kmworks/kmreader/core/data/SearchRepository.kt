package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.PageBookDto
import com.kmworks.kmreader.core.api.model.PageSeriesDto
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.SampleData
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

    /** Series full-text search page (POST api/v1/series/list; library filter is a
     *  hand-built condition, matching the iOS POST-only search). */
    suspend fun searchSeriesPage(
        term: String,
        libraryId: String? = null,
        page: Int = 0,
        size: Int = 30,
    ): Paged<Series> {
        val api = listSearchApi() ?: return Paged(emptyList(), hasMore = false)
        val result = api.series(
            searchBody(
                condition = libraryId?.let { leafCondition("libraryId", "is", it) },
                fullTextSearch = term.ifBlank { null },
            ),
            page = page,
            size = size,
            sort = listOf("metadata.titleSort,asc"),
        ).awaitBody() ?: return Paged(emptyList(), hasMore = false)
        return result.toSeriesPage(size)
    }

    /** Books full-text search page (POST api/v1/books/list; same library condition). */
    suspend fun searchBooksPage(
        term: String,
        libraryId: String? = null,
        page: Int = 0,
        size: Int = 30,
    ): Paged<Book> {
        val api = listSearchApi() ?: return Paged(emptyList(), hasMore = false)
        val result = api.books(
            searchBody(
                condition = libraryId?.let { leafCondition("libraryId", "is", it) },
                fullTextSearch = term.ifBlank { null },
            ),
            page = page,
            size = size,
            sort = listOf("metadata.numberSort,asc"),
        ).awaitBody() ?: return Paged(emptyList(), hasMore = false)
        return result.toBookPage(size)
    }

    /** Read lists search page (GET api/v1/readlists, native search + library_id). */
    suspend fun searchReadListsPage(
        term: String,
        libraryId: String? = null,
        page: Int = 0,
        size: Int = 20,
    ): Paged<SampleData.ListEntry> {
        val api = sessionManager.clients.value?.readlists
            ?: return Paged(emptyList(), hasMore = false)
        val result = api.getReadLists(
            search = term.ifBlank { null },
            libraryId = libraryId?.let { listOf(it) },
            page = page,
            size = size,
        ).awaitBody() ?: return Paged(emptyList(), hasMore = false)
        val items = result.content.orEmpty()
        return Paged(items.map { it.toListEntry() }, hasMore = items.size >= size && result.last != true)
    }

    /** Collections search page (GET api/v1/collections, native search + library_id). */
    suspend fun searchCollectionsPage(
        term: String,
        libraryId: String? = null,
        page: Int = 0,
        size: Int = 20,
    ): Paged<SampleData.ListEntry> {
        val api = sessionManager.clients.value?.collections
            ?: return Paged(emptyList(), hasMore = false)
        val result = api.getCollections(
            search = term.ifBlank { null },
            libraryId = libraryId?.let { listOf(it) },
            page = page,
            size = size,
        ).awaitBody() ?: return Paged(emptyList(), hasMore = false)
        val items = result.content.orEmpty()
        return Paged(items.map { it.toListEntry() }, hasMore = items.size >= size && result.last != true)
    }

    // ---- 仪表盘分区「更多」页：同一数据源的分页（原作 DashboardSectionDetailView pageSize 50）----

    /** 继续阅读（进行中书籍）分页；kmrs 服务器可带 recentReadingCutoff（对齐 iOS keepReading）。 */
    suspend fun keepReadingPage(
        page: Int = 0,
        size: Int = 50,
        libraryIds: List<String>? = null,
        readDateAfter: java.time.Instant? = null,
    ): Paged<Book> {
        val api = listSearchApi() ?: return Paged(emptyList(), false)
        val condition = combinedCondition(
            listOfNotNull(
                leafCondition("readStatus", "is", "IN_PROGRESS"),
                readDateAfter?.let { dateLeafCondition("readDate", "after", it.toString()) },
                libraryIds?.takeIf { it.isNotEmpty() }?.let(::libraryCondition),
            ),
        )
        val result = api.books(
            searchBody(condition = condition),
            page = page,
            size = size,
            sort = listOf("readProgress.readDate,desc"),
        ).awaitBody() ?: return Paged(emptyList(), false)
        return result.toBookPage(size)
    }

    /** 最近阅读的书籍分页（readStatus READ，sort readProgress.readDate,desc；对齐 iOS recentlyReadBooks）。 */
    suspend fun recentlyReadBooksPage(
        page: Int = 0,
        size: Int = 50,
        libraryIds: List<String>? = null,
    ): Paged<Book> {
        val api = listSearchApi() ?: return Paged(emptyList(), false)
        val condition = combinedCondition(
            listOfNotNull(
                leafCondition("readStatus", "is", "READ"),
                libraryIds?.takeIf { it.isNotEmpty() }?.let(::libraryCondition),
            ),
        )
        val result = api.books(
            searchBody(condition = condition),
            page = page,
            size = size,
            sort = listOf("readProgress.readDate,desc"),
        ).awaitBody() ?: return Paged(emptyList(), false)
        return result.toBookPage(size)
    }

    /** 最近发布的书籍分页（releaseDate after(now-1月)，sort metadata.releaseDate,desc；对齐 iOS recentlyReleasedBooks）。 */
    suspend fun recentlyReleasedBooksPage(
        page: Int = 0,
        size: Int = 50,
        libraryIds: List<String>? = null,
    ): Paged<Book> {
        val api = listSearchApi() ?: return Paged(emptyList(), false)
        val after = java.time.ZonedDateTime.now().minusMonths(1)
        val condition = combinedCondition(
            listOfNotNull(
                dateLeafCondition(
                    "releaseDate", "after",
                    after.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                ),
                libraryIds?.takeIf { it.isNotEmpty() }?.let(::libraryCondition),
            ),
        )
        val result = api.books(
            searchBody(condition = condition),
            page = page,
            size = size,
            sort = listOf("metadata.releaseDate,desc"),
        ).awaitBody() ?: return Paged(emptyList(), false)
        return result.toBookPage(size)
    }

    /** 待读（ondeck）分页。 */
    suspend fun onDeckPage(
        page: Int = 0,
        size: Int = 50,
        libraryIds: List<String>? = null,
    ): Paged<Book> {
        val api = sessionManager.clients.value?.books ?: return Paged(emptyList(), false)
        val result = api.getBooksOnDeck(
            libraryId = libraryIds?.takeIf { it.isNotEmpty() },
            page = page,
            size = size,
        ).awaitBody() ?: return Paged(emptyList(), false)
        return result.toBookPage(size)
    }

    /** 最近新增书籍分页（有库范围时与 iOS 一样改走 POST books/list + createdDate desc）。 */
    suspend fun latestBooksPage(
        page: Int = 0,
        size: Int = 50,
        libraryIds: List<String>? = null,
    ): Paged<Book> {
        if (libraryIds.isNullOrEmpty()) {
            val api = sessionManager.clients.value?.books ?: return Paged(emptyList(), false)
            val result = api.getBooksLatest(page = page, size = size).awaitBody()
                ?: return Paged(emptyList(), false)
            return result.toBookPage(size)
        }
        val api = listSearchApi() ?: return Paged(emptyList(), false)
        val result = api.books(
            searchBody(condition = libraryCondition(libraryIds)),
            page = page,
            size = size,
            sort = listOf("createdDate,desc"),
        ).awaitBody() ?: return Paged(emptyList(), false)
        return result.toBookPage(size)
    }

    /** 最近新增系列分页（GET /series/new；对齐 iOS recentlyAddedSeries，不是 latest）。 */
    suspend fun latestSeriesPage(
        page: Int = 0,
        size: Int = 50,
        libraryIds: List<String>? = null,
    ): Paged<Series> {
        val api = sessionManager.clients.value?.series ?: return Paged(emptyList(), false)
        val result = api.getSeriesNew(
            libraryId = libraryIds?.takeIf { it.isNotEmpty() },
            page = page,
            size = size,
        ).awaitBody() ?: return Paged(emptyList(), false)
        return result.toSeriesPage(size)
    }

    /** 最近更新系列分页（GET /series/updated）。 */
    suspend fun recentlyUpdatedSeriesPage(
        page: Int = 0,
        size: Int = 50,
        libraryIds: List<String>? = null,
    ): Paged<Series> {
        val api = sessionManager.clients.value?.series ?: return Paged(emptyList(), false)
        val result = api.getSeriesUpdated(
            libraryId = libraryIds?.takeIf { it.isNotEmpty() },
            page = page,
            size = size,
        ).awaitBody() ?: return Paged(emptyList(), false)
        return result.toSeriesPage(size)
    }

    // ---- 库浏览筛选（BrowseOptionsSheet → POST 手写条件，对齐 iOS buildCondition）----

    /** 系列浏览：库范围+筛选+排序真实作用于 POST series/list；失败返回 null。 */
    suspend fun browseSeriesPage(
        libraryIds: List<String>?,
        options: BookBrowseOptions,
        page: Int = 0,
        size: Int = 50,
    ): Paged<Series>? {
        val api = listSearchApi() ?: return null
        val body = searchBody(
            condition = combinedCondition(seriesConditions(libraryIds, options)),
        )
        val result = api.series(body, page = page, size = size, sort = listOf(seriesSort(options)))
            .awaitBody() ?: return null
        return result.toSeriesPage(size)
    }

    /** 书籍浏览：库范围+筛选+排序真实作用于 POST books/list；失败返回 null。 */
    suspend fun browseBooksPage(
        libraryIds: List<String>?,
        options: BookBrowseOptions,
        page: Int = 0,
        size: Int = 50,
    ): Paged<Book>? {
        val api = listSearchApi() ?: return null
        val body = searchBody(
            condition = combinedCondition(bookConditions(libraryIds, options)),
        )
        val result = api.books(body, page = page, size = size, sort = listOf(bookSort(options)))
            .awaitBody() ?: return null
        return result.toBookPage(size)
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

    private fun PageSeriesDto.toSeriesPage(size: Int): Paged<Series> {
        val items = content.orEmpty()
        return Paged(
            items.map { it.toUiSeries() },
            hasMore = items.size >= size && last != true,
            totalElements = totalElements,
        )
    }

    private fun PageBookDto.toBookPage(size: Int): Paged<Book> {
        val items = content.orEmpty()
        return Paged(
            items.map { it.toUiBook() },
            hasMore = items.size >= size && last != true,
            totalElements = totalElements,
        )
    }
}
