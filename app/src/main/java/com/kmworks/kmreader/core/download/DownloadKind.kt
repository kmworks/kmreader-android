package com.kmworks.kmreader.core.download

/**
 * What a download stores on disk.
 *
 * The kind is decided from the book's Komga media DTO when the download is enqueued, persisted in
 * meta.txt (`kind=...`) and in the enqueue metadata so pause/resume/retry never have to re-inspect
 * the book. Older download directories have no kind and read as [Pages] (one image per page), which
 * is what this app used to download for every book.
 */
enum class DownloadKind(val metaValue: String) {
    /**
     * One image file per page (`001.jpg`, `002.jpg`, ...). Used for Divina-compatible EPUBs, which
     * Komga renders server-side, and for archive formats whose pages we cannot read locally
     * (notably RAR/CBR), so page-level downloading stays the safe path for them.
     */
    Pages("pages"),

    /**
     * The archive itself (`book.cbz`) for ZIP-based comic archives. Divina pages are read out of
     * the local zip, which is cheaper to download than asking the server to render every page.
     */
    Archive("archive"),

    /** The whole PDF file (`book.pdf`); the PDF reader consumes it directly. */
    Pdf("pdf"),

    /** The whole EPUB file (`book.epub`); the EPUB reader serves resources out of the zip. */
    Epub("epub"),
    ;

    /** True when the download is one file per book rather than one file per page. */
    val isSingleFile: Boolean get() = this != Pages

    companion object {
        /** Parse the persisted `kind=` value; unknown/absent values mean legacy page downloads. */
        fun fromMeta(value: String?): DownloadKind =
            entries.firstOrNull { it.metaValue == value?.trim()?.lowercase() } ?: Pages
    }
}

/** The kind and container format chosen for a book, derived from its Komga media DTO. */
internal data class DownloadSpec(
    val kind: DownloadKind,
    val format: String,
) {
    companion object {
        const val FORMAT_IMAGES = "images"

        /**
         * Kind mapping:
         * - pdf media → [DownloadKind.Pdf]
         * - epub media → [DownloadKind.Pages] when Komga renders it (Divina-compatible), else [DownloadKind.Epub]
         * - zip-based comic archives (CBZ) → [DownloadKind.Archive]
         * - everything else (RAR/CBR, unknown media) → [DownloadKind.Pages]: the page API still works for
         *   these and downloading the container would leave us unable to read it offline.
         */
        fun forMedia(mediaType: String, epubDivinaCompatible: Boolean): DownloadSpec {
            val type = mediaType.trim().lowercase()
            return when {
                "pdf" in type -> DownloadSpec(DownloadKind.Pdf, "pdf")
                "epub" in type -> if (epubDivinaCompatible) {
                    DownloadSpec(DownloadKind.Pages, FORMAT_IMAGES)
                } else {
                    DownloadSpec(DownloadKind.Epub, "epub")
                }
                isZipComicArchive(type) -> DownloadSpec(DownloadKind.Archive, archiveFormat(type))
                else -> DownloadSpec(DownloadKind.Pages, FORMAT_IMAGES)
            }
        }

        /** True for zip containers we can open with java.util.zip at read time. */
        fun isZipComicArchive(mediaType: String): Boolean {
            val type = mediaType.trim().lowercase()
            return type.contains("comicbook+zip") ||
                type.contains("x-cbz") ||
                type == "application/zip" ||
                type.endsWith("+zip") && "epub" !in type
        }

        private fun archiveFormat(mediaType: String): String = when {
            mediaType.contains("comicbook+zip") || mediaType.contains("cbz") -> "cbz"
            else -> "zip"
        }
    }
}
