package com.kmworks.kmreader.core.pdf

import android.content.Context
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.download.OfflineLibrary
import com.kmworks.kmreader.core.network.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Whole-PDF download and caching (mirrors the local file cache of the original PdfReaderView).
 * GET {baseUrl}/api/v1/books/{id}/file streams through the session OkHttp (auth interceptor) into filesDir/pdf/.
 */
@Singleton
class PdfRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val offlineLibrary: OfflineLibrary,
) {

    private val pdfDir: File get() = File(context.filesDir, "pdf")

    fun bookDtoId(stableId: Int): String? = DetailIdRegistry.bookDtoId(stableId)

    /**
     * The PDF file to open (exists and non-empty); otherwise null.
     *
     * A whole-book download (DownloadKind.Pdf) wins over this repository's own cache: P2 already
     * stores book.pdf next to the other downloads, so reusing it avoids a second copy on disk and
     * a second download.
     */
    fun cachedFile(bookDtoId: String): File? =
        offlineLibrary.pdfFile(bookDtoId)?.takeIf { it.isFile && it.length() > 0L }
            ?: File(pdfDir, "$bookDtoId.pdf").takeIf { it.exists() && it.length() > 0L }

    /**
     * Download the whole PDF; returns immediately when already cached.
     * Resumable: when a .part half-finished file exists a Range header is sent (append on 206; if the
     * server ignores Range and answers 200, the whole file is rewritten). Failures keep .part for the
     * next resume attempt.
     * onProgress(copiedBytes, totalBytes); total is -1 when unknown (indeterminate progress).
     */
    suspend fun downloadPdf(
        bookDtoId: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        cachedFile(bookDtoId)?.let { return@withContext it }
        val clients = sessionManager.clients.value ?: return@withContext null
        val tmp = File(pdfDir, "$bookDtoId.pdf.part")
        val resumeFrom = if (tmp.isFile) tmp.length() else 0L
        val requestBuilder = Request.Builder()
            .url("${clients.baseUrl}/api/v1/books/$bookDtoId/file")
        if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")
        try {
            clients.okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body
                pdfDir.mkdirs()
                val append = response.code == 206 && resumeFrom > 0
                val total = body.contentLength().let { length ->
                    if (length >= 0) length + (if (append) resumeFrom else 0L) else -1L
                }
                var copied = if (append) resumeFrom else 0L
                onProgress(copied, total)
                body.byteStream().use { input ->
                    FileOutputStream(tmp, append).buffered().use { output ->
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
            // Keep the .part half-finished file: the next call resumes from it
            null
        }
    }

    /** Delete the cached file. */
    suspend fun deletePdf(bookDtoId: String) = withContext(Dispatchers.IO) {
        File(pdfDir, "$bookDtoId.pdf").delete()
        File(pdfDir, "$bookDtoId.pdf.part").delete()
    }

    /** Total PDF cache size (bytes). */
    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) {
        pdfDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Clear the whole PDF cache (used by the cache settings page). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        pdfDir.listFiles()?.forEach { it.delete() }
    }
}
