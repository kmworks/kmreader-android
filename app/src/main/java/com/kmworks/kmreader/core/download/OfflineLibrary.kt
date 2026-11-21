package com.kmworks.kmreader.core.download

import android.content.Context
import com.kmworks.kmreader.core.epub.EpubManifestDto
import com.kmworks.kmreader.core.epub.EpubResource
import com.kmworks.kmreader.core.epub.EpubZip
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single place that knows where a downloaded book lives on disk.
 *
 * Readers used to talk to the server only; this layer maps a book id to its local page files,
 * archive entries, EPUB zip, or PDF file so the readers can try local content first and fall
 * back to the network. The on-disk layout is produced by [DownloadWorker]:
 *
 * ```
 * filesDir/downloads/<bookId>/
 *   meta.txt            title, pageCount, kind, format
 *   001.jpg ...         DownloadKind.Pages page images
 *   book.epub           DownloadKind.Epub
 *   book.pdf            DownloadKind.Pdf
 *   book.cbz            DownloadKind.Archive (zip-based comics)
 * ```
 */
@Singleton
class OfflineLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val downloadsRoot = File(context.filesDir, "downloads")
    private val pageCacheRoot = File(context.cacheDir, "offline-pages")
    private val archiveEntryCache = ConcurrentHashMap<String, List<String>>()

    fun bookDir(bookDtoId: String): File = File(downloadsRoot, bookDtoId)

    fun meta(bookDtoId: String): DownloadMeta? = DownloadMeta.read(bookDir(bookDtoId))

    fun kind(bookDtoId: String): DownloadKind? = meta(bookDtoId)?.kind

    fun isDownloaded(bookDtoId: String): Boolean {
        val dir = bookDir(bookDtoId)
        return DownloadMeta.read(dir)?.isComplete(dir) == true
    }

    fun pageCount(bookDtoId: String): Int = meta(bookDtoId)?.pageCount ?: 0

    /** Page images for [DownloadKind.Pages] downloads, keyed by page number. */
    fun pageFiles(bookDtoId: String): Map<Int, File> {
        val dir = bookDir(bookDtoId)
        val meta = DownloadMeta.read(dir) ?: return emptyMap()
        return meta.pageFiles(dir)
            .mapNotNull { file -> pageNumberOf(file.name)?.let { it to file } }
            .toMap()
    }

    /** One-based page numbers available offline for this book. */
    suspend fun pageNumbers(bookDtoId: String): List<Int> = withContext(Dispatchers.IO) {
        val dir = bookDir(bookDtoId)
        val meta = DownloadMeta.read(dir) ?: return@withContext emptyList()
        when (meta.kind) {
            DownloadKind.Pages ->
                meta.pageFiles(dir).mapNotNull { pageNumberOf(it.name) }.sorted()
            DownloadKind.Archive ->
                archiveEntries(bookDtoId).indices.map { it + 1 }
            else ->
                (1..meta.pageCount).toList()
        }
    }

    /** Sorted image entry names inside the downloaded archive (empty when not an archive). */
    suspend fun archiveEntries(bookDtoId: String): List<String> = withContext(Dispatchers.IO) {
        val dir = bookDir(bookDtoId)
        val meta = DownloadMeta.read(dir) ?: return@withContext emptyList()
        val archive = meta.contentFile(dir) ?: return@withContext emptyList()
        val cacheKey = "${archive.absolutePath}@${archive.length()}"
        archiveEntryCache[cacheKey]?.let { return@withContext it }
        val entries = runCatching {
            ZipFile(archive).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && EpubZip.isImageEntry(it.name) }
                    .map { it.name }
                    .sortedWith(NaturalOrder)
                    .toList()
            }
        }.getOrDefault(emptyList())
        archiveEntryCache[cacheKey] = entries
        entries
    }

    /**
     * Extracts one archive page into the cache directory on demand.
     *
     * Bulk extraction is intentionally avoided: a downloaded CBZ may hold hundreds of pages,
     * which would duplicate the archive on disk. The extracted file is cached by page number
     * and reused until the archive changes.
     */
    suspend fun extractArchivePage(bookDtoId: String, pageNumber: Int): File? =
        withContext(Dispatchers.IO) {
            val entries = archiveEntries(bookDtoId)
            val entry = entries.getOrNull(pageNumber - 1) ?: return@withContext null
            val dir = bookDir(bookDtoId)
            val archive = DownloadMeta.read(dir)?.contentFile(dir) ?: return@withContext null
            val target = extractedPage(bookDtoId, pageNumber)
            if (target.isFile && target.length() > 0L && target.lastModified() >= archive.lastModified()) {
                return@withContext target
            }
            runCatching {
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, "${target.name}.part")
                ZipFile(archive).use { zip ->
                    val zipEntry = zip.getEntry(entry) ?: return@withContext null
                    zip.getInputStream(zipEntry).use { input ->
                        part.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                target
            }.getOrNull()
        }

    fun extractedPage(bookDtoId: String, pageNumber: Int): File =
        File(File(pageCacheRoot, bookDtoId), "%04d.img".format(pageNumber))

    fun epubFile(bookDtoId: String): File? = contentFile(bookDtoId, DownloadKind.Epub)

    fun pdfFile(bookDtoId: String): File? = contentFile(bookDtoId, DownloadKind.Pdf)

    /** A single resource out of the downloaded EPUB, or null when it is not available locally. */
    suspend fun epubResource(bookDtoId: String, href: String): EpubResource? =
        withContext(Dispatchers.IO) {
            epubResourceBlocking(bookDtoId, href)
        }

    /**
     * Blocking variant for [android.webkit.WebViewClient.shouldInterceptRequest], which calls the
     * resolver synchronously on a background thread.
     */
    fun epubResourceBlocking(bookDtoId: String, href: String): EpubResource? {
        val epub = epubFile(bookDtoId) ?: return null
        return runCatching { EpubZip.resource(epub, href) }.getOrNull()
    }

    /** The WebPub manifest rebuilt from the downloaded EPUB (needed when the server is unreachable). */
    suspend fun epubManifest(bookDtoId: String): EpubManifestDto? = withContext(Dispatchers.IO) {
        val epub = epubFile(bookDtoId) ?: return@withContext null
        runCatching { EpubZip.manifest(epub) }.getOrNull()
    }

    private fun contentFile(bookDtoId: String, expectedKind: DownloadKind): File? {
        val dir = bookDir(bookDtoId)
        val meta = DownloadMeta.read(dir) ?: return null
        if (meta.kind != expectedKind) return null
        return meta.contentFile(dir)?.takeIf { it.isFile && it.length() > 0L }
    }

    private fun pageNumberOf(fileName: String): Int? =
        fileName.substringBefore('.').toIntOrNull()

    /** Natural order so `page2` sorts before `page10` (matches comic page naming). */
    private object NaturalOrder : Comparator<String> {
        override fun compare(first: String, second: String): Int {
            var i = 0
            var j = 0
            while (i < first.length && j < second.length) {
                val a = first[i]
                val b = second[j]
                if (a.isDigit() && b.isDigit()) {
                    val startA = i
                    val startB = j
                    while (i < first.length && first[i].isDigit()) i++
                    while (j < second.length && second[j].isDigit()) j++
                    val numberA = first.substring(startA, i).trimStart('0').ifEmpty { "0" }
                    val numberB = second.substring(startB, j).trimStart('0').ifEmpty { "0" }
                    val byLength = numberA.length.compareTo(numberB.length)
                    if (byLength != 0) return byLength
                    val byValue = numberA.compareTo(numberB)
                    if (byValue != 0) return byValue
                } else {
                    val byChar = a.lowercaseChar().compareTo(b.lowercaseChar())
                    if (byChar != 0) return byChar
                    i++
                    j++
                }
            }
            return (first.length - i).compareTo(second.length - j)
        }
    }
}
