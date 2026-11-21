package com.kmworks.kmreader.core.data

/**
 * The read list a reader session continues in (mirrors ReaderReadListContext).
 *
 * The reader resolves adjacent books through the read list's own order while this is set, and the end
 * page names the list instead of the series.
 */
data class ReaderReadListContext(
    val readListId: String,
    val name: String,
)
