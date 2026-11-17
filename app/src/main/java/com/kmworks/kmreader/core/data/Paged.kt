package com.kmworks.kmreader.core.data

/** One page of a Komga list endpoint: [items], whether another page exists, and the server-side total. */
data class Paged<out T>(
    val items: List<T>,
    val hasMore: Boolean,
    /** Total server-side matches (totalElements of paged endpoints); null for sources without a total. */
    val totalElements: Long? = null,
)
