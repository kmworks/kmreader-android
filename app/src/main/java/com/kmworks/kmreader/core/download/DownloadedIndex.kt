package com.kmworks.kmreader.core.download

/**
 * Badge state drawn on series/book cards, mirroring iOS `DownloadStatus`/`SeriesDownloadStatus`:
 * [None] nothing downloaded, [Queued] the task waits for a worker slot, [Paused] the download is paused,
 * [Downloading] a worker is running, [Partial] some books of a series are downloaded, [Full] complete,
 * [Failed] the last attempt failed.
 */
enum class DownloadMark { None, Queued, Downloading, Paused, Partial, Full, Failed }

/** Queue status → badge state. Completed downloads never reach this: the file scan decides [DownloadMark.Full]. */
fun DownloadTaskUi.Status.toDownloadMark(): DownloadMark = when (this) {
    DownloadTaskUi.Status.Downloading -> DownloadMark.Downloading
    DownloadTaskUi.Status.Queued -> DownloadMark.Queued
    DownloadTaskUi.Status.Paused -> DownloadMark.Paused
    DownloadTaskUi.Status.Failed -> DownloadMark.Failed
}

/**
 * Offline aggregate of one series: how many books are complete, plus the in-flight/failed counts that drive
 * the aggregate badge while nothing is complete yet.
 *
 * A zero [total] means the series size was never recorded (single-book download), so the card can only show
 * the partially-downloaded mark.
 */
data class SeriesDownloadAggregate(
    val downloaded: Int = 0,
    val total: Int = 0,
    val downloading: Int = 0,
    val queued: Int = 0,
    val paused: Int = 0,
    val failed: Int = 0,
) {
    val mark: DownloadMark
        get() = when {
            downloaded > 0 && total > 0 && downloaded >= total -> DownloadMark.Full
            // A partially downloaded series keeps its partial mark even while the rest is queued.
            downloaded > 0 -> DownloadMark.Partial
            downloading > 0 -> DownloadMark.Downloading
            queued > 0 -> DownloadMark.Queued
            paused > 0 -> DownloadMark.Paused
            failed > 0 -> DownloadMark.Failed
            else -> DownloadMark.None
        }
}

/**
 * Snapshot of download state, refreshed by [DownloadRepository] after queue transitions and rescans.
 * Card composables read it from a CompositionLocal, so no screen has to probe the disk per row.
 */
data class DownloadedIndex(
    /** UI stable ids ([com.kmworks.kmreader.core.data.StableIds.book]) of fully downloaded books. */
    val books: Set<Int> = emptySet(),
    /**
     * Stable ids with an unfinished task → its badge state ([DownloadMark.Paused], [DownloadMark.Downloading]
     * or [DownloadMark.Failed]). A completed download always wins in [bookMark].
     */
    val tasks: Map<Int, DownloadMark> = emptyMap(),
    /** Series DTO id → aggregate of its books' download state. */
    val series: Map<String, SeriesDownloadAggregate> = emptyMap(),
) {
    fun bookMark(bookStableId: Int): DownloadMark =
        if (bookStableId in books) DownloadMark.Full
        else tasks[bookStableId] ?: DownloadMark.None

    fun seriesMark(seriesDtoId: String?): DownloadMark =
        seriesDtoId?.let { series[it]?.mark } ?: DownloadMark.None
}
