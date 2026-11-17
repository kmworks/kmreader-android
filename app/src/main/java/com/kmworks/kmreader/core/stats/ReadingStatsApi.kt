package com.kmworks.kmreader.core.stats

import kotlinx.serialization.Serializable
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * kmrs private reading-stats endpoints (404 on stock Komga; mirrors iOS ReadingStatsService).
 * DTO fields are all optional; missing server-side fields degrade to null.
 * Note: field names map one-to-one to the serde camelCase output of kmrs crates/komga-server/src/dto/stats.rs.
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

/**
 * GET /api/v1/stats/reading/summary — flat structure (no wrapper object).
 * Streaks are computed server-side from historical events (reading_stats::streaks).
 */
@Serializable
data class ReadingStatsSummaryResponse(
    val totalBooks: Int? = null,
    val booksStarted: Int? = null,
    val booksCompleted: Int? = null,
    val pagesRead: Int? = null,
    val averagePagesPerBook: Int? = null,
    val readingDays: Int? = null,
    val lastReadAt: String? = null,
    val currentStreakDays: Int? = null,
    val longestStreakDays: Int? = null,
    val statusDistribution: List<ReadingStatsItemDto>? = null,
    val generatedAt: String? = null,
)

@Serializable
data class ReadingStatsItemDto(
    val name: String? = null,
    val value: Double? = null,
)

/**
 * GET /api/v1/stats/reading/activity。
 * weekdayDistribution has exactly 7 slots with index 0 = Sunday; hourlyDistribution has exactly 24 slots.
 */
@Serializable
data class ReadingStatsActivityResponse(
    val weekdayDistribution: List<Int>? = null,
    val hourlyDistribution: List<Int>? = null,
    val readingTimeSeries: List<ReadingStatsTimePointDto>? = null,
    val generatedAt: String? = null,
)

@Serializable
data class ReadingStatsTimePointDto(
    /** yyyy-MM-dd (UTC, date of the read-progress event). */
    val date: String? = null,
    val pagesRead: Double? = null,
    val booksCompleted: Double? = null,
)

@Serializable
data class ReadingStatsTopsResponse(
    val topAuthors: List<ReadingStatsItemDto>? = null,
    val topGenres: List<ReadingStatsItemDto>? = null,
    val topTags: List<ReadingStatsItemDto>? = null,
    val genreDistribution: List<ReadingStatsItemDto>? = null,
    val tagDistribution: List<ReadingStatsItemDto>? = null,
    val generatedAt: String? = null,
)
