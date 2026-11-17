package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.PageBookDto
import com.kmworks.kmreader.core.api.model.PageSeriesDto
import kotlinx.serialization.json.JsonObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * POST books/list and series/list endpoints (query bodies are handwritten JsonObjects): the generated
 * BookSearch/SeriesSearch condition fields are all-required DSL types and cannot carry a handwritten
 * condition, so JsonObject is sent directly; responses reuse the generated models. Instances are built
 * via LocalApiFactory (ApiClients is read-only).
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
