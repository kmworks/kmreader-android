package com.kmworks.kmreader.core.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 整本下载 Worker：逐页 GET 页面图片写入应用私有目录。
 * 目标文件 downloads/{bookDtoId}/{page:000}.{ext}，已存在的页跳过（可断点续下）。
 * 完成后写 meta（标题/页数），供已下载列表与完整性检查使用。
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
        val sessionManager = EntryPointAccessors
            .fromApplication(applicationContext, ImageLoaderEntryPoint::class.java)
            .sessionManager()
        val clients = sessionManager.clients.value
            ?: return Result.failure(workDataOf(KEY_ERROR to "无会话"))
        val dir = File(applicationContext.filesDir, "downloads/$bookId")
        dir.mkdirs()
        return try {
            var done = dir.listFiles()?.count { it.name != META_FILE } ?: 0
            for (page in 1..pageCount) {
                if (isStopped) return Result.failure(workDataOf(KEY_ERROR to "已取消"))
                if (pageFile(dir, page) != null) continue
                downloadPage(clients.okHttpClient, clients.baseUrl, bookId, page, dir)
                done += 1
                setProgress(workDataOf(
                    KEY_PAGE_DONE to done,
                    KEY_PAGE_TOTAL to pageCount,
                    KEY_TITLE to title,
                ))
            }
            File(dir, META_FILE).writeText("title=$title\npageCount=$pageCount\n")
            Result.success()
        } catch (e: Exception) {
            val doneCount = dir.listFiles()?.count { it.name != META_FILE } ?: 0
            setProgress(workDataOf(
                KEY_PAGE_DONE to doneCount,
                KEY_PAGE_TOTAL to pageCount,
                KEY_TITLE to title,
            ))
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "下载失败")))
        }
    }

    /** 下载单页；扩展名由响应 Content-Type 决定。 */
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

        /** 已落盘的第 N 页文件（扩展名任意）；未下载返回 null。 */
        fun pageFile(dir: File, page: Int): File? {
            val prefix = "%03d.".format(page)
            return dir.listFiles()?.firstOrNull { it.name.startsWith(prefix) }
        }
    }
}
