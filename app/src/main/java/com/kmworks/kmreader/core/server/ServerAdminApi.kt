package com.kmworks.kmreader.core.server

import com.kmworks.kmreader.core.api.model.DirectoryListingDto
import com.kmworks.kmreader.core.api.model.DirectoryRequestDto
import com.kmworks.kmreader.core.api.model.PageHistoricalEventDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Server administration endpoints (mirrors iOS ServerStatsService / ManagementService / HistoryService /
 * FilesystemService / ReleasesService).
 *
 * Two families live here:
 *  - kmrs private endpoints, absent from the OpenAPI spec and 404 on stock Komga:
 *    `api/v1/stats/libraries`, `api/v1/stats/server`, `api/v1/update-check` and the extended
 *    `actuator/` set (sessions/scheduledtasks/logfile/shutdown).
 *  - OpenAPI endpoints whose generated interface only covers a subset (tasks DELETE, history GET,
 *    filesystem POST, actuator/info) plus the v2 user-session endpoints missing from the spec.
 *
 * Every response DTO is optional-tolerant: kmrs omits fields it cannot compute (e.g. sidecars for
 * non-admin callers) and older servers predate some fields entirely.
 */
internal interface ServerAdminApi {

    /** kmrs private: per-library content counts (admin gets sidecar counts too). */
    @GET("api/v1/stats/libraries")
    fun statsLibraries(): Call<StatsLibrariesDto>

    /** kmrs private: server snapshot (tasks / process / totals / disk), admin only. */
    @GET("api/v1/stats/server")
    fun statsServer(): Call<StatsServerDto>

    @GET("actuator/info")
    fun actuatorInfo(): Call<ActuatorInfoDto>

    @GET("actuator/health")
    fun actuatorHealth(): Call<ActuatorHealthDto>

    @GET("actuator/metrics/{name}")
    fun actuatorMetric(
        @Path("name") name: String,
        /** Repeatable `tag=key:value` filter (mirrors iOS ManagementService.getMetric(tag:)). */
        @Query("tag") tags: List<String> = emptyList(),
    ): Call<ActuatorMetricDto>

    @GET("actuator/metrics")
    fun actuatorMetricNames(): Call<ActuatorMetricNamesDto>

    @GET("actuator/sessions")
    fun actuatorSessions(@Query("username") username: String? = null): Call<ActuatorSessionsDto>

    @DELETE("actuator/sessions/{id}")
    fun deleteActuatorSession(@Path("id") id: String): Call<ResponseBody>

    @GET("actuator/scheduledtasks")
    fun actuatorScheduledTasks(): Call<ActuatorScheduledTasksDto>

    /** Plain-text log tail; fetched as ResponseBody so no JSON decoding is attempted. */
    @GET("actuator/logfile")
    fun actuatorLogfile(): Call<ResponseBody>

    @POST("actuator/shutdown")
    fun shutdown(): Call<ResponseBody>

    /** Task queue management only exposes "empty the queue" (the queue itself arrives over SSE). */
    @DELETE("api/v1/tasks")
    fun emptyTaskQueue(): Call<ResponseBody>

    /** kmrs private: daily GitHub release check. */
    @GET("api/v1/update-check")
    fun updateCheck(): Call<UpdateCheckDto>

    @GET("api/v2/users/me/sessions")
    fun mySessions(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): Call<SessionPageDto>

    @GET("api/v2/users/sessions")
    fun userSessions(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): Call<UserSessionPageDto>

    @DELETE("api/v2/users/me/sessions/{id}")
    fun deleteMySession(@Path("id") id: String): Call<ResponseBody>

    @DELETE("api/v2/users/sessions/{id}")
    fun deleteUserSession(@Path("id") id: String): Call<ResponseBody>

    @GET("api/v1/history")
    fun history(
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: List<String> = listOf(HISTORY_SORT),
    ): Call<PageHistoricalEventDto>

    /** Server-side directory browser (admin only); used by the library add/edit flows on iOS. */
    @POST("api/v1/filesystem")
    fun directoryListing(@Body body: DirectoryRequestDto): Call<DirectoryListingDto>

    companion object {
        /** Mirrors iOS HistoryService: newest events first. */
        val HISTORY_SORT = "timestamp,desc"
    }
}

// region kmrs stats (crates/komga-server/src/dto/stats.rs)

/** GET /api/v1/stats/libraries */
@Serializable
data class StatsLibrariesDto(
    val libraries: List<LibraryStatsDto>? = null,
    val total: LibraryStatsTotalDto? = null,
)

@Serializable
data class LibraryStatsDto(
    val libraryId: String = "",
    val name: String = "",
    val series: Long? = null,
    val books: Long? = null,
    val fileSize: Long? = null,
    /** Sidecar counts are admin-only on kmrs; other callers get the field omitted. */
    val sidecars: Long? = null,
)

@Serializable
data class LibraryStatsTotalDto(
    val series: Long? = null,
    val books: Long? = null,
    val fileSize: Long? = null,
    val sidecars: Long? = null,
)

/** GET /api/v1/stats/server */
@Serializable
data class StatsServerDto(
    val tasks: ServerTaskStatsDto? = null,
    val process: ServerProcessStatsDto? = null,
    val totals: ServerTotalsDto? = null,
    val disk: ServerDiskStatsDto? = null,
)

@Serializable
data class ServerTaskStatsDto(
    val queueSize: Long? = null,
    val types: List<TaskTypeStatsDto>? = null,
)

@Serializable
data class TaskTypeStatsDto(
    @SerialName("type") val type: String = "",
    val queued: Long? = null,
    val executions: Long? = null,
    val totalTimeMs: Long? = null,
    val maxTimeMs: Long? = null,
    val failures: Long? = null,
    val lastFailure: TaskFailureStatsDto? = null,
)

@Serializable
data class TaskFailureStatsDto(
    val taskId: String? = null,
    val taskType: String? = null,
    val finishedAt: String? = null,
    val error: String? = null,
)

@Serializable
data class ServerProcessStatsDto(
    val startTime: String? = null,
    val uptimeSeconds: Long? = null,
    /** Percent of the whole CPU capacity (100 = every core busy). */
    val cpuUsage: Double? = null,
    val memoryBytes: Long? = null,
)

@Serializable
data class ServerTotalsDto(
    val libraries: Long? = null,
    val series: Long? = null,
    val books: Long? = null,
    val fileSize: Long? = null,
    val collections: Long? = null,
    val readlists: Long? = null,
    val sidecars: Long? = null,
)

@Serializable
data class ServerDiskStatsDto(
    val free: Long? = null,
    val total: Long? = null,
)

// endregion

// region actuator

/** GET /actuator/info (Spring-compatible shape; kmrs fills git/build/os). */
@Serializable
data class ActuatorInfoDto(
    val git: ActuatorGitDto? = null,
    val build: ActuatorBuildDto? = null,
    val os: ActuatorOsDto? = null,
)

@Serializable
data class ActuatorGitDto(
    val branch: String? = null,
    val commit: ActuatorGitCommitDto? = null,
)

@Serializable
data class ActuatorGitCommitDto(
    val id: String? = null,
    val time: String? = null,
)

@Serializable
data class ActuatorBuildDto(
    val artifact: String? = null,
    val name: String? = null,
    val version: String? = null,
    val group: String? = null,
)

@Serializable
data class ActuatorOsDto(
    val name: String? = null,
    val version: String? = null,
    val arch: String? = null,
)

/**
 * GET /actuator/health. Components (db + diskSpace) are only present for admins;
 * `details` is schema-free JSON, so it stays a [JsonObject] and is read field by field.
 */
@Serializable
data class ActuatorHealthDto(
    val status: String? = null,
    val components: ActuatorHealthComponentsDto? = null,
)

@Serializable
data class ActuatorHealthComponentsDto(
    val db: ActuatorHealthDbDto? = null,
    val diskSpace: ActuatorHealthComponentDto? = null,
)

@Serializable
data class ActuatorHealthDbDto(
    val status: String? = null,
    val components: Map<String, ActuatorHealthComponentDto>? = null,
)

@Serializable
data class ActuatorHealthComponentDto(
    val status: String? = null,
    val details: JsonObject? = null,
)

/** GET /actuator/metrics/{name} */
@Serializable
data class ActuatorMetricDto(
    val name: String? = null,
    val description: String? = null,
    val baseUnit: String? = null,
    val measurements: List<ActuatorMetricMeasurementDto>? = null,
    val availableTags: List<ActuatorAvailableTagDto>? = null,
)

@Serializable
data class ActuatorMetricMeasurementDto(
    val statistic: String? = null,
    val value: Double? = null,
)

@Serializable
data class ActuatorAvailableTagDto(
    val tag: String? = null,
    val values: List<String>? = null,
)

@Serializable
data class ActuatorMetricNamesDto(
    val names: List<String>? = null,
)

/** GET /actuator/sessions */
@Serializable
data class ActuatorSessionsDto(
    val sessions: List<ActuatorSessionDto>? = null,
)

@Serializable
data class ActuatorSessionDto(
    val id: String = "",
    val creationTime: String? = null,
    val lastAccessedTime: String? = null,
    val maxInactiveInterval: Long? = null,
    val expired: Boolean? = null,
)

/** GET /actuator/scheduledtasks (only fixedRate carries entries on kmrs). */
@Serializable
data class ActuatorScheduledTasksDto(
    val fixedRate: List<ActuatorScheduledTaskDto>? = null,
)

@Serializable
data class ActuatorScheduledTaskDto(
    val runnable: ActuatorScheduledTaskRunnableDto? = null,
    val initialDelay: Long? = null,
    val interval: Long? = null,
)

@Serializable
data class ActuatorScheduledTaskRunnableDto(
    val target: String? = null,
)

// endregion

// region update check (kmrs private)

/** GET /api/v1/update-check */
@Serializable
data class UpdateCheckDto(
    val enabled: Boolean = false,
    val checkedAt: String? = null,
    val update: UpdateInfoDto? = null,
)

@Serializable
data class UpdateInfoDto(
    val version: String = "",
    val url: String? = null,
    val releaseDate: String? = null,
)

// endregion

// region sessions (api/v2, absent from the OpenAPI spec)

/** Paged envelope shared by the v2 session endpoints (only the fields the UI needs). */
@Serializable
data class SessionPageDto(
    val content: List<MySessionDto>? = null,
    val totalElements: Long? = null,
    val totalPages: Int? = null,
    val last: Boolean? = null,
)

@Serializable
data class MySessionDto(
    val id: String = "",
    val creationTime: String? = null,
    val lastAccessedTime: String? = null,
    /** True for the session issuing the request (cannot be kicked). */
    val current: Boolean? = null,
)

@Serializable
data class UserSessionPageDto(
    val content: List<UserSessionDto>? = null,
    val totalElements: Long? = null,
    val totalPages: Int? = null,
    val last: Boolean? = null,
)

@Serializable
data class UserSessionDto(
    val id: String = "",
    val userId: String? = null,
    val email: String? = null,
    val creationTime: String? = null,
    val lastAccessedTime: String? = null,
)

// endregion
