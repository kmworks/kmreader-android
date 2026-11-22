package com.kmworks.kmreader.core.download

import java.io.File

/**
 * `meta.txt` of one download directory.
 *
 * Layout (`key=value` per line): `title`, `pageCount`, and — for downloads created since the
 * kind-aware downloader — `kind` and `format`. Directories written by older versions only have
 * `title`/`pageCount`; they parse as [DownloadKind.Pages] with [DownloadSpec.FORMAT_IMAGES].
 *
 * A completed download also keeps its book cover as [COVER_FILE] next to the content, so the offline
 * list never has to ask whichever server happens to be active for a book downloaded from another one.
 */
data class DownloadMeta(
    val title: String?,
    val pageCount: Int,
    val kind: DownloadKind,
    val format: String,
) {
    /** Container extension of a single-file download (`pdf`, `epub`, `cbz`); unused for page downloads. */
    val fileFormat: String
        get() = format.ifBlank { if (kind == DownloadKind.Pages) DownloadSpec.FORMAT_IMAGES else "bin" }

    /** The downloaded container of a single-file download, when present and non-empty. */
    fun contentFile(dir: File): File? =
        if (kind == DownloadKind.Pages) {
            null
        } else {
            File(dir, fileName(fileFormat)).takeIf { it.isFile && it.length() > 0 }
        }

    /** All page images on disk (legacy directories keep them in the download directory root). */
    fun pageFiles(dir: File): List<File> =
        dir.listFiles().orEmpty().filter {
            it.isFile &&
                it.name != COVER_FILE &&
                !it.name.endsWith(PART_SUFFIX) &&
                it.name != DownloadWorker.META_FILE
        }

    /** The locally saved cover, when the download has one. */
    fun coverFile(dir: File): File? =
        File(dir, COVER_FILE).takeIf { it.isFile && it.length() > 0L }

    /** A download is complete when every page is present, or the single file is present. */
    fun isComplete(dir: File): Boolean = when (kind) {
        DownloadKind.Pages -> pageCount > 0 && pageFiles(dir).size >= pageCount
        else -> contentFile(dir) != null
    }

    companion object {
        const val PART_SUFFIX = ".part"

        /** Cover image saved with the download; not a page and not part of the content file. */
        const val COVER_FILE = "cover.img"

        fun read(dir: File): DownloadMeta? {
            val file = File(dir, DownloadWorker.META_FILE).takeIf { it.isFile } ?: return null
            val props = file.readLines().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index > 0) line.substring(0, index) to line.substring(index + 1) else null
            }.toMap()
            val pageCount = props["pageCount"]?.toIntOrNull() ?: return null
            return DownloadMeta(
                title = props["title"],
                pageCount = pageCount,
                kind = DownloadKind.fromMeta(props["kind"]),
                format = props["format"].orEmpty(),
            )
        }

        fun write(dir: File, title: String, pageCount: Int, kind: DownloadKind, format: String) {
            File(dir, DownloadWorker.META_FILE).writeText(
                buildString {
                    append("title=").append(title).append('\n')
                    append("pageCount=").append(pageCount).append('\n')
                    append("kind=").append(kind.metaValue).append('\n')
                    append("format=").append(format).append('\n')
                },
            )
        }

        /** Container file name of a single-file download. */
        fun fileName(format: String): String = "book.$format"

        /** Number of fully written page files in a download directory (partials, cover and meta excluded). */
        fun pageFileCount(dir: File): Int =
            dir.listFiles().orEmpty().count {
                it.isFile &&
                    it.name != COVER_FILE &&
                    !it.name.endsWith(PART_SUFFIX) &&
                    it.name != DownloadWorker.META_FILE
            }
    }
}
