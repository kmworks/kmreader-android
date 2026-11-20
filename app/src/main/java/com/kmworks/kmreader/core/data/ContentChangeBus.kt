package com.kmworks.kmreader.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process "the library content changed" signal.
 *
 * SSE already relays server-side changes, but it needs the long connection, the auto-refresh switch and the server
 * actually emitting an event. A write issued by this app (mark read/unread, delete, metadata edit, list membership)
 * must refresh the dashboard even when any of those is unavailable — SSE disabled, connection retrying, or the
 * server never announcing that particular change.
 *
 * Emitted only by write operations that change what the dashboard shows. Reading progress is deliberately excluded:
 * the reader reports it on every settled page, and a full dashboard reload per page turn would cost far more than
 * the debounced SSE path it already gets.
 */
@Singleton
class ContentChangeBus @Inject constructor() {

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}
