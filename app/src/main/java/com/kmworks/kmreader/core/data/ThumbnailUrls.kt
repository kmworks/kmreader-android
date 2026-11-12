package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.network.ApiClients

/** Komga thumbnail endpoints (imageLoader carries the same auth as the API client). */
fun ApiClients.seriesThumbnailUrl(seriesId: String): String =
    "$baseUrl/api/v1/series/$seriesId/thumbnail"

fun ApiClients.bookThumbnailUrl(bookId: String): String =
    "$baseUrl/api/v1/books/$bookId/thumbnail"
