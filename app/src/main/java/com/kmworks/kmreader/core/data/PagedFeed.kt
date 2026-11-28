package com.kmworks.kmreader.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Paging bookkeeping shared by every scrolling list (library browse, dashboard section feeds, collection /
 * read list / smart list detail).
 *
 * The rule it exists to enforce: a reload of the same query keeps the pages already on screen. Reading only
 * page 0 again shrinks the list, and a lazy grid clips its scroll offset to the shorter content, which reads
 * as "it jumped back to where I was before". Callers reload through [reload] instead of re-reading page 0
 * themselves.
 *
 * [reload] mirrors iOS `PaginationState.replaceItems` plus `revalidateWindow`: the pages already loaded are
 * re-read as one window (page 0, size = loaded pages × [pageSize]) and swapped in place, so a reload costs
 * one request instead of one per loaded page. The rows keep their identity across the swap, which is what
 * keeps the scroll position. Every operation takes this feed's lock, so a load-more and a reload can never
 * advance [page]/[hasMore] against each other, whatever the caller does.
 */
class PagedFeed<T>(
    /** Page size of the endpoint this feed reads; also the step kept when appending. */
    val pageSize: Int,
    /** Identity of one row, used to drop duplicates that a shifting server-side order produces between pages. */
    private val identity: (T) -> Any,
    /** Reads [size] rows from [page] (0-based); null means the source went away, so no further page is offered. */
    private val fetch: suspend (page: Int, size: Int) -> Paged<T>?,
) {
    /** Serializes reset / append / reload: they all mutate [page] and [hasMore] together. */
    private val mutex = Mutex()

    /** Highest page loaded so far. */
    var page: Int = 0
        private set

    /** Whether the source has a page after [page]. */
    var hasMore: Boolean = false
        private set

    /** Server-side total of the last read, when the endpoint reports one (null keeps the previous value). */
    var total: Long? = null
        private set

    /** First page of a new query, dropping whatever was loaded before: use it when the query itself changed. */
    suspend fun reset(): List<T> = mutex.withLock { resetLocked() }

    private suspend fun resetLocked(): List<T> {
        page = 0
        hasMore = false
        total = null
        val result = fetch(0, pageSize) ?: return emptyList()
        hasMore = result.hasMore
        total = result.totalElements ?: total
        return result.items
    }

    /** Next page appended to [current]; [current] comes back unchanged when there is nothing more. */
    suspend fun append(current: List<T>): List<T> = mutex.withLock {
        if (!hasMore) return@withLock current
        val at = page + 1
        val result = fetch(at, pageSize) ?: run {
            hasMore = false
            return@withLock current
        }
        val seen = current.mapTo(HashSet<Any>(), identity)
        val appended = result.items.filter { seen.add(identity(it)) }
        page = at
        // A trailing page that repeats only what is already shown means the source is done, even when its
        // hasMore flag still says otherwise (same guard the list pages always applied).
        hasMore = result.hasMore && appended.isNotEmpty()
        total = result.totalElements ?: total
        return current + appended
    }

    /**
     * Re-reads the window already on screen and keeps [current], so reloading the same query does not shrink
     * the list under the user. [keepPages] is the depth the caller saw before it called; the feed's live depth
     * and the rows in [current] still widen the window, so a load-more that ran in between cannot be lost.
     *
     * Pass [newQuery] = true only when the query itself changed (other scope, other collection, other filter):
     * a same-query reload must never treat an empty [current] as a new query, because a load-more can be in
     * flight behind an empty screen and its page would be dropped by the reset.
     *
     * One request for a window up to [MAX_PAGE_SIZE] rows: the server caps its page size, so a very deep list
     * is read in a few aligned chunks instead of one oversized request.
     */
    suspend fun reload(
        current: List<T>,
        keepPages: Int = page,
        newQuery: Boolean = false,
    ): List<T> = mutex.withLock {
        if (newQuery) return@withLock resetLocked()
        // The caller reading [page] before this locked is the normal case: if the feed paged further meanwhile
        // (a load-more ran in between), reload the deeper window rather than rolling [page] back over its rows.
        // A clamped window used to record fewer pages than the rows on screen cover; re-reading fewer rows than
        // are shown would shrink the list under the user, so the window always covers [current] as well.
        val shownPages = if (current.isEmpty()) 0 else (current.size - 1) / pageSize
        val pages = maxOf(keepPages, page, shownPages)
        val wanted = (pages + 1) * pageSize
        val chunk = if (wanted <= MAX_PAGE_SIZE) wanted else MAX_PAGE_SIZE
        val seen = HashSet<Any>(wanted)
        val items = ArrayList<T>(wanted)
        var more = false
        var lastTotal: Long? = null
        var at = 0
        // A single-shot window stops after one read even if row identity dropped a duplicate: paging a
        // partial window forward would step over the rows between the duplicate and the next chunk.
        while (items.size < wanted && (at == 0 || chunk < wanted)) {
            // A failed read abandons the reload: the rows on screen stay exactly as they were, which beats
            // truncating them to whatever the first chunk happened to return.
            val before = items.size
            val result = fetch(at, chunk) ?: return@withLock current
            result.items.forEach { if (seen.add(identity(it))) items.add(it) }
            more = result.hasMore
            lastTotal = result.totalElements ?: lastTotal
            at++
            if (!more) break
            // A source that keeps re-serving the same tail reports hasMore forever; asking for the next chunk
            // would loop here while holding the feed lock, blocking every append and reload. The same
            // no-new-rows rule append applies ends the window instead.
            if (items.size == before) {
                more = false
                break
            }
        }
        // A read that stopped before filling the window (the source ran out, or the server clamped the requested
        // page size) covers fewer pages than [pages] claims. Take the depth those rows actually cover: otherwise
        // a later append starts past the rows between what is on screen and page [pages] and skips them.
        val kept = if (items.size > wanted) ArrayList(items.subList(0, wanted)) else items
        page = if (kept.isEmpty()) 0 else minOf(pages, (kept.size - 1) / pageSize)
        // Rows trimmed off the end are still ahead of this window: the chunk that overshot reported the end of its
        // own read, not of the rows kept, so leaving hasMore false would make the remaining rows unreachable.
        hasMore = more || kept.size < items.size
        lastTotal?.let { total = it }
        return kept
    }

    private companion object {
        /** Server-side ceiling on one page (Komga's pageable max); larger windows are read in aligned chunks. */
        const val MAX_PAGE_SIZE = 2000
    }
}
