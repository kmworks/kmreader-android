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
 * 自动下载未读内容的 SSE 触发器：SSE 书籍/系列事件 → 未读检测 → [DownloadRepository.enqueueBook]。
 * 事件防抖 5s 聚合后，从最新书籍里筛出未读完且未下载的整本入队；
 * 与系列详情页的 autoDownloadUnreadIfEnabled（5a27329）共用同一个开关。
 * 由 LogInitProvider 在进程启动时提前实例化（订阅常驻应用级 scope）。
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
            .onFailure { AppLog.w("Download", "自动下载未读：拉取最新书籍失败：${it.message}") }
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
        if (enqueued > 0) AppLog.i("Download", "自动下载未读：入队 $enqueued 本")
    }
}
