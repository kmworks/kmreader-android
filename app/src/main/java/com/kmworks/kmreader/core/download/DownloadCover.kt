package com.kmworks.kmreader.core.download

import com.kmworks.kmreader.core.logging.AppLog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Book cover saved inside a download directory.
 *
 * Covers used to be resolved through the active server only, so switching servers blanked the
 * downloaded list of every book that server had never seen. Keeping the bytes with the download
 * makes the cover independent of which server is active (and works offline too).
 */
internal object DownloadCover {

    /**
     * Downloads [url] into `dir/cover.img` unless a cover is already there (or [overwrite] is set).
     * Returns true when a cover is present.
     */
    suspend fun save(
        client: OkHttpClient,
        url: String,
        dir: File,
        overwrite: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val target = File(dir, DownloadMeta.COVER_FILE)
        if (!overwrite && target.isFile && target.length() > 0L) return@withContext true
        val part = File(dir, target.name + DownloadMeta.PART_SUFFIX)
        part.delete()
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                part.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            if (!part.renameTo(target)) {
                // renameTo replacing an existing file is not guaranteed on every file system.
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            true
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            AppLog.w("Download", "cover failed: $url (${e.message})")
            false
        }
    }
}
