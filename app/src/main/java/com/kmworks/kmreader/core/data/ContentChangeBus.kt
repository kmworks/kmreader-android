package com.kmworks.kmreader.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What a write did to the entities it touched. */
enum class ContentChangeKind {
    /** Read state flipped; [ContentChange.read] says which way. */
    ReadStateChanged,

    /**
     * Reading progress moved without completing. Emitted once per reading session, when the reader closes
     * (per-page reports stay silent), together with [ContentChange.progress].
     */
    ProgressChanged,

    /** The entity's own fields changed (metadata, cover, summary, ordered flag). */
    MetadataChanged,

    /** Membership of a collection/read list changed: members were added or removed. */
    MembershipChanged,

    /** The entity was created; a screen cannot patch a new row in, it reloads. */
    Created,

    /** The entity no longer exists; a screen cannot patch that away either, it reloads. */
    Deleted,

    /**
     * The server announced a change but sends only ids (SSE payloads carry no values), so a screen has to
     * re-read the rows it shows by id before patching them.
     */
    Touched,
}

/**
 * A write issued by this app, named by the DTO ids it touched and what it did to them.
 *
 * The point is that a screen showing those entities can patch its own rows instead of re-reading the
 * whole query: marking ten books read costs no request at all, while the same event arriving as a
 * bare "something changed" signal would force a full reload of every list on screen.
 *
 * Carriers are per entity kind because the UI states are: books and series carry read state, the
 * three list kinds only carry their own fields and membership.
 */
sealed interface ContentChange {
    /** Server-side DTO ids of the entities the write touched. */
    val dtoIds: Set<String>

    val kind: ContentChangeKind

    /** With [ContentChangeKind.ReadStateChanged]: true marked read, false marked unread. */
    val read: Boolean? get() = null

    /** With [ContentChangeKind.ProgressChanged]: the page fraction read (0..1). */
    val progress: Float? get() = null

    data class Books(
        override val dtoIds: Set<String>,
        override val kind: ContentChangeKind,
        override val read: Boolean? = null,
        override val progress: Float? = null,
        /** Library the rows belong to when the source says so (SSE payloads carry it); null = unknown. */
        val libraryId: String? = null,
    ) : ContentChange

    data class Series(
        override val dtoIds: Set<String>,
        override val kind: ContentChangeKind,
        override val read: Boolean? = null,
        override val progress: Float? = null,
        /** Library the rows belong to when the source says so (SSE payloads carry it); null = unknown. */
        val libraryId: String? = null,
    ) : ContentChange

    data class Collections(
        override val dtoIds: Set<String>,
        override val kind: ContentChangeKind,
    ) : ContentChange

    data class ReadLists(
        override val dtoIds: Set<String>,
        override val kind: ContentChangeKind,
    ) : ContentChange

    data class SmartLists(
        override val dtoIds: Set<String>,
        override val kind: ContentChangeKind,
    ) : ContentChange

    /**
     * A library was added, renamed, re-rooted or removed. No screen patches a library row by id, so this
     * carries no values either: screens that hold a library list (scope menus, the dashboard's library
     * cards) re-read it. See [libraryChanges].
     */
    data class Libraries(
        override val dtoIds: Set<String>,
        override val kind: ContentChangeKind,
    ) : ContentChange

    /**
     * Something changed that cannot be expressed as a patch: an SSE payload that failed to decode, an event kind
     * this app does not model yet, or a change touching more ids than a screen should re-read one by one. Screens
     * never patch it; [observeChanges] routes it straight to their reload path.
     */
    data object Unknown : ContentChange {
        override val dtoIds: Set<String> get() = emptySet()
        override val kind: ContentChangeKind get() = ContentChangeKind.Touched
    }
}

/** Shorthand for the write call sites, which all start from a single DTO id or a batch of them. */
internal fun booksChanged(
    dtoIds: Collection<String>,
    kind: ContentChangeKind,
    read: Boolean? = null,
    progress: Float? = null,
    libraryId: String? = null,
): ContentChange = ContentChange.Books(dtoIds.toSet(), kind, read, progress, libraryId)

internal fun seriesChanged(
    dtoIds: Collection<String>,
    kind: ContentChangeKind,
    read: Boolean? = null,
    progress: Float? = null,
    libraryId: String? = null,
): ContentChange = ContentChange.Series(dtoIds.toSet(), kind, read, progress, libraryId)

internal fun collectionsChanged(dtoIds: Collection<String>, kind: ContentChangeKind): ContentChange =
    ContentChange.Collections(dtoIds.toSet(), kind)

internal fun readListsChanged(dtoIds: Collection<String>, kind: ContentChangeKind): ContentChange =
    ContentChange.ReadLists(dtoIds.toSet(), kind)

internal fun smartListsChanged(dtoIds: Collection<String>, kind: ContentChangeKind): ContentChange =
    ContentChange.SmartLists(dtoIds.toSet(), kind)

internal fun librariesChanged(dtoIds: Collection<String>, kind: ContentChangeKind): ContentChange =
    ContentChange.Libraries(dtoIds.toSet(), kind)

/**
 * In-process "the library content changed" signal.
 *
 * SSE already relays server-side changes, but it needs the long connection, the auto-refresh switch and the server
 * actually emitting an event. A write issued by this app (mark read/unread, delete, metadata edit, list membership)
 * must reach the screens showing that content even when any of those is unavailable — SSE disabled, connection
 * retrying, or the server never announcing that particular change. Each screen then either patches the rows it
 * already has (see [observeChanges]) or re-reads its query.
 *
 * Emitted only by write operations that change what lists show. Reading progress is excluded except for the one
 * report a reader flushes when it closes (see [ContentChangeKind.ProgressChanged]): the reader reports every
 * settled page, and broadcasting those would cost far more than the debounced SSE path they already get.
 */
@Singleton
class ContentChangeBus @Inject constructor() {

    private val _changes = MutableSharedFlow<ContentChange>(extraBufferCapacity = 64)

    /** Every change, local and SSE-forwarded: screens that refresh on anything collect this. */
    val changes: SharedFlow<ContentChange> = _changes.asSharedFlow()

    /**
     * Writes issued by this app only. SSE-forwarded changes reach screens that refresh on any change through
     * [changes] too, but a screen that also has its own SSE path (the dashboard) must not refresh twice, and
     * must not refresh from SSE at all when its auto-refresh switch is off. Library scope cannot tell the two
     * apart: collection/read-list events carry no library id and multi-library batches deliberately clear it.
     */
    private val _localChanges = MutableSharedFlow<ContentChange>(extraBufferCapacity = 64)
    val localChanges: SharedFlow<ContentChange> = _localChanges.asSharedFlow()

    /** True while a reader session is open, so screens can hold their refresh (mirrors the iOS deferral). */
    private val _readerActive = MutableStateFlow(false)
    val readerActive: StateFlow<Boolean> = _readerActive.asStateFlow()

    private val lock = Any()
    private var readerSessions = 0

    /**
     * Changes that arrived while a reader was open. They are replayed when the last reader closes instead of
     * collapsing into one reload, so a screen that can re-read a subset (the dashboard's sections) only touches
     * what the session changed, the way the iOS coordinator flushes its deferred section set. Progress reports
     * are folded to their latest value: a reader posts one per page. A re-read request folds the same way, so a
     * session that mixes page turns with server echoes stays one entry per row set instead of one per page.
     */
    private class Held(val change: ContentChange, val fromSse: Boolean)

    private val heldChanges = ArrayList<Held>()
    private var heldOverflow = false

    fun readerOpened() {
        synchronized(lock) {
            readerSessions++
            if (readerSessions == 1) _readerActive.value = true
        }
    }

    /**
     * Mirrors the iOS deferral: changes that arrive while a reader is open are not delivered immediately, and
     * the ones still held are replayed when the last reader closes, progress reports folded to their latest
     * value. A screen that can re-read a subset then touches only that subset; a replay longer than
     * [MAX_HELD_CHANGES] degrades to one reload signal. Nested readers only release the gate when the last
     * one closes.
     */
    fun readerClosed() {
        val pending = synchronized(lock) {
            if (readerSessions == 0) return
            readerSessions--
            if (readerSessions > 0) return
            _readerActive.value = false
            val list = heldChanges.toList()
            val overflow = heldOverflow
            heldChanges.clear()
            heldOverflow = false
            list to overflow
        }
        if (pending.second) {
            // More changes than a replay is worth: one reload covers them all, on both channels (the batch can
            // mix local writes with SSE events).
            _changes.tryEmit(ContentChange.Unknown)
            _localChanges.tryEmit(ContentChange.Unknown)
            return
        }
        pending.first.forEach { held ->
            _changes.tryEmit(held.change)
            if (!held.fromSse) _localChanges.tryEmit(held.change)
        }
    }

    /** [fromSse] changes are forwarded from an SSE event and are not republished on [localChanges]. */
    fun notify(change: ContentChange, fromSse: Boolean = false) {
        synchronized(lock) {
            if (readerSessions > 0) {
                holdLocked(change, fromSse)
                return
            }
        }
        _changes.tryEmit(change)
        if (!fromSse) _localChanges.tryEmit(change)
    }

    private fun holdLocked(change: ContentChange, fromSse: Boolean) {
        // Progress reports and re-read requests for the same rows fold into one held entry, whichever kind they
        // arrived as: a reading session reports one page at a time, and without the fold a session longer than
        // MAX_HELD_CHANGES pages overflowed the replay into a full reload.
        if (change.kind == ContentChangeKind.ProgressChanged || change.kind == ContentChangeKind.Touched) {
            val index = heldChanges.indexOfFirst { held ->
                (held.change.kind == ContentChangeKind.ProgressChanged ||
                    held.change.kind == ContentChangeKind.Touched) && sameRows(held.change, change)
            }
            if (index >= 0) {
                // Removed and re-appended instead of replaced in place: the replay must follow the order the
                // changes arrived in, so a progress report that came after a mark-unread replays after it.
                heldChanges.removeAt(index)
                heldChanges += Held(change, fromSse)
                return
            }
        }
        if (heldChanges.size >= MAX_HELD_CHANGES) {
            heldOverflow = true
            return
        }
        heldChanges += Held(change, fromSse)
    }

    private fun sameRows(a: ContentChange, b: ContentChange): Boolean = when {
        a is ContentChange.Books && b is ContentChange.Books -> a.dtoIds == b.dtoIds
        a is ContentChange.Series && b is ContentChange.Series -> a.dtoIds == b.dtoIds
        else -> false
    }

    private companion object {
        /** Past this a replay costs more than one reload, so the close degrades to a full reload signal. */
        const val MAX_HELD_CHANGES = 24
    }
}

/**
 * True when the change names a library outside [scope], so a list scoped to other libraries cannot be affected
 * by it and neither patches nor re-reads. The library id is only known when the source carried it (SSE payloads
 * do, local writes do not), and a null or empty scope means "all libraries": both always pass.
 */
fun ContentChange.isOutsideLibraryScope(scope: Collection<String>?): Boolean {
    val libraryId = when (this) {
        is ContentChange.Books -> libraryId
        is ContentChange.Series -> libraryId
        else -> null
    } ?: return false
    if (scope == null || scope.isEmpty()) return false
    return libraryId !in scope
}

/**
 * Only the library-configuration changes, for the screens that cache the library list instead of the rows
 * of some collection (see [ContentChange.Libraries]). Every other change is filtered out so those screens
 * do not re-read on unrelated writes.
 */
fun ContentChangeBus.libraryChanges(): Flow<ContentChange> =
    changes.filter { it is ContentChange.Libraries }

/** How long a screen waits before re-reading itself after a change it could not patch. */
private const val RELOAD_DEBOUNCE_MILLIS = 500L

/**
 * How long a screen waits before re-reading the fields a patch leaves stale.
 *
 * Longer than [RELOAD_DEBOUNCE_MILLIS] on purpose: the fields that need it are aggregates the server recomputes
 * (a series' progress and unread count out of its books), and a re-read issued in the same tick as the write can
 * still see the old numbers.
 */
private const val SETTLE_DEBOUNCE_MILLIS = 4_000L

/**
 * What a screen did with a change it was handed.
 *
 * [Patched] alone used to mean "no request needed", which is what let a written book leave its series card stale:
 * the row was updated, the aggregate above it was not. The third outcome keeps those two apart.
 */
enum class PatchOutcome {
    /** Applied in place; nothing on screen is stale. */
    Patched,

    /** Applied in place, but a field this screen cannot compute from it (a server-side aggregate) is stale. */
    PendingSettle,

    /** Could not be applied: re-read now. */
    Declined,
}

/**
 * Feeds one screen from the change signal (local writes and forwarded SSE events).
 *
 * [patch] applies what the screen can represent in place. A [PatchOutcome.Declined] change is coalesced into a
 * single [reload] half a second after the last one, so a batch delete of ten books still re-reads the screen once.
 * A [PatchOutcome.PendingSettle] change is coalesced into [settle] [settleDebounceMillis] later, giving the server's
 * aggregates a moment to catch up before the screen asks for them.
 *
 * [reload] and [settle] suspend because a screen may have to fetch the touched rows before it can patch them (see
 * [ContentChangeKind.Touched]); the reload path is where that fetch happens.
 */
suspend fun ContentChangeBus.observeChanges(
    patch: suspend (ContentChange) -> PatchOutcome,
    reload: suspend (ContentChange) -> Unit,
    settle: suspend (ContentChange) -> Unit = {},
    reloadDebounceMillis: Long = RELOAD_DEBOUNCE_MILLIS,
    settleDebounceMillis: Long = SETTLE_DEBOUNCE_MILLIS,
) {
    coroutineScope {
        val reloads = Channel<ContentChange>(Channel.CONFLATED)
        val settles = Channel<ContentChange>(Channel.CONFLATED)
        launch {
            changes.collect { change ->
                // An unclassifiable change cannot be patched; it can only cost one reload.
                if (change is ContentChange.Unknown) {
                    reloads.trySend(change)
                    return@collect
                }
                when (patch(change)) {
                    PatchOutcome.Patched -> Unit
                    PatchOutcome.PendingSettle -> settles.trySend(change)
                    PatchOutcome.Declined -> reloads.trySend(change)
                }
            }
        }
        launch {
            reloads.receiveAsFlow().debounce(reloadDebounceMillis).collect { reload(it) }
        }
        launch {
            settles.receiveAsFlow().debounce(settleDebounceMillis).collect { settle(it) }
        }
    }
}
