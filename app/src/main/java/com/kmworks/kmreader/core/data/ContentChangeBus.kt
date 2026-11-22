package com.kmworks.kmreader.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    ) : ContentChange

    data class Series(
        override val dtoIds: Set<String>,
        override val kind: ContentChangeKind,
        override val read: Boolean? = null,
        override val progress: Float? = null,
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
): ContentChange = ContentChange.Books(dtoIds.toSet(), kind, read, progress)

internal fun seriesChanged(
    dtoIds: Collection<String>,
    kind: ContentChangeKind,
    read: Boolean? = null,
    progress: Float? = null,
): ContentChange = ContentChange.Series(dtoIds.toSet(), kind, read, progress)

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
    val changes: SharedFlow<ContentChange> = _changes.asSharedFlow()

    fun notify(change: ContentChange) {
        _changes.tryEmit(change)
    }
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
    patch: (ContentChange) -> PatchOutcome,
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
