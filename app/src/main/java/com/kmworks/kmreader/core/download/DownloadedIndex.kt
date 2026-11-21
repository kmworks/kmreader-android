package com.kmworks.kmreader.core.download

/** Downloaded marker drawn on series/book cards (mirrors iOS DownloadStatus/SeriesDownloadStatus icons). */
enum class DownloadMark { None, Partial, Full }

/**
 * Offline aggregate of one series: [downloaded] complete books out of [total] known books.
 * A zero [total] means the series size was never recorded (single-book download), so the card can only show
 * the partially-downloaded mark.
 */
data class SeriesDownloadAggregate(
    val downloaded: Int = 0,
    val total: Int = 0,
) {
    val mark: DownloadMark
        get() = when {
            downloaded <= 0 -> DownloadMark.None
            total > 0 && downloaded >= total -> DownloadMark.Full
            else -> DownloadMark.Partial
        }
}

/**
 * Snapshot of completed downloads, refreshed by [DownloadRepository] after queue transitions and rescans.
 * Card composables read it from a CompositionLocal, so no screen has to probe the disk per row.
 */
data class DownloadedIndex(
    /** UI stable ids ([com.kmworks.kmreader.core.data.StableIds.book]) of fully downloaded books. */
    val books: Set<Int> = emptySet(),
    /** Series DTO id → aggregate of its downloaded books. */
    val series: Map<String, SeriesDownloadAggregate> = emptyMap(),
) {
    fun bookMark(bookStableId: Int): DownloadMark =
        if (bookStableId in books) DownloadMark.Full else DownloadMark.None

    fun seriesMark(seriesDtoId: String?): DownloadMark =
        seriesDtoId?.let { series[it]?.mark } ?: DownloadMark.None
}
