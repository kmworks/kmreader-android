package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Re-reads individual rows after a server-side change announced only as ids.
 *
 * SSE payloads carry no field values, so a screen that wants to keep showing what it has must ask the server what
 * the row looks like now, by id, and swap it in place. That is one small request per visible row instead of a page
 * reload: the screen keeps its scroll position, its loaded pages and its other rows untouched.
 *
 * Requests go out in small batches so a burst of events (or a large collection) cannot open dozens of sockets at
 * once. Ids that fail to load are simply missing from the result; the caller decides whether that means "drop the
 * row" or "fall back to a full reload".
 */
@Singleton
class ContentRowRefresher @Inject constructor(
    private val detailRepository: DetailRepository,
) {

    /** Rows keyed by their stable id (what the UI states hold); ids that failed to load are absent. */
    suspend fun books(dtoIds: Set<String>): Map<Int, Book> =
        fetch(dtoIds) { id -> detailRepository.bookDetail(id)?.toUiBook() }.associateBy { it.id }

    suspend fun series(dtoIds: Set<String>): Map<Int, Series> =
        fetch(dtoIds) { id -> detailRepository.seriesDetail(id)?.toUiSeries() }.associateBy { it.id }

    private suspend fun <T> fetch(dtoIds: Set<String>, fetchOne: suspend (String) -> T?): List<T> {
        if (dtoIds.isEmpty()) return emptyList()
        return coroutineScope {
            dtoIds.chunked(BATCH_SIZE)
                .flatMap { chunk -> chunk.map { id -> async { runCatching { fetchOne(id) }.getOrNull() } } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private companion object {
        /** Enough to fill a screen's first rows in one round trip without opening a socket per id. */
        const val BATCH_SIZE = 8
    }
}
