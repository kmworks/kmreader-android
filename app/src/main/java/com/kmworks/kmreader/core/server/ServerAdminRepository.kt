package com.kmworks.kmreader.core.server

import com.kmworks.kmreader.core.api.model.ApiKeyDto
import com.kmworks.kmreader.core.api.model.ApiKeyRequestDto
import com.kmworks.kmreader.core.api.model.AuthenticationActivityDto
import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.DirectoryListingDto
import com.kmworks.kmreader.core.api.model.DirectoryRequestDto
import com.kmworks.kmreader.core.api.model.HistoricalEventDto
import com.kmworks.kmreader.core.api.model.LibraryCreationDto
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.api.model.LibraryUpdateDto
import com.kmworks.kmreader.core.api.model.PageHashCreationDto
import com.kmworks.kmreader.core.api.model.PageHashKnownDto
import com.kmworks.kmreader.core.api.model.PageHashMatchDto
import com.kmworks.kmreader.core.api.model.PageHashUnknownDto
import com.kmworks.kmreader.core.data.ListSearchApi
import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.data.Paged
import com.kmworks.kmreader.core.data.allOfCondition
import com.kmworks.kmreader.core.data.anyOfCondition
import com.kmworks.kmreader.core.data.awaitBody
import com.kmworks.kmreader.core.data.awaitSuccess
import com.kmworks.kmreader.core.data.combinedCondition
import com.kmworks.kmreader.core.data.leafCondition
import com.kmworks.kmreader.core.data.libraryCondition
import com.kmworks.kmreader.core.data.searchBody
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.ResponseBody

/** Who can a session-management screen act on (mirrors iOS ServerSessionScope). */
enum class SessionScope { Own, All }

/**
 * A session row. The v2 endpoints exist only on kmrs; the actuator fallback is shared with stock Komga,
 * so [native] decides which delete endpoint the kick uses.
 */
data class SessionEntry(
    val id: String,
    val creationTime: String? = null,
    val lastAccessedTime: String? = null,
    val current: Boolean = false,
    /** The actuator listing reports expired sessions; the native listings only return live ones. */
    val expired: Boolean = false,
    val email: String? = null,
    val userId: String? = null,
    val native: Boolean = false,
)

/** One page of sessions plus its provenance; [supported] is false when neither API family answered. */
data class SessionsPage(
    val entries: List<SessionEntry> = emptyList(),
    val hasMore: Boolean = false,
    val native: Boolean = false,
    val supported: Boolean = true,
)

/** Volume/count metrics for one library; null means the source omitted that number. */
data class LibraryMetricValues(
    val fileSize: Long? = null,
    val seriesCount: Long? = null,
    val booksCount: Long? = null,
) {
    val isEmpty: Boolean get() = fileSize == null && seriesCount == null && booksCount == null
}

/** Per-library metrics plus the server-wide total (mirrors iOS LibraryMetricValues). */
data class LibraryMetrics(
    val byLibrary: Map<String, LibraryMetricValues> = emptyMap(),
    val total: LibraryMetricValues? = null,
) {
    /** True when no source answered, so the UI hides the caption instead of showing zeros. */
    val isEmpty: Boolean get() = byLibrary.values.all { it.isEmpty } && total?.isEmpty != false
}

/**
 * Server administration data source (mirrors iOS ManagementService + ServerStatsService +
 * HistoryService + FilesystemService + ReleasesService).
 *
 * Capability gating mirrors iOS: the kmrs-private families (stats, update-check) return null when
 * `server/info` did not advertise them, so the UI hides those sections instead of showing errors;
 * stock Komga answers 404 for the actuator extensions and everything degrades to null/false.
 */
@Singleton
class ServerAdminRepository @Inject constructor(
    private val sessionManager: SessionManager,
    private val serverProfileStore: ServerProfileStore,
    private val configStore: ServerConfigStore,
) {

    private fun api(): ServerAdminApi? =
        sessionManager.clients.value?.let { LocalApiFactory.create(it, ServerAdminApi::class.java) }

    fun hasFeature(feature: ServerFeature): Boolean = serverProfileStore.hasFeature(feature)

    /** Current account is an administrator (every management screen is gated on this, like iOS). */
    suspend fun isAdmin(): Boolean = configStore.config.first()?.isAdmin == true

    suspend fun username(): String = configStore.config.first()?.username.orEmpty()

    /** Last library-metric snapshot; avoids re-querying every actuator tag on each reload. */
    private var metricsCache: Pair<Long, LibraryMetrics>? = null

    // region kmrs stats

    /** Native server identity (`GET api/v1/server/info`); also the capability probe used for gating. */
    suspend fun serverInfo(): ServerInfoDto? =
        sessionManager.clients.value?.serverApi?.getServerInfo()?.awaitBody()

    suspend fun librariesStats(): StatsLibrariesDto? {
        if (!hasFeature(ServerFeature.ServerStats)) return null
        return api()?.statsLibraries()?.awaitBody()
    }

    suspend fun serverStats(): StatsServerDto? {
        if (!hasFeature(ServerFeature.ServerStats)) return null
        return api()?.statsServer()?.awaitBody()
    }

    // endregion

    // region library metrics

    /**
     * Per-library size/series/books metrics (mirrors iOS LibraryMetricsLoader.refreshMetrics):
     * the kmrs stats endpoint first, then the stock-Komga actuator meters carrying a `library` tag.
     * Returns an empty snapshot when neither source has numbers.
     */
    suspend fun libraryMetrics(): LibraryMetrics {
        val cached = metricsCache
        if (cached != null && System.currentTimeMillis() - cached.first < METRICS_TTL_MS) {
            return cached.second
        }
        val metrics = loadLibraryMetrics()
        metricsCache = System.currentTimeMillis() to metrics
        return metrics
    }

    private suspend fun loadLibraryMetrics(): LibraryMetrics {
        librariesStats()?.let { stats ->
            val byLibrary = stats.libraries.orEmpty().associate { library ->
                library.libraryId to LibraryMetricValues(
                    fileSize = library.fileSize,
                    seriesCount = library.series,
                    booksCount = library.books,
                )
            }
            val total = stats.total?.let { value ->
                LibraryMetricValues(
                    fileSize = value.fileSize,
                    seriesCount = value.series,
                    booksCount = value.books,
                )
            }
            return LibraryMetrics(byLibrary = byLibrary, total = total)
        }
        // Stock Komga: the meters exist only for admins and expose library ids as a tag value.
        if (!isAdmin()) return LibraryMetrics()
        val filesize = metric(BOOKS_FILESIZE_METRIC) ?: return LibraryMetrics()
        val libraryIds = filesize.availableTags
            ?.firstOrNull { it.tag == METRIC_LIBRARY_TAG }
            ?.values
            .orEmpty()
            .filter { it.isNotBlank() }
        if (libraryIds.isEmpty()) return LibraryMetrics()
        val byLibrary = libraryIds.associateWith { libraryId ->
            LibraryMetricValues(
                fileSize = metric(BOOKS_FILESIZE_METRIC, METRIC_LIBRARY_TAG to libraryId)
                    .measurement(METRIC_VALUE_STATISTIC)?.toLong(),
                seriesCount = metric(SERIES_METRIC, METRIC_LIBRARY_TAG to libraryId)
                    .measurement(METRIC_VALUE_STATISTIC)?.toLong(),
                booksCount = metric(BOOKS_METRIC, METRIC_LIBRARY_TAG to libraryId)
                    .measurement(METRIC_VALUE_STATISTIC)?.toLong(),
            )
        }
        val total = LibraryMetricValues(
            fileSize = byLibrary.values.mapNotNull { it.fileSize }.takeIf { it.isNotEmpty() }?.sum(),
            seriesCount = byLibrary.values.mapNotNull { it.seriesCount }.takeIf { it.isNotEmpty() }?.sum(),
            booksCount = byLibrary.values.mapNotNull { it.booksCount }.takeIf { it.isNotEmpty() }?.sum(),
        )
        return LibraryMetrics(byLibrary = byLibrary, total = total)
    }

    // endregion

    // region actuator

    suspend fun actuatorInfo(): ActuatorInfoDto? = api()?.actuatorInfo()?.awaitBody()

    suspend fun actuatorHealth(): ActuatorHealthDto? = api()?.actuatorHealth()?.awaitBody()

    /** Metric lookup used as the stock-Komga fallback for the stats/server numbers. */
    suspend fun metric(name: String, vararg tags: Pair<String, String>): ActuatorMetricDto? =
        api()?.actuatorMetric(name, tags.map { (key, value) -> "$key:$value" })?.awaitBody()

    suspend fun actuatorScheduledTasks(): List<ActuatorScheduledTaskDto> =
        api()?.actuatorScheduledTasks()?.awaitBody()?.fixedRate.orEmpty()

    /** Log tail as text; the endpoint answers text/plain, so no JSON decoding happens. */
    suspend fun logfile(): String? = api()?.actuatorLogfile()?.awaitBody()?.stringOrNull()

    suspend fun shutdown(): Boolean = api()?.shutdown()?.awaitSuccess() ?: false

    // endregion

    // region tasks

    /** Drops every queued task (the queue itself is pushed over SSE). */
    suspend fun emptyTaskQueue(): Boolean = api()?.emptyTaskQueue()?.awaitSuccess() ?: false

    /**
     * Stock-Komga fallback for the per-type execution numbers: the untagged `komga.tasks.execution`
     * meter lists every known type in its `type` tag, then each type's timer and failure counter are
     * queried with that tag (mirrors iOS ServerTasksView.loadActuatorTaskMetrics).
     *
     * Returns the same shape the kmrs stats family uses, so the screen renders one model. Actuator
     * timer values are seconds, converted to milliseconds here.
     */
    suspend fun actuatorTaskStats(): List<TaskTypeStatsDto> {
        val execution: ActuatorMetricDto = metric(TASK_EXECUTION_METRIC) ?: return emptyList()
        val types = execution.availableTags.orEmpty()
            .firstOrNull { it.tag == TASK_TYPE_TAG }?.values.orEmpty()
            .filter { it.isNotBlank() }
        if (types.isEmpty()) return emptyList()

        return types.map { type ->
            val timer = metric(TASK_EXECUTION_METRIC, TASK_TYPE_TAG to type)
            val failure = metric(TASK_FAILURE_METRIC, TASK_TYPE_TAG to type)
            TaskTypeStatsDto(
                type = type,
                executions = timer.measurement("COUNT")?.toLong(),
                totalTimeMs = timer.measurement("TOTAL_TIME")?.times(1000)?.toLong(),
                maxTimeMs = timer.measurement("MAX")?.times(1000)?.toLong(),
                failures = failure.measurement("COUNT")?.toLong(),
            )
        }.sortedByDescending { it.executions ?: 0L }
    }

    /** Actuator measurement by statistic name ("COUNT", "TOTAL_TIME", "MAX", "VALUE"). */
    private fun ActuatorMetricDto?.measurement(statistic: String): Double? =
        this?.measurements?.firstOrNull { it.statistic == statistic }?.value

    // endregion

    // region update check

    suspend fun updateCheck(): UpdateCheckDto? {
        if (!hasFeature(ServerFeature.UpdateCheck)) return null
        return api()?.updateCheck()?.awaitBody()
    }

    // endregion

    // region sessions

    /**
     * Loads one page of sessions. `Own` needs the kmrs v2 endpoint; `All` falls back to the actuator
     * listing (all sessions, then `?username=` when the server restricts the scope), exactly like iOS.
     */
    suspend fun sessions(scope: SessionScope, page: Int = 0, size: Int = PAGE_SIZE): SessionsPage {
        val api = api() ?: return SessionsPage(supported = false)
        if (scope == SessionScope.Own) {
            // No actuator equivalent for "my sessions": missing here means "unsupported", not an error.
            val own = api.mySessions(page, size).awaitBody() ?: return SessionsPage(supported = false)
            return SessionsPage(
                entries = own.content.orEmpty().map { dto ->
                    SessionEntry(
                        id = dto.id,
                        creationTime = dto.creationTime,
                        lastAccessedTime = dto.lastAccessedTime,
                        current = dto.current == true,
                        native = true,
                    )
                },
                hasMore = own.last == false,
                native = true,
            )
        }
        val all = api.userSessions(page, size).awaitBody()
        if (all != null) {
            return SessionsPage(
                entries = all.content.orEmpty().map { dto ->
                    SessionEntry(
                        id = dto.id,
                        creationTime = dto.creationTime,
                        lastAccessedTime = dto.lastAccessedTime,
                        email = dto.email,
                        userId = dto.userId,
                        native = true,
                    )
                },
                hasMore = all.last == false,
                native = true,
            )
        }
        // Actuator fallback: all sessions first, then scoped to the current user when the server
        // rejects the unscoped listing (iOS retries with ?username= on 404/400).
        val sessions = api.actuatorSessions()?.awaitBody()?.sessions
            ?: api.actuatorSessions(username().takeIf { it.isNotBlank() })?.awaitBody()?.sessions
            ?: return SessionsPage(supported = false)
        return SessionsPage(
            entries = sessions.map { dto ->
                SessionEntry(
                    id = dto.id,
                    creationTime = dto.creationTime,
                    lastAccessedTime = dto.lastAccessedTime,
                    expired = dto.expired == true,
                    native = false,
                )
            },
        )
    }

    suspend fun kickSession(scope: SessionScope, entry: SessionEntry): Boolean {
        val api = api() ?: return false
        return when {
            !entry.native -> api.deleteActuatorSession(entry.id).awaitSuccess()
            scope == SessionScope.Own -> api.deleteMySession(entry.id).awaitSuccess()
            else -> api.deleteUserSession(entry.id).awaitSuccess()
        }
    }

    // endregion

    // region history

    /** Paged server history (admin only; newest first). */
    suspend fun history(page: Int = 0, size: Int = PAGE_SIZE): Paged<HistoricalEventDto> {
        val body = api()?.history(page, size)?.awaitBody()
            ?: return Paged(emptyList(), hasMore = false)
        val items = body.content.orEmpty()
        return Paged(items, hasMore = body.last != true, totalElements = body.totalElements)
    }

    // endregion

    // region filesystem

    /** Server-side directory listing; [path] null starts at the server root. */
    suspend fun directory(path: String?): DirectoryListingDto? {
        val api = api() ?: return null
        return api.directoryListing(DirectoryRequestDto(path = path.orEmpty(), showFiles = false)).awaitBody()
    }

    // endregion

    // region libraries (admin CRUD + maintenance)

    /** All libraries with their full configuration (admin view). */
    suspend fun libraries(): List<LibraryDto> =
        sessionManager.clients.value?.libraries?.getLibraries()?.awaitBody().orEmpty()

    /** Full library configuration (only admins see the scan/hash flags). */
    suspend fun library(id: String): LibraryDto? =
        sessionManager.clients.value?.libraries?.getLibraryById(id)?.awaitBody()

    suspend fun createLibrary(creation: LibraryCreationDto): LibraryDto? =
        sessionManager.clients.value?.libraries?.addLibrary(creation)?.awaitBody()

    suspend fun updateLibrary(id: String, update: LibraryUpdateDto): Boolean =
        sessionManager.clients.value?.libraries?.updateLibraryById(id, update)?.awaitSuccess() ?: false

    suspend fun deleteLibrary(id: String): Boolean =
        sessionManager.clients.value?.libraries?.deleteLibraryById(id)?.awaitSuccess() ?: false

    /** Queue a library scan; [deep] forces a full re-read of every file. */
    suspend fun scanLibrary(id: String, deep: Boolean = false): Boolean =
        sessionManager.clients.value?.libraries?.libraryScan(id, deep)?.awaitSuccess() ?: false

    suspend fun analyzeLibrary(id: String): Boolean =
        sessionManager.clients.value?.libraries?.libraryAnalyze(id)?.awaitSuccess() ?: false

    suspend fun emptyLibraryTrash(id: String): Boolean =
        sessionManager.clients.value?.libraries?.libraryEmptyTrash(id)?.awaitSuccess() ?: false

    suspend fun refreshLibraryMetadata(id: String): Boolean =
        sessionManager.clients.value?.libraries?.libraryRefreshMetadata(id)?.awaitSuccess() ?: false

    // endregion

    // region api keys

    /** API keys of the signed-in account (the key itself is only returned on creation). */
    suspend fun apiKeys(): List<ApiKeyDto> =
        sessionManager.clients.value?.apiKeys?.getApiKeysForCurrentUser()?.awaitBody().orEmpty()

    suspend fun createApiKey(comment: String): ApiKeyDto? =
        sessionManager.clients.value?.apiKeys?.createApiKeyForCurrentUser(ApiKeyRequestDto(comment))
            ?.awaitBody()

    suspend fun deleteApiKey(keyId: String): Boolean =
        sessionManager.clients.value?.apiKeys?.deleteApiKeyByKeyId(keyId)?.awaitSuccess() ?: false

    // endregion

    // region authentication activity

    /** Paged sign-in history of the signed-in account (newest first). */
    suspend fun authenticationActivity(page: Int = 0, size: Int = PAGE_SIZE): Paged<AuthenticationActivityDto> {
        val body = sessionManager.clients.value?.currentUser
            ?.getAuthenticationActivityForCurrentUser(
                page = page,
                size = size,
                sort = listOf(AUTH_ACTIVITY_SORT),
            )
            ?.awaitBody() ?: return Paged(emptyList(), hasMore = false)
        return Paged(
            items = body.content.orEmpty(),
            hasMore = body.last != true,
            totalElements = body.totalElements,
        )
    }

    // endregion

    // region duplicates

    /** Books sharing the same file hash. */
    suspend fun duplicateBooks(page: Int = 0, size: Int = PAGE_SIZE): Paged<BookDto> {
        val body = sessionManager.clients.value?.books
            ?.getBooksDuplicates(page = page, size = size, sort = listOf(DUPLICATE_FILE_SORT))
            ?.awaitBody() ?: return Paged(emptyList(), hasMore = false)
        return Paged(
            items = body.content.orEmpty(),
            hasMore = body.last != true,
            totalElements = body.totalElements,
        )
    }

    /** Duplicate pages already marked as known by an admin. */
    /** Duplicate pages already marked as known by an admin, optionally filtered by action. */
    suspend fun knownPageHashes(
        page: Int = 0,
        size: Int = PAGE_SIZE,
        actions: List<String> = emptyList(),
    ): Paged<PageHashKnownDto> {
        val body = sessionManager.clients.value?.duplicatePages
            ?.getKnownPageHashes(
                action = actions.ifEmpty { null },
                page = page,
                size = size,
                sort = listOf(KNOWN_HASH_SORT),
            )
            ?.awaitBody() ?: return Paged(emptyList(), hasMore = false)
        return Paged(
            items = body.content.orEmpty(),
            hasMore = body.last != true,
            totalElements = body.totalElements,
        )
    }

    /** Duplicate pages detected by the scanner that nobody has triaged yet. */
    suspend fun unknownPageHashes(page: Int = 0, size: Int = PAGE_SIZE): Paged<PageHashUnknownDto> {
        val body = sessionManager.clients.value?.duplicatePages
            ?.getUnknownPageHashes(page = page, size = size, sort = listOf(UNKNOWN_HASH_SORT))
            ?.awaitBody() ?: return Paged(emptyList(), hasMore = false)
        return Paged(
            items = body.content.orEmpty(),
            hasMore = body.last != true,
            totalElements = body.totalElements,
        )
    }

    // endregion

    // region media management (analysis, missing posters, page-hash triage)

    /** Pages flagged by the analyzer (status ERROR/UNSUPPORTED), optionally scoped to libraries. */
    suspend fun mediaAnalysisBooks(
        statuses: List<String>,
        libraryIds: List<String>? = null,
        page: Int = 0,
        size: Int = PAGE_SIZE,
    ): Paged<BookDto> {
        if (statuses.isEmpty()) return Paged(emptyList(), hasMore = false)
        val leaves = statuses.map { leafCondition("mediaStatus", "is", it) }
        val condition = combinedCondition(
            listOfNotNull(
                if (leaves.size == 1) leaves.single() else anyOfCondition(leaves),
                libraryIds?.takeIf { it.isNotEmpty() }?.let(::libraryCondition),
            ),
        )
        return booksPage(searchBody(condition = condition), page, size, listOf(MEDIA_STATUS_SORT))
    }

    /** Books that are readable but still have no selected poster (mirrors iOS getMissingPosterBooks). */
    suspend fun missingPosterBooks(page: Int = 0, size: Int = PAGE_SIZE): Paged<BookDto> {
        val condition = allOfCondition(
            listOf(
                leafCondition("mediaStatus", "is", "READY"),
                buildJsonObject {
                    putJsonObject("poster") {
                        put("operator", "isNot")
                        putJsonObject("value") { put("selected", true) }
                    }
                },
            ),
        )
        return booksPage(searchBody(condition = condition), page, size, emptyList())
    }

    /** Pages of one hash (the sheet shows up to 100 at once, like iOS). */
    suspend fun pageHashMatches(
        hash: String,
        page: Int = 0,
        size: Int = MATCH_PAGE_SIZE,
    ): Paged<PageHashMatchDto> {
        val body = sessionManager.clients.value?.duplicatePages
            ?.getPageHashMatches(hash, page = page, size = size)
            ?.awaitBody() ?: return Paged(emptyList(), hasMore = false)
        return Paged(
            items = body.content.orEmpty(),
            hasMore = body.last != true,
            totalElements = body.totalElements,
        )
    }

    /** Records the triage action for a hash (PUT api/v1/page-hashes). */
    suspend fun updatePageHash(hash: String, size: Long?, action: String): Boolean {
        val api = sessionManager.clients.value?.duplicatePages ?: return false
        val enumAction = PageHashCreationDto.Action.entries.firstOrNull { it.value == action } ?: return false
        return api.createOrUpdateKnownPageHash(
            PageHashCreationDto(action = enumAction, hash = hash, propertySize = size),
        ).awaitSuccess()
    }

    /** Deletes every page matching the hash (POST api/v1/page-hashes/{hash}/delete-all). */
    suspend fun deleteAllPageHashMatches(hash: String): Boolean =
        sessionManager.clients.value?.duplicatePages
            ?.deleteDuplicatePagesByPageHash(hash)
            ?.awaitSuccess() ?: false

    /** Deletes one matched page (POST api/v1/page-hashes/{hash}/delete-match; the server reads bookId + pageNumber only). */
    suspend fun deletePageHashMatch(hash: String, bookId: String, pageNumber: Int): Boolean {
        val api = sessionManager.clients.value?.duplicatePages ?: return false
        val match = PageHashMatchDto(
            bookId = bookId,
            fileName = "",
            fileSize = 0L,
            mediaType = "",
            pageNumber = pageNumber,
            url = "",
        )
        return api.deleteSingleMatchByPageHash(hash, match).awaitSuccess()
    }

    /** Authenticated page thumbnail of a duplicate match (the session image loader adds the credentials). */
    fun pageThumbnailUrl(bookId: String, pageNumber: Int): String? =
        sessionManager.clients.value?.baseUrl
            ?.let { "$it/api/v1/books/$bookId/pages/$pageNumber/thumbnail" }

    /** Authenticated cover thumbnail, used by the analysis lists like the iOS media screens. */
    fun bookThumbnailUrl(bookId: String): String? =
        sessionManager.clients.value?.baseUrl
            ?.let { "$it/api/v1/books/$bookId/thumbnail" }

    private suspend fun booksPage(
        search: JsonObject,
        page: Int,
        size: Int,
        sort: List<String>,
    ): Paged<BookDto> {
        val api = sessionManager.clients.value?.let { LocalApiFactory.create(it, ListSearchApi::class.java) }
            ?: return Paged(emptyList(), hasMore = false)
        val body = api.books(search, page = page, size = size, sort = sort.ifEmpty { null })
            ?.awaitBody() ?: return Paged(emptyList(), hasMore = false)
        return Paged(
            items = body.content.orEmpty(),
            hasMore = body.last != true,
            totalElements = body.totalElements,
        )
    }

    // endregion

    private fun ResponseBody.stringOrNull(): String? = try {
        string()
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Mirrors the iOS session paging pageSize. */
        const val PAGE_SIZE = 20

        /** Komga sorts authentication activity by dateTime descending. */
        const val AUTH_ACTIVITY_SORT = "dateTime,desc"

        /** iOS DuplicateFilesView sorts by file hash so equal hashes land next to each other. */
        const val DUPLICATE_FILE_SORT = "fileHash,asc"

        /** iOS DuplicatePagesKnownView sorts by deleteCount descending. */
        const val KNOWN_HASH_SORT = "deleteCount,desc"

        /** iOS DuplicatePagesUnknownView sorts by matchCount descending. */
        const val UNKNOWN_HASH_SORT = "matchCount,desc"

        /** iOS MediaAnalysisView sorts by media status. */
        const val MEDIA_STATUS_SORT = "media.status,asc"

        /** iOS PageHashMatchesView asks for a single page of 100 matches. */
        const val MATCH_PAGE_SIZE = 100

        /** Actuator meters carrying the per-type task numbers on stock Komga. */
        const val TASK_EXECUTION_METRIC = "komga.tasks.execution"
        const val TASK_FAILURE_METRIC = "komga.tasks.failure"
        const val TASK_TYPE_TAG = "type"

        /** Stock-Komga per-library meters (mirrors iOS LibraryMetricsLoader.actuatorFallbackMetrics). */
        const val BOOKS_FILESIZE_METRIC = "komga.books.filesize"
        const val SERIES_METRIC = "komga.series"
        const val BOOKS_METRIC = "komga.books"
        const val METRIC_LIBRARY_TAG = "library"
        const val METRIC_VALUE_STATISTIC = "VALUE"

        /** Library metrics change slowly; re-query at most every five minutes. */
        const val METRICS_TTL_MS = 5 * 60 * 1000L
    }
}
