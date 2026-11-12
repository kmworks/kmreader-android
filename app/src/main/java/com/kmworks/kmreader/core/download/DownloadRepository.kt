package com.kmworks.kmreader.core.download

import android.content.Context
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.network.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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

    // 下载策略（对齐 OfflinePolicySheet 的 Wi-Fi/自动下载开关）
    private val settingsPrefs
        get() = context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)

    private val _wifiOnly = MutableStateFlow(settingsPrefs.getBoolean(KEY_WIFI_ONLY, false))

    /** 仅 Wi-Fi 下载：on 时任务约束 UNMETERED 网络。 */
    val wifiOnly: Flow<Boolean> = _wifiOnly

    fun setWifiOnly(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        _wifiOnly.value = enabled
    }

    private val _autoDownloadUnread = MutableStateFlow(settingsPrefs.getBoolean(KEY_AUTO_UNREAD, false))

    /**
     * 自动下载未读内容：当前仅持久化该偏好，下载触发器（SSE/续读监听）
     * 尚未实现，后续版本接入后 worker 才会消费。
     */
    val autoDownloadUnread: Flow<Boolean> = _autoDownloadUnread

    fun setAutoDownloadUnread(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_AUTO_UNREAD, enabled).apply()
        _autoDownloadUnread.value = enabled
    }

    // 暂停/恢复/入队等本地动作通过 tick 驱动 downloads flow 重新合成
    private val tick = MutableStateFlow(0)

    /** 入队整本下载；同名任务 REPLACE 重跑（重试语义）。 */
    fun enqueueBook(bookDtoId: String, title: String, pageCount: Int) {
        enqueueMeta.edit()
            .putString(bookDtoId, title)
            .putInt("$bookDtoId#pages", pageCount)
            .apply()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (_wifiOnly.value) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED,
            )
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(
                DownloadWorker.KEY_BOOK_ID to bookDtoId,
                DownloadWorker.KEY_TITLE to title,
                DownloadWorker.KEY_PAGE_COUNT to pageCount,
            ))
            .setConstraints(constraints)
            .addTag(DownloadWorker.TAG)
            .addTag(bookDtoId)
            .build()
        workManager.enqueueUniqueWork(workName(bookDtoId), androidx.work.ExistingWorkPolicy.REPLACE, request)
        AppLog.i("Download", "入队：$title（$pageCount 页）")
        tick.value += 1
    }

    /** 真暂停：标记暂停态并取消任务；已落盘的页保留（续传时自动跳过）。 */
    suspend fun pauseBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        enqueueMeta.edit().putBoolean("$bookDtoId#paused", true).apply()
        workManager.cancelUniqueWork(workName(bookDtoId))
        tick.value += 1
    }

    /** 恢复下载：清除暂停标记并重新入队（已存在的页自动跳过，天然续传）。 */
    suspend fun resumeBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        val title = enqueueMeta.getString(bookDtoId, null) ?: return@withContext
        val pageCount = enqueueMeta.getInt("$bookDtoId#pages", 0)
        if (pageCount <= 0) return@withContext
        enqueueMeta.edit().putBoolean("$bookDtoId#paused", false).apply()
        enqueueBook(bookDtoId, title, pageCount)
    }

    /** 下载中队列（进行中 + 已暂停 + 失败）。 */
    val downloads: Flow<List<DownloadTaskUi>> =
        workManager.getWorkInfosByTagFlow(DownloadWorker.TAG)
            .combine(tick) { infos, _ -> infos }
            .map { infos ->
                val rows = infos.mapNotNull { info ->
                    val bookId = info.tags.firstOrNull { it != DownloadWorker.TAG }
                        ?: return@mapNotNull null
                    val title = enqueueMeta.getString(bookId, null) ?: bookId
                    val total = enqueueMeta.getInt("$bookId#pages", 0)
                    val paused = enqueueMeta.getBoolean("$bookId#paused", false)
                    val pageDone = info.progress.getInt(DownloadWorker.KEY_PAGE_DONE, 0)
                    val pageTotal = info.progress.getInt(DownloadWorker.KEY_PAGE_TOTAL, total)
                    when {
                        paused && info.state != WorkInfo.State.RUNNING -> DownloadTaskUi(
                            bookDtoId = bookId,
                            title = title,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Paused,
                        )
                        info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED -> DownloadTaskUi(
                            bookDtoId = bookId,
                            title = title,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Downloading,
                        )
                        info.state == WorkInfo.State.FAILED -> DownloadTaskUi(
                            bookDtoId = bookId,
                            title = title,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Failed,
                        )
                        else -> null
                    }
                }
                // WorkInfo 已被回收的暂停项（重启后）：按文件扫描补齐 Paused 行
                val present = rows.map { it.bookDtoId }.toSet()
                val pausedOrphans = enqueueMeta.all
                    .filter { it.key.endsWith("#paused") && it.value == true }
                    .map { it.key.removeSuffix("#paused") }
                    .filter { it !in present }
                    .mapNotNull { bookId ->
                        val dir = File(downloadsDir, bookId)
                        val pageTotal = enqueueMeta.getInt("$bookId#pages", 0)
                        if (!dir.isDirectory || pageTotal <= 0) return@mapNotNull null
                        val pageDone = dir.listFiles()
                            ?.count { it.name != DownloadWorker.META_FILE } ?: 0
                        // 已完整的不该出现在队列（等 completed 列表接管）
                        if (pageDone >= pageTotal) return@mapNotNull null
                        DownloadTaskUi(
                            bookDtoId = bookId,
                            title = enqueueMeta.getString(bookId, null) ?: bookId,
                            pageDone = pageDone,
                            pageTotal = pageTotal,
                            status = DownloadTaskUi.Status.Paused,
                        )
                    }
                rows + pausedOrphans
            }
            .flowOn(Dispatchers.IO)

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
        enqueueMeta.edit()
            .remove(bookDtoId)
            .remove("$bookDtoId#pages")
            .remove("$bookDtoId#paused")
            .apply()
        File(downloadsDir, bookDtoId).deleteRecursively()
        tick.value += 1
    }

    /** 删除已完成的书籍文件。 */
    suspend fun deleteBook(bookDtoId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(bookDtoId))
        enqueueMeta.edit()
            .remove(bookDtoId)
            .remove("$bookDtoId#pages")
            .remove("$bookDtoId#paused")
            .apply()
        File(downloadsDir, bookDtoId).deleteRecursively()
        tick.value += 1
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

    companion object {
        private const val KEY_WIFI_ONLY = "wifiOnly"
        private const val KEY_AUTO_UNREAD = "autoDownloadUnread"
    }
}
