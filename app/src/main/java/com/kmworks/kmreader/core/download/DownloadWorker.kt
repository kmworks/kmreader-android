package com.kmworks.kmreader.core.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
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
import okhttp3.Request
import java.io.File

/**
 * Whole-book download Worker: GETs each page image into the app-private directory
 * (page-level concurrency = the download concurrency from the network settings page).
 * Target files downloads/{bookDtoId}/{page:000}.{ext}; existing pages are skipped (resumable).
 * On completion writes meta (title/page count) for the downloaded list and integrity checks.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: bookId
        val pageCount = inputData.getInt(KEY_PAGE_COUNT, 0)
        if (pageCount <= 0) return Result.failure()
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
            val initialDone = dir.listFiles()?.count { it.name != META_FILE } ?: 0
            val doneCounter = AtomicInteger(initialDone)
            val semaphore = Semaphore(concurrency)
            coroutineScope {
                for (page in 1..pageCount) {
                    if (pageFile(dir, page) != null) continue
                    launch {
                        semaphore.withPermit {
                            downloadPage(clients.okHttpClient, clients.baseUrl, bookId, page, dir)
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
            File(dir, META_FILE).writeText("title=$title\npageCount=$pageCount\n")
            AppLog.i("Download", "done: $title ($pageCount pages, concurrency $concurrency)")
            Result.success()
        } catch (e: CancellationException) {
            // Stopped/cancelled by WorkManager: rethrow, do not retry
            throw e
        } catch (e: Exception) {
            val doneCount = dir.listFiles()?.count { it.name != META_FILE } ?: 0
            setProgress(workDataOf(
                KEY_PAGE_DONE to doneCount,
                KEY_PAGE_TOTAL to pageCount,
                KEY_TITLE to title,
            ))
            AppLog.e("Download", "failed: $title page ${doneCount + 1}", e)
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: applicationContext.getString(R.string.download_failed))))
        }
    }

    /** Download a single page; the extension is derived from the response Content-Type. */
    private suspend fun downloadPage(
        client: okhttp3.OkHttpClient,
        baseUrl: String,
        bookId: String,
        page: Int,
        dir: File,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/books/$bookId/pages/$page")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val ext = extensionFor(response.header("Content-Type"))
            val body = response.body ?: throw java.io.IOException("empty body")
            val target = File(dir, "%03d.%s".format(page, ext))
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    companion object {
        const val TAG = "book_download"
        const val KEY_BOOK_ID = "bookId"
        const val KEY_TITLE = "title"
        const val KEY_PAGE_COUNT = "pageCount"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_PAGE_DONE = "pageDone"
        const val KEY_PAGE_TOTAL = "pageTotal"
        const val KEY_ERROR = "error"
        const val META_FILE = "meta.txt"

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
            return dir.listFiles()?.firstOrNull { it.name.startsWith(prefix) }
        }
    }
}
