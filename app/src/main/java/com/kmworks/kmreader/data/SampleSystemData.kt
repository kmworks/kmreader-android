package com.kmworks.kmreader.data

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

/** Custom font (mirrors CustomFontDisplayItem). */
data class CustomFont(val id: Int, val name: String, val fileName: String, val sizeLabel: String)

/** Server account card (mirrors SettingsServerCardView). */
data class ServerAccount(
    val id: Int,
    val name: String,
    val url: String,
    val user: String,
    val serverVersion: String,
    val active: Boolean,
)

/** Log line (mirrors SettingsLogsView rows). */
data class LogLine(val time: String, val level: String, val message: String)

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
)

/** Single heatmap day (mirrors ReadingStatsTimePoint). */
data class HeatmapDay(val date: String, val pages: Int)

/** Fake data for fonts / servers / logs / stats. */
object SampleSystemData {

    val customFonts: List<CustomFont> = listOf(
        CustomFont(1, "霞鹜文楷", "LXGWWenKai-Regular.ttf", "18.2 MB"),
        CustomFont(2, "思源宋体", "SourceHanSerifSC-Regular.otf", "24.7 MB"),
        CustomFont(3, "得意黑", "SmileySans-Oblique.ttf", "6.3 MB"),
    )

    val serverAccounts: List<ServerAccount> = listOf(
        ServerAccount(1, "主服务器", "https://komga.example.com", "demo@example.com", "v1.14.1", active = true),
        ServerAccount(2, "家中 NAS", "https://nas.local:8443", "reader", "v1.10.4", active = false),
    )

    val logLines: List<LogLine> = listOf(
        LogLine("20:41:07", "INFO", "SSE 连接已建立"),
        LogLine("20:41:09", "API", "GET /api/v1/series 200 (182 ms)"),
        LogLine("20:42:31", "INFO", "下载完成：葬送的芙莉莲 第 12 卷"),
        LogLine("20:43:02", "WARN", "SSE 心跳超时，准备重连"),
        LogLine("20:43:04", "ERROR", "下载失败：王者天下 第 73 卷（网络连接中断）"),
        LogLine("20:43:10", "INFO", "阅读进度已同步：怪兽8号 第 4 卷 第 87 页"),
    )

    /** SSE placeholder state (mirrors SettingsSSEView). */
    const val sseConnected = true
    const val sseLastEvent = "阅读进度更新 · 2 分钟前"

    val readingStats = ReadingStatsSummary(
        totalPages = 12840,
        totalMinutes = 9640,
        booksCompleted = 37,
        currentStreakDays = 16,
        longestStreakDays = 42,
        readingDays = 203,
        avgPagesPerBook = 212,
        lastReadLabel = "今天",
    )

    /** 52 weeks x 7 days, Monday first, trailing future days = null (mirrors ReadingPagesHeatmapWeek). */
    val heatmapWeeks: List<List<HeatmapDay?>> by lazy { buildHeatmapWeeks() }

    val maxHeatmapPages: Int
        get() = heatmapWeeks.flatten().filterNotNull().maxOfOrNull { it.pages } ?: 1

    private fun buildHeatmapWeeks(): List<List<HeatmapDay?>> {
        val end = LocalDate.now()
        val start = end.minusWeeks(51).with(DayOfWeek.MONDAY)
        return (0 until 52).map { week ->
            (0 until 7).map { day ->
                val date = start.plusDays((week * 7 + day).toLong())
                if (date.isAfter(end)) {
                    null
                } else {
                    val rnd = Random(date.toEpochDay())
                    val pages = if (rnd.nextFloat() < 0.35f) 0 else rnd.nextInt(8, 160)
                    HeatmapDay(date.toString(), pages)
                }
            }
        }
    }
}
