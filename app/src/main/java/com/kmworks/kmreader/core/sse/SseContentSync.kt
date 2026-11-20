package com.kmworks.kmreader.core.sse

import com.kmworks.kmreader.core.data.ContentChange
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.ContentChangeKind
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.data.ThumbnailCache
import com.kmworks.kmreader.core.data.booksChanged
import com.kmworks.kmreader.core.data.collectionsChanged
import com.kmworks.kmreader.core.data.readListsChanged
import com.kmworks.kmreader.core.data.seriesChanged
import com.kmworks.kmreader.core.data.smartListsChanged
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Forwards SSE events onto [ContentChangeBus] so screens can patch by id instead of reloading themselves.
 *
 * Before this class each screen subscribed to [SseService] on its own and re-read its whole query three seconds
 * after any relevant event: a reply on one book reloaded the entire library page. The server's payload already says
 * *which* entity changed, so the same information can travel down the local-write channel and reach the screen as a
 * row-level patch.
 *
 * Events are not forwarded one by one: a burst (a series import touches hundreds of books) is accumulated for
 * [WINDOW_MILLIS] and sent as one change carrying the union of ids. Past [MAX_IDS_PER_CHANGE] the union stops being
 * useful — no screen should issue hundreds of row requests — so it degrades to a single "reload" signal.
 */
@Singleton
class SseContentSync @Inject constructor(
    private val sseService: SseService,
    private val parser: SseChangeParser,
    private val contentChangeBus: ContentChangeBus,
    private val thumbnailCache: ThumbnailCache,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    /** A change that could not be classified: the next flush sends one reload instead of ids. */
    private var unknown = false
    private val mutex = Mutex()
    private val pending = LinkedHashMap<Carrier, Pending>()
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

    /** Idempotent; called once per process start (RootViewModel), the connection itself is still owned by SseService. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            coroutineScope {
                launch { collectEvents() }
                launch { flushSignal.receiveAsFlow().debounce(WINDOW_MILLIS).collect { flush() } }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        flushSignal.trySend(Unit)
    }

    private suspend fun collectEvents() {
        sseService.events.collect { event ->
            val parsed = runCatching { parser.parse(event) }.getOrNull() ?: return@collect
            if (parsed.coverDtoIds.isNotEmpty()) refreshCovers(parsed)
            val change = parsed.change ?: return@collect
            mutex.withLock { accumulate(change) }
            flushSignal.trySend(Unit)
        }
    }

    private fun accumulate(change: ContentChange) {
        val carrier = carrierOf(change)
        if (carrier == null) {
            unknown = true
            return
        }
        val entry = pending.getOrPut(carrier) { Pending() }
        entry.ids += change.dtoIds
        entry.kinds += change.kind
        if (change.kind == ContentChangeKind.ReadStateChanged && change.read == false) entry.unread = true
    }

    private suspend fun flush() {
        val (snapshot, sawUnknown) = mutex.withLock {
            val copy = pending.toList()
            pending.clear()
            val flag = unknown
            unknown = false
            copy to flag
        }
        if (sawUnknown) {
            contentChangeBus.notify(ContentChange.Unknown)
            // An unknown change already costs every screen a reload; the accumulated ids would only add requests.
            if (snapshot.isEmpty()) return
        }
        snapshot.forEach { (carrier, entry) -> notifyCarrier(carrier, entry) }
    }

    private fun notifyCarrier(carrier: Carrier, entry: Pending) {
        val ids = entry.ids
        if (ids.isEmpty()) return
        val kind = mergedKind(entry.kinds)
        if (ids.size > MAX_IDS_PER_CHANGE || kind == null) {
            contentChangeBus.notify(ContentChange.Unknown)
            return
        }
        val change = when (carrier) {
            Carrier.Books -> booksChanged(ids, kind, read = entry.unread.takeIf { it })
            Carrier.Series -> seriesChanged(ids, kind, read = entry.unread.takeIf { it })
            Carrier.Collections -> collectionsChanged(ids, kind)
            Carrier.ReadLists -> readListsChanged(ids, kind)
            Carrier.SmartLists -> smartListsChanged(ids, kind)
        }
        contentChangeBus.notify(change)
    }

    /** Deletion dominates; a create mixed with other changes cannot be expressed as a patch, so it becomes unknown. */
    private fun mergedKind(kinds: Set<ContentChangeKind>): ContentChangeKind? = when {
        kinds.isEmpty() -> null
        kinds.size == 1 -> kinds.first()
        ContentChangeKind.Deleted in kinds -> ContentChangeKind.Deleted
        ContentChangeKind.Created in kinds -> null
        else -> ContentChangeKind.Touched
    }

    private fun refreshCovers(parsed: ParsedSseChange) {
        val carrier = when (parsed.change) {
            is ContentChange.Books -> Carrier.Books
            is ContentChange.Series -> Carrier.Series
            is ContentChange.Collections -> Carrier.Collections
            is ContentChange.ReadLists -> Carrier.ReadLists
            is ContentChange.SmartLists -> Carrier.SmartLists
            else -> null
        } ?: return
        parsed.coverDtoIds.forEach { dtoId ->
            val stableId = when (carrier) {
                Carrier.Books -> DetailIdRegistry.bookStableId(dtoId)
                Carrier.Series -> DetailIdRegistry.seriesStableId(dtoId)
                Carrier.Collections -> DetailIdRegistry.collectionStableId(dtoId)
                Carrier.ReadLists -> DetailIdRegistry.readListStableId(dtoId)
                Carrier.SmartLists -> DetailIdRegistry.smartListStableId(dtoId)
            }
            // Not registered yet (no screen showed it) means no cached cover to drop.
            stableId?.let { thumbnailCache.refresh(it) }
        }
    }

    private fun carrierOf(change: ContentChange): Carrier? = when (change) {
        is ContentChange.Books -> Carrier.Books
        is ContentChange.Series -> Carrier.Series
        is ContentChange.Collections -> Carrier.Collections
        is ContentChange.ReadLists -> Carrier.ReadLists
        is ContentChange.SmartLists -> Carrier.SmartLists
        ContentChange.Unknown -> null
    }

    private class Pending {
        val ids: MutableSet<String> = mutableSetOf()
        val kinds: MutableSet<ContentChangeKind> = mutableSetOf()
        var unread: Boolean = false
    }

    private enum class Carrier { Books, Series, Collections, ReadLists, SmartLists }

    private companion object {
        /**
         * Quiet time before a burst is forwarded. Long enough that a multi-entity change (a collection edit adding
         * twenty series) arrives as one signal, short enough that the screen still feels immediate.
         */
        const val WINDOW_MILLIS = 350L

        /** Above this, patch-per-row stops being cheaper than the reload it replaces. */
        const val MAX_IDS_PER_CHANGE = 100
    }
}
