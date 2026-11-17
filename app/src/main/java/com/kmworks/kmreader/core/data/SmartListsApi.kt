package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.PageBookDto
import com.kmworks.kmreader.core.api.model.PageSeriesDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Smart list DTO (kmrs private endpoint, not covered by the openapi spec). */
@Serializable
data class SmartListDto(
    val id: String,
    val name: String,
    val summary: String = "",
    val ownerId: String = "",
    val target: String = "BOOK",
    val visibility: String = "PRIVATE",
    val sharedWithUserIds: List<String> = emptyList(),
    val search: JsonObject? = null,
    val createdDate: String = "",
    val lastModifiedDate: String = "",
)

/** Page wrapper (only content is read; every other field is ignored). */
@Serializable
data class SmartListPageDto(
    val content: List<SmartListDto> = emptyList(),
)

/** Create body (name/summary/target/search; admin fields such as visibility to be wired later). */
@Serializable
data class SmartListCreateDto(
    val name: String,
    val summary: String,
    val target: String,
    val search: JsonObject,
)

/** Update body; when search is null the whole key is omitted, protecting filters the editor cannot express. */
@Serializable
data class SmartListUpdateDto(
    val name: String,
    val summary: String,
    val search: JsonObject? = null,
)

/**
 * Handwritten Retrofit interface for the kmrs smart-lists endpoints
 * (not present in the stock Komga OpenAPI spec; stock servers return 404).
 */
interface SmartListsApi {

    @GET("api/v1/smart-lists")
    fun getSmartLists(
        @Query("unpaged") unpaged: Boolean? = null,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
    ): Call<SmartListPageDto>

    @GET("api/v1/smart-lists/{id}")
    fun getSmartList(@Path("id") id: String): Call<SmartListDto>

    @POST("api/v1/smart-lists")
    fun createSmartList(@Body body: SmartListCreateDto): Call<SmartListDto>

    @PATCH("api/v1/smart-lists/{id}")
    fun updateSmartList(
        @Path("id") id: String,
        @Body body: SmartListUpdateDto,
    ): Call<Unit>

    @DELETE("api/v1/smart-lists/{id}")
    fun deleteSmartList(@Path("id") id: String): Call<Unit>

    /** Matched content (book target); body is the BookSearch JSON document, passed through as stored server-side. */
    @POST("api/v1/smart-lists/{id}/books")
    fun getSmartListBooks(
        @Path("id") id: String,
        @Body search: JsonObject,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
        @Query("sort") sort: List<String>? = null,
    ): Call<PageBookDto>

    /** Matched content (series target); body is the SeriesSearch JSON document. */
    @POST("api/v1/smart-lists/{id}/series")
    fun getSmartListSeries(
        @Path("id") id: String,
        @Body search: JsonObject,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
        @Query("sort") sort: List<String>? = null,
    ): Call<PageSeriesDto>
}
