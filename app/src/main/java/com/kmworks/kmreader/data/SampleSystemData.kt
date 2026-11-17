package com.kmworks.kmreader.data

/** Custom font (mirrors CustomFontDisplayItem). */
data class CustomFont(val id: Int, val name: String, val fileName: String, val sizeLabel: String)

/** Reading stats summary (mirrors ReadingStatsSummary). */
data class ReadingStatsSummary(
    val totalPages: Int,
    val totalMinutes: Int,
    val booksCompleted: Int,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val readingDays: Int,
    val avgPagesPerBook: Int,
    val lastReadLabel: String,
    /** Number of volumes started (server summary.booksStartedReading). */
    val booksStarted: Int = 0,
    /** Total volumes in the library (server summary.totalBooks). */
    val totalBooks: Int = 0,
    /** Last read date yyyy-MM-dd (used for card formatting). */
    val lastReadDate: String? = null,
)

/** Single heatmap day (mirrors ReadingStatsTimePoint). */
data class HeatmapDay(val date: String, val pages: Int)

/** Fake data for fonts / servers / stats. */
object SampleSystemData {

    val customFonts: List<CustomFont> = listOf(
        CustomFont(1, "LXGW WenKai", "LXGWWenKai-Regular.ttf", "18.2 MB"),
        CustomFont(2, "Source Han Serif SC", "SourceHanSerifSC-Regular.otf", "24.7 MB"),
        CustomFont(3, "Smiley Sans", "SmileySans-Oblique.ttf", "6.3 MB"),
    )
}
