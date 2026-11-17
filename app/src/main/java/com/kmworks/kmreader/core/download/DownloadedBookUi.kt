package com.kmworks.kmreader.core.download

/** Downloaded-book UI model (file scan + meta file). */
data class DownloadedBookUi(
    val bookDtoId: String,
    val title: String,
    val pageCount: Int,
    val sizeBytes: Long,
    /** Owning library id (recorded at enqueue time; null for historical downloads without a record — those show only under "All Libraries"). */
    val libraryId: String? = null,
)
