package com.kmworks.kmreader.core.sse

import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.network.ApiClients
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/** Server-sent events long connection (mirrors Core/Network/SSEService.swift). */
@Singleton
class SseService @Inject constructor(
    private val scope: CoroutineScope,
) {

    private val _connectionState = MutableStateFlow<SseConnectionState>(SseConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<SseEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val _lastEventAt = MutableStateFlow<Long?>(null)
    val lastEventAt = _lastEventAt.asStateFlow()

    private var connectJob: Job? = null
    private var eventSource: EventSource? = null
    private var lastEventId: String? = null
    private var activeClients: ApiClients? = null

    /** Open (or rebuild after switching instance) the long connection; repeated calls for the same instance are idempotent. */
    fun start(clients: ApiClients) {
        // Switching server instance/logged-in user → different clients object, so disconnect and reconnect (mirrors the iOS rebuild on instance change)
        if (connectJob != null && activeClients === clients) return
        if (connectJob != null) stop()
        activeClients = clients
        connectJob = scope.launch { runLoop(clients) }
    }

    /** Disconnect and stop reconnecting (called on logout, when the toggle is turned off, or on configuration change). */
    fun stop() {
        connectJob?.cancel()
        connectJob = null
        eventSource?.cancel()
        eventSource = null
        activeClients = null
        // Mirrors iOS: disconnecting resets the cursor, so the next reconnect receives events from scratch
        lastEventId = null
        _connectionState.value = SseConnectionState.Disconnected
    }

    private suspend fun runLoop(clients: ApiClients) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            _connectionState.value = SseConnectionState.Connecting
            val opened = connectOnce(clients)
            if (!currentCoroutineContext().isActive) break
            if (opened) backoffMs = INITIAL_BACKOFF_MS
            val retrySeconds = (backoffMs / 1000L).toInt()
            _connectionState.value = SseConnectionState.Retrying(retrySeconds)
            delay(backoffMs)
            if (!opened) backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** A single connection; suspends until the connection drops and returns whether onOpen fired this time. */
    private suspend fun connectOnce(clients: ApiClients): Boolean {
        var didOpen = false
        val closed = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url(clients.baseUrl + "/sse/v1/events")
            .header("Accept", "text/event-stream")
            .apply { lastEventId?.let { header("Last-Event-ID", it) } }
            .build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                didOpen = true
                _connectionState.value = SseConnectionState.Connected
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (id != null) lastEventId = id
                val now = System.currentTimeMillis()
                _lastEventAt.value = now
                _events.tryEmit(SseEvent(name = type ?: "message", data = data, atMillis = now))
            }

            override fun onClosed(eventSource: EventSource) {
                closed.complete(Unit)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                response?.close()
                AppLog.w(TAG, "SSE connection failed: ${t?.message ?: "HTTP ${response?.code}"}")
                closed.complete(Unit)
            }
        }
        val source = clients.newEventSource(request, listener)
        eventSource = source
        return try {
            // Critical: hold the connection until it drops; returning at onOpen would leak a new connection
            // every 2s, and once OkHttp's 5-per-host limit is reached every request (incl. covers) starves
            closed.await()
            didOpen
        } finally {
            source.cancel()
            eventSource = null
        }
    }

    private companion object {
        const val TAG = "SseService"
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
