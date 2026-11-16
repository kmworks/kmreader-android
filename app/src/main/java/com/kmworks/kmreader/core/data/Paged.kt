package com.kmworks.kmreader.core.data

/** One page of a Komga list endpoint: [items], whether another page exists, and the server-side total. */
data class Paged<out T>(
    val items: List<T>,
    val hasMore: Boolean,
    /** 服务端匹配总数（分页端点的 totalElements）；不支持总数的来源为 null。 */
    val totalElements: Long? = null,
)
