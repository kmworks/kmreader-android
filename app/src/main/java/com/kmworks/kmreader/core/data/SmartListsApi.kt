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

/** Smart list DTO（kmrs 私有端点，openapi spec 未覆盖）。 */
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

/** Page wrapper（只取 content，其余字段忽略）。 */
@Serializable
data class SmartListPageDto(
    val content: List<SmartListDto> = emptyList(),
)

/** Create body（name/summary/target/search；visibility 等管理员字段后续再接）。 */
@Serializable
data class SmartListCreateDto(
    val name: String,
    val summary: String,
    val target: String,
    val search: JsonObject,
)

/** Update body；search 为 null 时整键省略，保护编辑器无法表达的过滤条件。 */
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

    /** 命中内容（书籍目标）；body 为 BookSearch JSON 文档，直接透传服务端存的 search。 */
    @POST("api/v1/smart-lists/{id}/books")
    fun getSmartListBooks(
        @Path("id") id: String,
        @Body search: JsonObject,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
        @Query("sort") sort: List<String>? = null,
    ): Call<PageBookDto>

    /** 命中内容（系列目标）；body 为 SeriesSearch JSON 文档。 */
    @POST("api/v1/smart-lists/{id}/series")
    fun getSmartListSeries(
        @Path("id") id: String,
        @Body search: JsonObject,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
        @Query("sort") sort: List<String>? = null,
    ): Call<PageSeriesDto>
}
