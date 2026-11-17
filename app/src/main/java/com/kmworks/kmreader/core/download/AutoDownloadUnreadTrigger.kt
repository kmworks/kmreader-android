package com.kmworks.kmreader.core.download

import com.kmworks.kmreader.core.data.BookRepository
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SSE trigger for auto-downloading unread content: SSE book/series events → unread detection → [DownloadRepository.enqueueBook].
 * After a 5s debounce, whole unread and not-yet-downloaded books are picked from the latest books and enqueued;
 * shares one toggle with the series detail page's autoDownloadUnreadIfEnabled (5a27329).
 * Instantiated eagerly by LogInitProvider at process start (the subscription lives in the app-level scope).
 */
@OptIn(FlowPreview::class)
@Singleton
class AutoDownloadUnreadTrigger @Inject constructor(
    sseService: SseService,
    private val downloadRepository: DownloadRepository,
    private val bookRepository: BookRepository,
    appScope: CoroutineScope,
) {

    init {
        appScope.launch {
            sseService.events
                .filter { it.kind == SseEventKind.Book || it.kind == SseEventKind.Series }
                .debounce(5_000)
                .collect { enqueueUnreadLatest() }
        }
    }

    private suspend fun enqueueUnreadLatest() {
        if (!downloadRepository.autoDownloadUnread.first()) return
        val books = runCatching { bookRepository.latest(size = 24) }
            .onFailure { AppLog.w("Download", "auto-unread: failed to fetch latest books: ${it.message}") }
            .getOrNull() ?: return
        val candidates = books.filter { dto ->
            dto.readProgress?.completed != true && dto.media.pagesCount > 0
        }
        var enqueued = 0
        for (dto in candidates) {
            if (downloadRepository.isBookDownloaded(dto.id)) continue
            downloadRepository.enqueueBook(
                bookDtoId = dto.id,
                title = dto.metadata.title.ifBlank { dto.name },
                pageCount = dto.media.pagesCount,
                libraryId = dto.libraryId,
            )
            enqueued += 1
        }
        if (enqueued > 0) AppLog.i("Download", "auto-unread: enqueued $enqueued books")
    }
}
