package com.kmworks.kmreader.core.download

/** Downloaded-book UI model (file scan + meta file). */
data class DownloadedBookUi(
    val bookDtoId: String,
    val title: String,
    val pageCount: Int,
    val sizeBytes: Long,
    /** Owning library id (recorded at enqueue time; null for historical downloads without a record — those show only under "All Libraries"). */
    val libraryId: String? = null,
    /** What was downloaded (legacy directories without a kind read as [DownloadKind.Pages]). */
    val kind: DownloadKind = DownloadKind.Pages,
    /** Container format of a single-file download (`pdf`/`epub`/`cbz`); `images` for page downloads. */
    val format: String = DownloadSpec.FORMAT_IMAGES,
)
