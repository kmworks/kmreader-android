package com.kmworks.kmreader.core.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.core.data.bookThumbnailUrl
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.settings.AppSettingsStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Whole-book download Worker. The download kind decides what is fetched:
 * - [DownloadKind.Pages]: one page image per page into downloads/{bookDtoId}/ ({page:000}.{ext}),
 *   page-level concurrency = the download concurrency from the network settings page; existing
 *   pages are skipped (resumable).
 * - [DownloadKind.Pdf] / [DownloadKind.Epub] / [DownloadKind.Archive]: the single book file from
 *   `GET /api/v1/books/{id}/file` into `book.{format}` (streamed to a `.part` file, then renamed).
 *
 * On completion writes meta (title/page count/kind/format) for the downloaded list, integrity
 * checks and the offline readers.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: bookId
        val pageCount = inputData.getInt(KEY_PAGE_COUNT, 0)
        val kind = DownloadKind.fromMeta(inputData.getString(KEY_KIND))
        val format = inputData.getString(KEY_FORMAT).orEmpty().ifBlank { defaultFormat(kind) }
        if (kind == DownloadKind.Pages && pageCount <= 0) return Result.failure()
        val concurrency = inputData
            .getInt(KEY_CONCURRENCY, AppSettingsStore.DEFAULT_DOWNLOAD_CONCURRENCY)
            .coerceIn(1, 8)
        val sessionManager = EntryPointAccessors
            .fromApplication(applicationContext, ImageLoaderEntryPoint::class.java)
            .sessionManager()
        val clients = sessionManager.clients.value
            ?: return Result.failure(workDataOf(KEY_ERROR to applicationContext.getString(R.string.download_no_session)))
        val dir = File(applicationContext.filesDir, "downloads/$bookId")
        dir.mkdirs()
        return try {
            when (kind) {
                DownloadKind.Pages -> downloadPages(clients.okHttpClient, clients.baseUrl, bookId, title, pageCount, concurrency, dir)
                else -> downloadSingleFile(clients.okHttpClient, clients.baseUrl, bookId, title, kind, format, dir)
            }
            // Best effort: the cover belongs to the download, so the offline list keeps it after a
            // server switch even though the new server has never seen this book id.
            DownloadCover.save(
                clients.okHttpClient,
                clients.bookThumbnailUrl(bookId),
                dir,
                overwrite = true,
            )
            DownloadMeta.write(
                dir = dir,
                title = title,
                pageCount = pageCount.coerceAtLeast(if (kind == DownloadKind.Pages) 1 else 0),
                kind = kind,
                format = format,
            )
            AppLog.i("Download", "done: $title ($kind, $pageCount pages, concurrency $concurrency)")
            Result.success()
        } catch (e: CancellationException) {
            // Stopped/cancelled by WorkManager: rethrow, do not retry
            throw e
        } catch (e: Exception) {
            val doneCount = if (kind == DownloadKind.Pages) {
                DownloadMeta.pageFileCount(dir)
            } else {
                0
            }
            setProgress(workDataOf(
                KEY_PAGE_DONE to doneCount,
                KEY_PAGE_TOTAL to totalUnits(kind, pageCount),
                KEY_TITLE to title,
            ))
            AppLog.e("Download", "failed: $title $kind", e)
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: applicationContext.getString(R.string.download_failed))))
        }
    }

    /** Page-image download (Divina-compatible EPUB, RAR/unknown media), resumable at page level. */
    private suspend fun downloadPages(
        client: OkHttpClient,
        baseUrl: String,
        bookId: String,
        title: String,
        pageCount: Int,
        concurrency: Int,
        dir: File,
    ) {
        val initialDone = DownloadMeta.pageFileCount(dir)
        val doneCounter = AtomicInteger(initialDone)
        val semaphore = Semaphore(concurrency)
        coroutineScope {
            for (page in 1..pageCount) {
                if (pageFile(dir, page) != null) continue
                launch {
                    semaphore.withPermit {
                        downloadPage(client, baseUrl, bookId, page, dir)
                        val done = doneCounter.incrementAndGet()
                        setProgress(workDataOf(
                            KEY_PAGE_DONE to done,
                            KEY_PAGE_TOTAL to pageCount,
                            KEY_TITLE to title,
                        ))
                    }
                }
            }
        }
    }

    /** Single-file download (PDF / EPUB / CBZ): streamed to a `.part` file, then renamed into place. */
    private suspend fun downloadSingleFile(
        client: OkHttpClient,
        baseUrl: String,
        bookId: String,
        title: String,
        kind: DownloadKind,
        format: String,
        dir: File,
    ) = withContext(Dispatchers.IO) {
        val target = File(dir, DownloadMeta.fileName(format))
        val part = File(dir, target.name + DownloadMeta.PART_SUFFIX)
        part.delete()
        setProgress(workDataOf(
            KEY_PAGE_DONE to 0,
            KEY_PAGE_TOTAL to totalUnits(kind, 0),
            KEY_TITLE to title,
        ))
        val request = Request.Builder()
            .url("$baseUrl/api/v1/books/$bookId/file")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            part.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("cannot move ${part.name} into place")
        }
        setProgress(workDataOf(
            KEY_PAGE_DONE to 1,
            KEY_PAGE_TOTAL to totalUnits(kind, 0),
            KEY_TITLE to title,
        ))
    }

    /** Download a single page; the extension is derived from the response Content-Type. */
    private suspend fun downloadPage(
        client: OkHttpClient,
        baseUrl: String,
        bookId: String,
        page: Int,
        dir: File,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/books/$bookId/pages/$page")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val ext = extensionFor(response.header("Content-Type"))
            val body = response.body
            val target = File(dir, "%03d.%s".format(page, ext))
            val part = File(dir, target.name + DownloadMeta.PART_SUFFIX)
            part.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("cannot move ${part.name} into place")
            }
        }
    }

    companion object {
        const val TAG = "book_download"
        const val KEY_BOOK_ID = "bookId"
        const val KEY_TITLE = "title"
        const val KEY_PAGE_COUNT = "pageCount"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_KIND = "kind"
        const val KEY_FORMAT = "format"
        const val KEY_PAGE_DONE = "pageDone"
        const val KEY_PAGE_TOTAL = "pageTotal"
        const val KEY_ERROR = "error"
        const val META_FILE = "meta.txt"

        /** Progress units: one per page for page downloads, one per file for single-file downloads. */
        private fun totalUnits(kind: DownloadKind, pageCount: Int): Int =
            if (kind == DownloadKind.Pages) pageCount else 1

        private fun defaultFormat(kind: DownloadKind): String = when (kind) {
            DownloadKind.Pages -> DownloadSpec.FORMAT_IMAGES
            DownloadKind.Pdf -> "pdf"
            DownloadKind.Epub -> "epub"
            DownloadKind.Archive -> "cbz"
        }

        fun extensionFor(contentType: String?): String = when {
            contentType?.startsWith("image/jpeg") == true -> "jpg"
            contentType?.startsWith("image/png") == true -> "png"
            contentType?.startsWith("image/webp") == true -> "webp"
            contentType?.startsWith("image/gif") == true -> "gif"
            else -> "img"
        }

        /** The persisted file of page N (any extension); null when not downloaded. */
        fun pageFile(dir: File, page: Int): File? {
            val prefix = "%03d.".format(page)
            return dir.listFiles()?.firstOrNull {
                it.name.startsWith(prefix) && !it.name.endsWith(DownloadMeta.PART_SUFFIX)
            }
        }
    }
}
