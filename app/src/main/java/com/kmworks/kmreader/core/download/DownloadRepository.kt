package com.kmworks.kmreader.core.download

import android.content.Context
import android.os.StatFs
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kmworks.kmreader.core.network.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载管理（MVP：整本下载到应用私有目录 downloads/{bookDtoId}/）。
 * 队列状态来自 WorkManager（tag = bookDtoId，公共 tag = book_download）；
 * 已下载列表与完整性检查用文件扫描（meta.txt 记标题/页数）。
 */
@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("unused") private val sessionManager: SessionManager,
) {

    private val workManager get() = WorkManager.getInstance(context)
    private val downloadsDir get() = File(context.filesDir, "downloads")

    // 入队元数据（WorkInfo 不带 inputData，标题/页数存这里供队列行展示）
    private val enqueueMeta
        get() = context.getSharedPreferences("download_enqueue_meta", Context.MODE_PRIVATE)

    /** 入队整本下载；同名任务 REPLACE 重跑（重试语义）。 */
    fun enqueueBook(bookDtoId: String, title: String, pageCount: Int) {
        enqueueMeta.edit()
            .putString(bookDtoId, title)
            .putInt("$bookDtoId#pages", pageCount)
            .apply()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(
                DownloadWorker.KEY_BOOK_ID to bookDtoId,
                DownloadWorker.KEY_TITLE to title,
                DownloadWorker.KEY_PAGE_COUNT to pageCount,
            ))
            .addTag(DownloadWorker.TAG)
            .addTag(bookDtoId)
            .build()
        workManager.enqueueUniqueWork(workName(bookDtoId), androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    /** 下载中队列（进行中 + 失败）。 */
    val downloads: Flow<List<DownloadTaskUi>> =
        workManager.getWorkInfosByTagFlow(DownloadWorker.TAG).map { infos ->
            infos.mapNotNull { info ->
                val bookId = info.tags.firstOrNull { it != DownloadWorker.TAG }
                    ?: return@mapNotNull null
                val title = enqueueMeta.getString(bookId, null) ?: bookId
                val total = enqueueMeta.getInt("$bookId#pages", 0)
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED,
                    -> DownloadTaskUi(
                        bookDtoId = bookId,
                        title = title,
                        pageDone = info.progress.getInt(DownloadWorker.KEY_PAGE_DONE, 0),
                        pageTotal = info.progress.getInt(DownloadWorker.KEY_PAGE_TOTAL, total),
                        status = DownloadTaskUi.Status.Downloading,
                    )
                    WorkInfo.State.FAILED -> DownloadTaskUi(
                        bookDtoId = bookId,
                        title = title,
                        pageDone = info.progress.getInt(DownloadWorker.KEY_PAGE_DONE, 0),
                        pageTotal = info.progress.getInt(DownloadWorker.KEY_PAGE_TOTAL, total),
                        status = DownloadTaskUi.Status.Failed,
                    )
                    else -> null
                }
            }
        }

    /** 已下载书籍（文件扫描；需 meta.txt 与页文件齐全）。 */
    suspend fun downloadedBooks(): List<DownloadedBookUi> = withContext(Dispatchers.IO) {
        downloadsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val meta = File(dir, DownloadWorker.META_FILE).takeIf { it.isFile } ?: return@mapNotNull null
            val props = meta.readLines().mapNotNull {
                val i = it.indexOf('=')
                if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
            }.toMap()
            val pageCount = props["pageCount"]?.toIntOrNull() ?: return@mapNotNull null
            val pages = dir.listFiles()?.count { it.name != DownloadWorker.META_FILE } ?: 0
            if (pages < pageCount) return@mapNotNull null
            DownloadedBookUi(
                bookDtoId = dir.name,
                title = props["title"] ?: dir.name,
                pageCount = pageCount,
                sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            )
        }?.sortedBy { it.title } ?: emptyList()
    }

    /** 完整性检查：目录存在且页数齐全。 */
    suspend fun isBookDownloaded(bookDtoId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(downloadsDir, bookDtoId)
        val meta = File(dir, DownloadWorker.META_FILE)
        if (!meta.isFile) return@withContext false
        val pageCount = meta.readLines()
            .firstOrNull { it.startsWith("pageCount=") }
            ?.substringAfter('=')?.toIntOrNull() ?: return@withContext false
        val pages = dir.listFiles()?.count { it.name != DownloadWorker.META_FILE } ?: 0
        pages >= pageCount
    }

    /** 取消任务并删除已落盘的页文件。 */
    suspend fun cancelBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(bookDtoId))
        enqueueMeta.edit().remove(bookDtoId).remove("$bookDtoId#pages").apply()
        File(downloadsDir, bookDtoId).deleteRecursively()
    }

    /** 删除已完成的书籍文件。 */
    suspend fun deleteBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(bookDtoId))
        enqueueMeta.edit().remove(bookDtoId).remove("$bookDtoId#pages").apply()
        File(downloadsDir, bookDtoId).deleteRecursively()
    }

    /** 下载目录总占用字节数。 */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        downloadsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 设备总存储字节数（filesDir 所在分区）。 */
    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.blockSizeLong * stat.blockCountLong
    }

    private fun workName(bookDtoId: String) = "download_$bookDtoId"
}
