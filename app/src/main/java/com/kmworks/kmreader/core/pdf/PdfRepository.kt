package com.kmworks.kmreader.core.pdf

import android.content.Context
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.network.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * PDF 整本下载与缓存（对齐原作 PdfReaderView 的本地文件缓存）。
 * GET {baseUrl}/api/v1/books/{id}/file 经会话 OkHttp（认证拦截器）流式写入 filesDir/pdf/。
 */
@Singleton
class PdfRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
) {

    private val pdfDir: File get() = File(context.filesDir, "pdf")

    fun bookDtoId(stableId: Int): String? = DetailIdRegistry.bookDtoId(stableId)

    /** 已缓存的 PDF 文件（存在且非空）；否则 null。 */
    fun cachedFile(bookDtoId: String): File? {
        val file = File(pdfDir, "$bookDtoId.pdf")
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * 下载整本 PDF；已缓存直接返回。
     * onProgress(copiedBytes, totalBytes)；total 未知时传 -1（不定进度）。
     * 失败返回 null 并清理半成品。
     */
    suspend fun downloadPdf(
        bookDtoId: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        cachedFile(bookDtoId)?.let { return@withContext it }
        val clients = sessionManager.clients.value ?: return@withContext null
        val request = Request.Builder()
            .url("${clients.baseUrl}/api/v1/books/$bookDtoId/file")
            .build()
        val tmp = File(pdfDir, "$bookDtoId.pdf.part")
        try {
            clients.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                pdfDir.mkdirs()
                val total = body.contentLength()
                var copied = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied, total)
                        }
                    }
                }
                val target = File(pdfDir, "$bookDtoId.pdf")
                if (tmp.renameTo(target)) target else null
            }
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    /** 删除缓存文件。 */
    suspend fun deletePdf(bookDtoId: String) = withContext(Dispatchers.IO) {
        File(pdfDir, "$bookDtoId.pdf").delete()
        File(pdfDir, "$bookDtoId.pdf.part").delete()
    }
}
