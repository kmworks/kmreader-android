package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.PageBookDto
import com.kmworks.kmreader.core.api.model.PageSeriesDto
import kotlinx.serialization.json.JsonObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * POST books/list 与 series/list 端点（查询体手写 JsonObject）：生成的
 * BookSearch/SeriesSearch 的 condition 字段是全必填 DSL 类型，无法携带手写
 * 条件，故以 JsonObject 直发；响应沿用生成模型。实例经 LocalApiFactory 构建
 * （ApiClients 只读）。
 */
internal interface ListSearchApi {

    @POST("api/v1/books/list")
    fun books(
        @Body search: JsonObject,
        @Query("unpaged") unpaged: Boolean? = null,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
        @Query("sort") sort: List<String>? = null,
    ): Call<PageBookDto>

    @POST("api/v1/series/list")
    fun series(
        @Body search: JsonObject,
        @Query("unpaged") unpaged: Boolean? = null,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
        @Query("sort") sort: List<String>? = null,
    ): Call<PageSeriesDto>
}
