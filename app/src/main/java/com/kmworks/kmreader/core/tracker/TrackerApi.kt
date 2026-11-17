package com.kmworks.kmreader.core.tracker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Tracking preferences (mirrors TrackerPreferences). */
@Serializable
data class TrackerPreferencesDto(
    val libraries: List<String> = emptyList(),
    val defaultTracker: String? = null,
)

/** Provider account state (mirrors TrackerAccount; snake_case keys). */
@Serializable
data class TrackerAccountDto(
    @SerialName("logged_in") val loggedIn: Boolean = false,
    val username: String? = null,
)

/** Series binding (mirrors TrackerLink). */
@Serializable
data class TrackerLinkDto(
    val seriesId: String = "",
    val provider: String = "",
    val trackId: String = "",
    val title: String? = null,
    val trackMode: String = "auto",
    val chapterOffset: Int = 0,
    val createdDate: String? = null,
    val lastModifiedDate: String? = null,
)

/** Binding write body (mirrors the JSON fields of upsertLink). */
@Serializable
data class TrackerLinkWriteDto(
    val provider: String,
    val trackId: String,
    val title: String? = null,
    val trackMode: String,
    val chapterOffset: Int,
)

/** Binding group (mirrors TrackerLinkGroup). */
@Serializable
data class TrackerLinkGroupDto(
    val seriesId: String = "",
    val links: List<TrackerLinkDto> = emptyList(),
)

/** Binding counts (mirrors TrackerLinkCounts). */
@Serializable
data class TrackerLinkCountsDto(
    val series: Int = 0,
    val providers: Map<String, Int> = emptyMap(),
)

/** Paged bindings (mirrors TrackerLinkPage, keeping only the used fields). */
@Serializable
data class TrackerLinkPageDto(
    val content: List<TrackerLinkGroupDto> = emptyList(),
    val totalElements: Int = 0,
    val totalPages: Int = 0,
    val last: Boolean = false,
    val counts: TrackerLinkCountsDto = TrackerLinkCountsDto(),
)

/** Remote search result (mirrors TrackerSearchItem). */
@Serializable
data class TrackerSearchItemDto(
    val id: String = "",
    val title: String = "",
    val coverUrl: String? = null,
    val description: String? = null,
    val tracked: Boolean = false,
    val mediaType: String? = null,
    val series: Boolean = false,
    val url: String? = null,
)

/** Remote read state (mirrors TrackerRemoteState, all fields optional). */
@Serializable
data class TrackerRemoteStateDto(
    val score: Int? = null,
    val status: String? = null,
    val lastReadChapter: Double? = null,
    val lastReadVolume: Int? = null,
    val totalChapters: Int? = null,
    val totalVolumes: Int? = null,
    val startReadDate: String? = null,
    val finishReadDate: String? = null,
)

/** Ledger entry of an update (mirrors TrackerLedgerItem; updatedAt is epoch seconds). */
@Serializable
data class TrackerLedgerItemDto(
    val provider: String = "",
    val trackId: String = "",
    val title: String? = null,
    val url: String? = null,
    val coverUrl: String? = null,
    val updatedAt: Double = 0.0,
)

/** Paged ledger (mirrors Page<TrackerLedgerItem>, keeping only the used fields). */
@Serializable
data class TrackerLedgerPageDto(
    val content: List<TrackerLedgerItemDto> = emptyList(),
    val totalElements: Int = 0,
    val totalPages: Int = 0,
)

/** kmrs private Tracker endpoints (mirrors iOS TrackerService; handwritten, built via LocalApiFactory). */
interface TrackerApi {

    @GET("api/v1/trackers/preferences")
    fun getPreferences(): Call<TrackerPreferencesDto>

    @PUT("api/v1/trackers/preferences")
    fun updatePreferences(@Body body: TrackerPreferencesDto): Call<TrackerPreferencesDto>

    @GET("api/v1/trackers/oauth/{provider}/status")
    fun getAccount(@Path("provider") provider: String): Call<TrackerAccountDto>

    @POST("api/v1/trackers/oauth/{provider}/logout")
    fun logout(@Path("provider") provider: String): Call<ResponseBody>

    @GET("api/v1/trackers/links")
    fun getLinks(
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("provider") provider: String?,
    ): Call<TrackerLinkPageDto>

    @GET("api/v1/trackers/links/{seriesId}")
    fun getSeriesLinks(@Path("seriesId") seriesId: String): Call<List<TrackerLinkDto>>

    @PUT("api/v1/trackers/links/{seriesId}")
    fun upsertLink(
        @Path("seriesId") seriesId: String,
        @Body body: TrackerLinkWriteDto,
    ): Call<TrackerLinkDto>

    @DELETE("api/v1/trackers/links/{seriesId}/{provider}")
    fun deleteLink(
        @Path("seriesId") seriesId: String,
        @Path("provider") provider: String,
    ): Call<ResponseBody>

    @GET("api/v1/trackers/{provider}/search")
    fun search(
        @Path("provider") provider: String,
        @Query("name") name: String,
        @Query("nsfw") nsfw: Boolean,
        @Query("language") language: String?,
    ): Call<List<TrackerSearchItemDto>>

    @GET("api/v1/trackers/{provider}/state")
    fun getState(
        @Path("provider") provider: String,
        @Query("trackId") trackId: String,
    ): Call<TrackerRemoteStateDto>

    @POST("api/v1/trackers/{provider}/update")
    fun updateState(
        @Path("provider") provider: String,
        @Body body: JsonObject,
    ): Call<ResponseBody>

    @GET("api/v1/trackers/ledger")
    fun getLedger(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): Call<TrackerLedgerPageDto>
}
