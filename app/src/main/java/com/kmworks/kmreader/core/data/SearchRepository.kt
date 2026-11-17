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
        libraryIds: List<String>? = null,
        options: BookBrowseOptions? = null,
        page: Int = 0,
        size: Int = 30,
    ): Paged<Series> {
        val api = listSearchApi() ?: return Paged(emptyList(), hasMore = false)
        val result = api.series(
            searchBody(
                // When options exist they stack with full-text search (mirrors the filter semantics of the iOS search tab)
                condition = if (options != null) {
                    combinedCondition(seriesConditions(libraryIds, options))
                } else {
                    libraryIds?.takeIf { it.isNotEmpty() }?.let { libraryCondition(it) }
                },
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
        libraryIds: List<String>? = null,
        options: BookBrowseOptions? = null,
        page: Int = 0,
        size: Int = 30,
    ): Paged<Book> {
        val api = listSearchApi() ?: return Paged(emptyList(), hasMore = false)
        val result = api.books(
            searchBody(
                condition = if (options != null) {
                    combinedCondition(bookConditions(libraryIds, options))
                } else {
                    libraryIds?.takeIf { it.isNotEmpty() }?.let { libraryCondition(it) }
                },
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
        libraryIds: List<String>? = null,
        page: Int = 0,
        size: Int = 20,
    ): Paged<SampleData.ListEntry> {
        val api = sessionManager.clients.value?.readlists
            ?: return Paged(emptyList(), hasMore = false)
        val result = api.getReadLists(
            search = term.ifBlank { null },
            libraryId = libraryIds,
            page = page,
            size = size,
        ).awaitBody() ?: return Paged(emptyList(), hasMore = false)
        val items = result.content.orEmpty()
        return Paged(items.map { it.toListEntry() }, hasMore = items.size >= size && result.last != true)
    }

    /** Collections search page (GET api/v1/collections, native search + library_id). */
    suspend fun searchCollectionsPage(
        term: String,
        libraryIds: List<String>? = null,
        page: Int = 0,
        size: Int = 20,
    ): Paged<SampleData.ListEntry> {
        val api = sessionManager.clients.value?.collections
            ?: return Paged(emptyList(), hasMore = false)
        val result = api.getCollections(
            search = term.ifBlank { null },
            libraryId = libraryIds,
            page = page,
            size = size,
        ).awaitBody() ?: return Paged(emptyList(), hasMore = false)
        val items = result.content.orEmpty()
        return Paged(items.map { it.toListEntry() }, hasMore = items.size >= size && result.last != true)
    }

    // ---- Dashboard section "More" pages: pagination over the same data source (original DashboardSectionDetailView pageSize 50) ----

    /** Continue-reading (in-progress books) paged; kmrs servers may carry recentReadingCutoff (mirrors iOS keepReading). */
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

    /** Recently read books paged (readStatus READ, sort readProgress.readDate,desc; mirrors iOS recentlyReadBooks). */
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

    /** Recently released books paged (releaseDate after(now-1month), sort metadata.releaseDate,desc; mirrors iOS recentlyReleasedBooks). */
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

    /** On-deck (to read) paged. */
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

    /** Recently added books paged (with a library scope it switches to POST books/list + createdDate desc, same as iOS). */
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

    /** Recently added series paged (GET /series/new; mirrors iOS recentlyAddedSeries — not latest). */
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
            // Mirrors iOS: exclude oneshot one-shots
            oneshot = false,
        ).awaitBody() ?: return Paged(emptyList(), false)
        return result.toSeriesPage(size)
    }

    /** Recently updated series paged (GET /series/updated). */
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
            // Mirrors iOS: exclude oneshot one-shots
            oneshot = false,
        ).awaitBody() ?: return Paged(emptyList(), false)
        return result.toSeriesPage(size)
    }

    // ---- Library browse filters (BrowseOptionsSheet → POST handwritten conditions, mirrors iOS buildCondition) ----

    /** Series browse: library scope + filters + sort applied to POST series/list; null on failure. */
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

    /** Book browse: library scope + filters + sort applied to POST books/list; null on failure. */
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

    /** Book filter conditions (condition keys and operators mirror iOS BookSearch.buildCondition). */
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
        if (options.oneshot == TriState.Exclude) {
            conditions += flagLeafCondition("oneshot", "isFalse")
        }
        if (options.deleted == TriState.Include) {
            conditions += flagLeafCondition("deleted", "isTrue")
        }
        if (options.deleted == TriState.Exclude) {
            conditions += flagLeafCondition("deleted", "isFalse")
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
        notableEntitiesCondition(options)?.let { conditions += it }
        return conditions
    }

    /** Series filter conditions (the author condition does not apply to series; other keys match the book ones; series-specific metadata dimensions mirror the original SeriesSearch). */
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
        if (options.oneshot == TriState.Exclude) {
            conditions += flagLeafCondition("oneshot", "isFalse")
        }
        if (options.deleted == TriState.Include) {
            conditions += flagLeafCondition("deleted", "isTrue")
        }
        if (options.deleted == TriState.Exclude) {
            conditions += flagLeafCondition("deleted", "isFalse")
        }
        tagCondition(options)?.let { conditions += it }
        // Series metadata dimensions: multiple values in one dimension become anyOf is (mirrors the original publisher/genre/… branches)
        if (options.publishers.isNotEmpty()) {
            conditions += anyOfCondition(options.publishers.map { leafCondition("publisher", "is", it) })
        }
        if (options.genres.isNotEmpty()) {
            conditions += anyOfCondition(options.genres.map { leafCondition("genre", "is", it) })
        }
        if (options.ageRatings.isNotEmpty()) {
            conditions += anyOfCondition(options.ageRatings.map { leafCondition("ageRating", "is", it) })
        }
        if (options.languages.isNotEmpty()) {
            conditions += anyOfCondition(options.languages.map { leafCondition("language", "is", it) })
        }
        if (options.releaseYears.isNotEmpty()) {
            conditions += anyOfCondition(options.releaseYears.map { leafCondition("releaseYear", "is", it) })
        }
        if (options.seriesStatuses.isNotEmpty()) {
            conditions += anyOfCondition(options.seriesStatuses.map { leafCondition("seriesStatus", "is", it.apiName) })
        }
        notableEntitiesCondition(options)?.let { conditions += it }
        return conditions
    }

    /** Notable entity condition: one notableEntity is {kind,name} leaf per entity; ∧ = allOf, ∨ = anyOf (mirrors the original notableEntities branch). */
    private fun notableEntitiesCondition(options: BookBrowseOptions): JsonObject? {
        if (options.notableEntities.isEmpty()) return null
        val leaves = options.notableEntities.map { notableEntityCondition(it.kind, it.name) }
        return if (options.notableEntitiesAllMatch) allOfCondition(leaves) else anyOfCondition(leaves)
    }

    /** Read-status condition: included statuses become anyOf is, excluded ones each get an isNot (mirrors the original). */
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

    /** ReadStatus → Komga API value (UNREAD/IN_PROGRESS/READ). */
    private fun readStatusApiName(status: com.kmworks.kmreader.data.ReadStatus): String =
        when (status) {
            com.kmworks.kmreader.data.ReadStatus.Unread -> "UNREAD"
            com.kmworks.kmreader.data.ReadStatus.InProgress -> "IN_PROGRESS"
            com.kmworks.kmreader.data.ReadStatus.Read -> "READ"
        }

    /** Tag condition: ∧ = allOf(tag is), ∨ = anyOf (mirrors the original tag branch). */
    private fun tagCondition(options: BookBrowseOptions): JsonObject? {
        if (options.tags.isEmpty()) return null
        val leaves = options.tags.map { leafCondition("tag", "is", it) }
        return if (options.tagLogicAnd) allOfCondition(leaves) else anyOfCondition(leaves)
    }

    /** Book sort mapping (Komga books/list sort fields); keys are stable sort-field ids from BookSortFields. */
    private fun bookSort(options: BookBrowseOptions): String {
        val field = when (options.sortField) {
            "title" -> "metadata.titleSort"
            "release_date" -> "metadata.releaseDate"
            "added_date" -> "createdDate"
            "updated_date" -> "lastModifiedDate"
            "file_size" -> "fileSize"
            "page_count" -> "media.pagesCount"
            else -> "metadata.numberSort"
        }
        return "$field,${if (options.sortAscending) "asc" else "desc"}"
    }

    /** Series sort mapping (Komga series/list fields; fields without a match fall back to booksCount/titleSort). */
    private fun seriesSort(options: BookBrowseOptions): String {
        val field = when (options.sortField) {
            "release_date" -> "booksMetadata.releaseDate"
            "added_date" -> "createdDate"
            "updated_date" -> "lastModifiedDate"
            "file_size", "page_count" -> "booksCount"
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
