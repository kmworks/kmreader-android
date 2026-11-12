package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Creator
import com.kmworks.kmreader.data.Series
import kotlin.math.absoluteValue

/** Stable Int id for UI models (Komga ids are strings). */
internal fun String.stableId(): Int = hashCode()

/** Deterministic placeholder hue from a stable key. */
internal fun hueFor(key: String): Float = (key.hashCode().absoluteValue % 360).toFloat()

private fun mapStatus(status: String): String = when (status.uppercase()) {
    "ENDED" -> "已完结"
    "ONGOING" -> "连载中"
    "ABANDONED" -> "已弃坑"
    "HIATUS" -> "休刊中"
    else -> status
}

private fun mapRole(role: String): AuthorRole = when (role.lowercase()) {
    "writer" -> AuthorRole.Writer
    "penciller" -> AuthorRole.Penciller
    "inker" -> AuthorRole.Inker
    "colorist" -> AuthorRole.Colorist
    "letterer" -> AuthorRole.Letterer
    "cover" -> AuthorRole.Cover
    "editor" -> AuthorRole.Editor
    "translator" -> AuthorRole.Translator
    else -> AuthorRole.Custom
}

/** Map a Komga series to the UI model (progress from read counts). */
fun SeriesDto.toUiSeries(): Series = Series(
    id = id.stableId(),
    title = metadata.title.ifBlank { name },
    author = booksMetadata.authors.joinToString(" / ") { it.name },
    hue = hueFor(id),
    bookCount = booksCount,
    unreadCount = booksUnreadCount,
    progress = when {
        booksCount <= 0 -> null
        booksReadCount == 0 && booksInProgressCount == 0 -> null
        else -> (booksReadCount.toFloat() / booksCount).coerceIn(0f, 1f)
    },
    publisher = metadata.publisher.ifBlank { null },
    status = mapStatus(metadata.status),
    language = metadata.language.ifBlank { null },
    directionRtl = metadata.readingDirection.contains("RIGHT", ignoreCase = true),
    totalBookCount = metadata.totalBookCount,
    ageRating = metadata.ageRating,
    creators = booksMetadata.authors.map { Creator(it.name, mapRole(it.role)) },
    genres = metadata.genres.toList(),
    tags = metadata.tags.toList(),
    links = metadata.links.map { it.label to it.url },
    summary = metadata.summary.ifBlank { null },
)

/** Map a Komga book to the UI model (progress from readProgress). */
fun BookDto.toUiBook(): Book {
    val n = metadata.number.ifBlank { number.toString() }
    val numberLabel = if (n.toFloatOrNull() != null) "第 $n 卷" else n
    return Book(
        id = id.stableId(),
        number = numberLabel,
        title = metadata.title.ifBlank { name },
        seriesTitle = seriesTitle,
        hue = hueFor(seriesId),
        pageCount = media.pagesCount,
        progress = readProgress?.let { p ->
            when {
                p.completed -> 1f
                media.pagesCount > 0 -> (p.page.toFloat() / media.pagesCount).coerceIn(0f, 1f)
                else -> 0f
            }
        },
    )
}
