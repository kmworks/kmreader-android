package com.kmworks.kmreader.core.stats

import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.data.ImageCachePaths
import com.kmworks.kmreader.core.data.ListSearchApi
import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.data.anyOfCondition
import com.kmworks.kmreader.core.data.awaitBody
import com.kmworks.kmreader.core.data.leafCondition
import com.kmworks.kmreader.core.data.searchBody
import com.kmworks.kmreader.core.db.CachedBookEntity
import com.kmworks.kmreader.core.db.CatalogDao
import com.kmworks.kmreader.core.network.SessionManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Offline reading stats aggregated from the local catalog cache.
 *
 * Mirrors iOS `ReadingStatsService.fetchLocalPayload`, which falls back to GRDB when the server has
 * no `readingStats` capability (stock Komga) or the app is offline. The same endpoints' shapes are
 * produced locally, so the ViewModel/UI need no separate rendering path.
 *
 * Caveats worth surfacing in the UI: the cache only holds the books this device has browsed (paged
 * lists + dashboard seeds), so the numbers describe the cached catalog, not the whole library;
 * rows carry no `libraryId`, so library filtering is not offered in this mode. Genre/tag rankings
 * need series metadata, which is not cached: they are fetched on demand (`series/list`, batched by
 * series id) for the series holding completed books, and stay empty when offline or on failure.
 */
@Singleton
class LocalReadingStats @Inject constructor(
    private val dao: CatalogDao,
    private val sessionManager: SessionManager,
) {

    /** True when a cached catalog exists for the active server (else the UI keeps its placeholder). */
    suspend fun hasData(): Boolean {
        val key = instanceKey() ?: return false
        return dao.allBooks(key).isNotEmpty()
    }

    suspend fun summary(): ReadingStatsSummaryResponse? {
        val books = books() ?: return null
        if (books.isEmpty()) return null
        val started = books.count { it.progress?.let { progress -> progress > 0f } == true }
        val completed = books.count(::isCompleted)
        val pagesRead = books.sumOf(::pagesFor)
        val days = readingDays(books)
        val today = LocalDate.now()
        return ReadingStatsSummaryResponse(
            totalBooks = books.size,
            booksStarted = started,
            booksCompleted = completed,
            pagesRead = pagesRead,
            averagePagesPerBook = if (started == 0) 0 else pagesRead / started,
            readingDays = days.size,
            lastReadAt = books.mapNotNull { it.lastReadAtMs }.maxOrNull()
                ?.let { Instant.ofEpochMilli(it).toString() },
            currentStreakDays = currentStreak(days, today),
            longestStreakDays = longestStreak(days),
            statusDistribution = listOf(
                ReadingStatsItemDto("read", completed.toDouble()),
                ReadingStatsItemDto("inProgress", (started - completed).coerceAtLeast(0).toDouble()),
                ReadingStatsItemDto("unread", (books.size - started).coerceAtLeast(0).toDouble()),
            ),
            generatedAt = Instant.now().toString(),
        )
    }

    suspend fun activity(): ReadingStatsActivityResponse? {
        val books = books() ?: return null
        if (books.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val weekday = IntArray(7)
        val hourly = IntArray(24)
        books.forEach { book ->
            // Js weekday index 0 = Sunday, matching the server's weekdayDistribution slots.
            val time = book.lastReadAtMs?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return@forEach
            weekday[time.dayOfWeek.value % 7] += 1
            hourly[time.hour] += 1
        }
        val points = books
            .mapNotNull { book -> dateOf(book.lastReadAtMs, zone)?.let { date -> date to book } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (date, dayBooks) ->
                ReadingStatsTimePointDto(
                    date = date,
                    pagesRead = dayBooks.sumOf(::pagesFor).toDouble(),
                    booksCompleted = dayBooks.count(::isCompleted).toDouble(),
                )
            }
        return ReadingStatsActivityResponse(
            weekdayDistribution = weekday.toList(),
            hourlyDistribution = hourly.toList(),
            readingTimeSeries = points,
            generatedAt = Instant.now().toString(),
        )
    }

    /**
     * Rankings. [withMetadata] = false skips the series-metadata request and leaves the genre/tag
     * rankings empty, so the UI can render the author ranking immediately and fill genres/tags in a
     * second pass.
     */
    suspend fun tops(withMetadata: Boolean = true): ReadingStatsTopsResponse? {
        val books = books() ?: return null
        if (books.isEmpty()) return null
        val authors = books
            .mapNotNull { it.author?.takeIf { name -> name.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(TOP_LIMIT)
            .map { ReadingStatsItemDto(it.key, it.value.toDouble()) }
        val series = if (withMetadata) seriesMetadata(completedSeriesIds(books)) else emptyList()
        // The server counts each genre/tag once per series with a completed book; mirror that here.
        val genres = countedNames(series, SERIES_GENRE)
        val tags = countedNames(series, SERIES_TAG)
        return ReadingStatsTopsResponse(
            topAuthors = authors,
            topGenres = topItems(genres, TOP_LIMIT),
            topTags = topItems(tags, TOP_LIMIT),
            genreDistribution = withOther(genres, DISTRIBUTION_LIMIT),
            tagDistribution = withOther(tags, DISTRIBUTION_LIMIT),
            generatedAt = Instant.now().toString(),
        )
    }

    /**
     * Series ids of the series holding at least one completed cached book (the server's
     * `completed_series` scope). Books are linked to series by title because the cached book rows
     * carry only `seriesTitle`, not the series id.
     */
    private suspend fun completedSeriesIds(books: List<CachedBookEntity>): List<String> {
        val key = instanceKey() ?: return emptyList()
        val seriesRows = dao.allSeries(key)
        if (seriesRows.isEmpty()) return emptyList()
        val titleToId = seriesRows.associate { it.title to it.dtoId }
        return books
            .filter(::isCompleted)
            .mapNotNull { book ->
                titleToId[book.seriesTitle] ?: titleToId[book.title]
            }
            .distinct()
    }

    /**
     * Series metadata for the requested ids via `POST /series/list` (anyOf of `seriesId is` leaves,
     * batched). No session means offline: nothing is requested and the rankings stay empty. A failed
     * batch is skipped, so a flaky network only drops those series.
     */
    private suspend fun seriesMetadata(ids: List<String>): List<SeriesDto> {
        if (ids.isEmpty()) return emptyList()
        val clients = sessionManager.clients.value ?: return emptyList()
        val api = LocalApiFactory.create(clients, ListSearchApi::class.java)
        val found = mutableMapOf<String, SeriesDto>()
        ids.chunked(METADATA_BATCH_SIZE).forEach { chunk ->
            val condition = anyOfCondition(chunk.map { leafCondition("seriesId", "is", it) })
            val page = runCatching { api.series(searchBody(condition), unpaged = true).awaitBody() }
                .getOrNull() ?: return@forEach
            page.content.orEmpty().forEach { series -> found[series.id] = series }
        }
        return found.values.toList()
    }

    private fun countedNames(series: List<SeriesDto>, field: String): List<Pair<String, Int>> {
        val counts = mutableMapOf<String, Int>()
        series.forEach { entry ->
            val names = if (field == SERIES_GENRE) entry.metadata.genres else entry.metadata.tags
            names.forEach { name ->
                if (name.isNotBlank()) counts[name] = (counts[name] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    private fun topItems(counts: List<Pair<String, Int>>, limit: Int): List<ReadingStatsItemDto> =
        counts.take(limit).map { ReadingStatsItemDto(it.first, it.second.toDouble()) }

    /** Same shape as the server: first `limit` entries plus an "Other" bucket summing the rest. */
    private fun withOther(counts: List<Pair<String, Int>>, limit: Int): List<ReadingStatsItemDto> {
        if (counts.size <= limit) return topItems(counts, limit)
        val head = topItems(counts, limit)
        val rest = counts.drop(limit).sumOf { it.second }
        return head + ReadingStatsItemDto(OTHER_LABEL, rest.toDouble())
    }

    private suspend fun books(): List<CachedBookEntity>? {
        val key = instanceKey() ?: return null
        return dao.allBooks(key)
    }

    private fun instanceKey(): String? =
        sessionManager.clients.value?.cacheNamespace?.let(ImageCachePaths::sanitize)

    private fun isCompleted(book: CachedBookEntity): Boolean =
        book.progress?.let { it >= 1f } == true

    /** Pages read for one book: the full page count when completed, progress x pageCount otherwise. */
    private fun pagesFor(book: CachedBookEntity): Int {
        if (book.pageCount <= 0) return 0
        if (isCompleted(book)) return book.pageCount
        val progress = book.progress ?: return 0
        return (progress * book.pageCount).roundToInt().coerceIn(0, book.pageCount)
    }

    private fun dateOf(epochMs: Long?, zone: ZoneId): String? =
        epochMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }

    private fun readingDays(books: List<CachedBookEntity>): List<LocalDate> {
        val zone = ZoneId.systemDefault()
        return books
            .mapNotNull { book -> book.lastReadAtMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } }
            .distinct()
            .sorted()
    }

    private fun longestStreak(days: List<LocalDate>): Int {
        if (days.isEmpty()) return 0
        var longest = 1
        var run = 1
        for (index in 1 until days.size) {
            run = if (ChronoUnit.DAYS.between(days[index - 1], days[index]) == 1L) run + 1 else 1
            if (run > longest) longest = run
        }
        return longest
    }

    /** Streak ending today or yesterday (a day still in progress keeps yesterday's run alive). */
    private fun currentStreak(days: List<LocalDate>, today: LocalDate): Int {
        if (days.isEmpty()) return 0
        val last = days.last()
        if (ChronoUnit.DAYS.between(last, today) > 1L) return 0
        var streak = 1
        for (index in days.size - 1 downTo 1) {
            if (ChronoUnit.DAYS.between(days[index - 1], days[index]) != 1L) break
            streak++
        }
        return streak
    }

    private companion object {
        /** Mirrors the server's top-N rankings. */
        const val TOP_LIMIT = 10

        /** Mirrors the server's distribution cutoff (first N entries, then an "Other" bucket). */
        const val DISTRIBUTION_LIMIT = 17

        /** Series ids per `series/list` request (anyOf of `seriesId is` leaves). */
        const val METADATA_BATCH_SIZE = 50

        /** Stand-in for the server's "Other" bucket label (same literal it returns). */
        const val OTHER_LABEL = "Other"

        const val SERIES_GENRE = "genre"
        const val SERIES_TAG = "tag"
    }
}
