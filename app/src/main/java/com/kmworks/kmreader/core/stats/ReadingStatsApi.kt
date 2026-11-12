package com.kmworks.kmreader.core.stats

import kotlinx.serialization.Serializable
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * kmrs 私有阅读统计端点（stock Komga 404；对齐 iOS ReadingStatsService）。
 * DTO 全可选，服务端字段缺失时容错为 null。
 */
internal interface ReadingStatsApi {

    @GET("api/v1/stats/reading/summary")
    fun summary(@Query("libraryId") libraryId: String? = null): Call<ReadingStatsSummaryResponse>

    @GET("api/v1/stats/reading/activity")
    fun activity(
        @Query("libraryId") libraryId: String? = null,
        @Query("tzOffsetMinutes") tzOffsetMinutes: Int? = null,
    ): Call<ReadingStatsActivityResponse>

    @GET("api/v1/stats/reading/tops")
    fun tops(@Query("libraryId") libraryId: String? = null): Call<ReadingStatsTopsResponse>
}

@Serializable
data class ReadingStatsSummaryResponse(
    val summary: ReadingStatsSummaryDto? = null,
    val statusDistribution: List<ReadingStatsItemDto>? = null,
    val generatedAt: String? = null,
)

@Serializable
data class ReadingStatsSummaryDto(
    val totalBooks: Int? = null,
    val booksStartedReading: Int? = null,
    val booksCompletedReading: Int? = null,
    val totalPagesRead: Int? = null,
    val averagePagesPerBook: Int? = null,
    val readingDays: Int? = null,
    val estimatedReadingHours: Double? = null,
    val lastReadAt: String? = null,
)

@Serializable
data class ReadingStatsItemDto(
    val name: String? = null,
    val value: Double? = null,
)

@Serializable
data class ReadingStatsActivityResponse(
    val weekdayDistribution: List<Int>? = null,
    val hourlyDistribution: List<Int>? = null,
    val readingTimeSeries: List<ReadingStatsTimePointDto>? = null,
)

@Serializable
data class ReadingStatsTimePointDto(
    val name: String? = null,
    val value: Double? = null,
    val dateString: String? = null,
)

@Serializable
data class ReadingStatsTopsResponse(
    val topAuthors: List<ReadingStatsItemDto>? = null,
    val topGenres: List<ReadingStatsItemDto>? = null,
    val topTags: List<ReadingStatsItemDto>? = null,
    val genreDistribution: List<ReadingStatsItemDto>? = null,
    val tagDistribution: List<ReadingStatsItemDto>? = null,
)
