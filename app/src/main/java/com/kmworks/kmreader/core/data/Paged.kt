package com.kmworks.kmreader.core.data

/** One page of a Komga list endpoint: [items] plus whether another page exists. */
data class Paged<out T>(
    val items: List<T>,
    val hasMore: Boolean,
)
