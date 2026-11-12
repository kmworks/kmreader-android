package com.kmworks.kmreader.core.sse

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

    /** 建立（或切换实例后重建）长连；重复调用同一实例为幂等。 */
    fun start(clients: ApiClients) {
        if (connectJob != null) return
        connectJob = scope.launch { runLoop(clients) }
    }

    /** 断开并停止重连（登出、关闭开关、配置变化时调用）。 */
    fun stop() {
        connectJob?.cancel()
        connectJob = null
        eventSource?.cancel()
        eventSource = null
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

    /** 单次连接；返回本次是否成功 onOpen 过。 */
    private suspend fun connectOnce(clients: ApiClients): Boolean {
        val opened = CompletableDeferred<Boolean>()
        val request = Request.Builder()
            .url(clients.baseUrl + "/sse/v1/events")
            .header("Accept", "text/event-stream")
            .apply { lastEventId?.let { header("Last-Event-ID", it) } }
            .build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                _connectionState.value = SseConnectionState.Connected
                opened.complete(true)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (id != null) lastEventId = id
                val now = System.currentTimeMillis()
                _lastEventAt.value = now
                _events.tryEmit(SseEvent(name = type ?: "message", data = data, atMillis = now))
            }

            override fun onClosed(eventSource: EventSource) {
                opened.complete(false)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                response?.close()
                opened.complete(false)
            }
        }
        eventSource = clients.newEventSource(request, listener)
        return try {
            opened.await()
        } finally {
            eventSource = null
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
