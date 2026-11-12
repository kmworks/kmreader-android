package com.kmworks.kmreader.core.sse

/** SSE connection lifecycle state. */
sealed interface SseConnectionState {
    data object Disconnected : SseConnectionState
    data object Connecting : SseConnectionState
    data object Connected : SseConnectionState
    data class Retrying(val retryInSeconds: Int) : SseConnectionState
}

/** Coarse event category, derived from the SSE event name prefix. */
enum class SseEventKind {
    Book,
    Series,
    ReadProgress,
    Collection,
    ReadList,
    Library,
    Task,
    Session,
    Other,
}

/** One SSE event: raw name + payload string, classified by prefix. */
data class SseEvent(
    val name: String,
    val data: String,
    val atMillis: Long,
) {
    val kind: SseEventKind = kindOf(name)

    companion object {
        /** 按事件名前缀归类（对齐原作 SSEEvent.swift 的枚举命名）。 */
        fun kindOf(name: String): SseEventKind = when {
            name.startsWith("ThumbnailSeriesCollection") -> SseEventKind.Collection
            name.startsWith("ThumbnailReadList") -> SseEventKind.ReadList
            name.startsWith("ThumbnailBook") -> SseEventKind.Book
            name.startsWith("ThumbnailSeries") -> SseEventKind.Series
            name.startsWith("Book") -> SseEventKind.Book
            name.startsWith("Series") -> SseEventKind.Series
            name.startsWith("ReadProgress") -> SseEventKind.ReadProgress
            name.startsWith("ReadList") -> SseEventKind.ReadList
            name.startsWith("Collection") -> SseEventKind.Collection
            name.startsWith("Library") -> SseEventKind.Library
            name.startsWith("Task") -> SseEventKind.Task
            name.startsWith("Session") -> SseEventKind.Session
            else -> SseEventKind.Other
        }
    }
}
