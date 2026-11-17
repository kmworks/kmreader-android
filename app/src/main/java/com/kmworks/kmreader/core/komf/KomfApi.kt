package com.kmworks.kmreader.core.komf

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** kmrs private komf proxy endpoints (admin-only, 404 on stock Komga). */
internal interface KomfApi {

    @GET("api/v1/komf/search")
    fun search(
        @Query("name") name: String,
        @Query("libraryId") libraryId: String,
        @Query("seriesId") seriesId: String,
    ): Call<List<KomfSearchResultDto>>

    @POST("api/v1/komf/identify")
    fun identify(@Body body: KomfIdentifyRequestDto): Call<KomfMetadataJobResponseDto>

    @POST("api/v1/komf/match/library/{libraryId}/series/{seriesId}")
    fun match(
        @Path("libraryId") libraryId: String,
        @Path("seriesId") seriesId: String,
    ): Call<KomfMetadataJobResponseDto>

    /** reset returns an empty response (not a job), mirroring the original resetSeries. */
    @POST("api/v1/komf/reset/library/{libraryId}/series/{seriesId}")
    fun reset(
        @Path("libraryId") libraryId: String,
        @Path("seriesId") seriesId: String,
    ): Call<ResponseBody>

    @GET("api/v1/komf/jobs/{id}")
    fun job(@Path("id") id: String): Call<KomfJobDto>
}
