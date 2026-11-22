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

/** Page wrapper (every field except content/last is ignored). */
@Serializable
data class SmartListPageDto(
    val content: List<SmartListDto> = emptyList(),
    val last: Boolean? = null,
)

/** Create body (name/summary/target/search + admin-only sharing fields, omitted when null). */
@Serializable
data class SmartListCreateDto(
    val name: String,
    val summary: String,
    val target: String,
    val search: JsonObject,
    val visibility: String? = null,
    val sharedWithUserIds: List<String>? = null,
)

/** Update body; when search is null the whole key is omitted, protecting filters the editor cannot express. */
@Serializable
data class SmartListUpdateDto(
    val name: String,
    val summary: String,
    val search: JsonObject? = null,
    /** PRIVATE | PUBLIC | SHARED; omitted for non-admins because kmrs rejects them with 403. */
    val visibility: String? = null,
    /** Sharee user ids; required (non-empty) when [visibility] is SHARED. */
    val sharedWithUserIds: List<String>? = null,
)

/** One pickable share target (kmrs exposes the minimal user directory to admins only). */
@Serializable
data class SmartListShareTargetDto(
    val id: String,
    val email: String = "",
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

    /** Admins only (403 otherwise): the minimal user directory used to populate the sharing picker. */
    @GET("api/v1/smart-lists/share-targets")
    fun getSmartListShareTargets(): Call<List<SmartListShareTargetDto>>

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
