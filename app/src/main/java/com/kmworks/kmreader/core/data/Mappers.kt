package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.AuthorDto
import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Creator
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.SmartListTarget
import com.kmworks.kmreader.data.Series
import kotlin.math.absoluteValue

/** Deterministic placeholder hue from a stable key. */
internal fun hueFor(key: String): Float = (key.hashCode().absoluteValue % 360).toFloat()

/**
 * Normalize the Komga series status to a stable token (ENDED/ONGOING/ABANDONED/HIATUS);
 * unrecognized statuses pass through unchanged. The UI resolves display labels via R.string.core_status_*.
 */
private fun mapStatus(status: String): String {
    val token = status.uppercase()
    return if (token in KNOWN_STATUS_TOKENS) token else status
}

private val KNOWN_STATUS_TOKENS = setOf("ENDED", "ONGOING", "ABANDONED", "HIATUS")

/** Role vocabulary: Komga's role is free text; unmatched roles keep their original text as a custom role. */
private fun mapRole(role: String): AuthorRole = when (role.lowercase()) {
    "writer", "author" -> AuthorRole.Writer
    "penciller", "artist", "illustrator" -> AuthorRole.Penciller
    "inker" -> AuthorRole.Inker
    "colorist" -> AuthorRole.Colorist
    "letterer" -> AuthorRole.Letterer
    "cover" -> AuthorRole.Cover
    "editor" -> AuthorRole.Editor
    "translator" -> AuthorRole.Translator
    else -> AuthorRole.Custom
}

/** Map a Komga author to the UI creator model (custom role text preserved). */
fun AuthorDto.toCreator(): Creator = Creator(
    name = name,
    role = mapRole(role),
    customRole = role.takeIf { mapRole(it) == AuthorRole.Custom },
)

/** Map a Komga series to the UI model (progress from read counts). */
fun SeriesDto.toUiSeries(): Series {
    val stableId = StableIds.series(id)
    DetailIdRegistry.registerSeries(stableId, id)
    CoverUrlRegistry.registerSeries(stableId, id)
    return Series(
        id = stableId,
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
    // Keep the raw token: "LEFT_TO_RIGHT" also contains the substring "RIGHT", so a contains() test
    // here used to mark every LTR series as RTL (and VERTICAL/WEBTOON as LTR).
    readingDirection = metadata.readingDirection.ifBlank { "LEFT_TO_RIGHT" },
    totalBookCount = metadata.totalBookCount,
    ageRating = metadata.ageRating,
    creators = booksMetadata.authors.map { it.toCreator() },
    // The server guarantees lowercase kind; still defensively trim+lowercase as on iOS
    notableEntities = booksMetadata.notableEntities.map { it.toNotableEntity() },
    genres = metadata.genres.toList(),
    tags = metadata.tags.toList(),
    links = metadata.links.map { it.label to it.url },
    summary = metadata.summary.ifBlank { null },
    oneshot = oneshot,
    )
}

/** Map a Komga book to the UI model (progress from readProgress). */
fun BookDto.toUiBook(): Book {
    val stableId = StableIds.book(id)
    DetailIdRegistry.registerBook(stableId, id)
    CoverUrlRegistry.registerBook(stableId, id)
    val n = metadata.number.ifBlank { number.toString() }
    // Raw volume/issue number; the UI formats the display label via R.string.core_volume_number.
    return Book(
        id = stableId,
        number = n,
        title = metadata.title.ifBlank { name },
        seriesTitle = seriesTitle,
        seriesDtoId = seriesId,
        hue = hueFor(seriesId),
        pageCount = media.pagesCount,
        progress = readProgress?.let { p ->
            when {
                p.completed -> 1f
                media.pagesCount > 0 -> (p.page.toFloat() / media.pagesCount).coerceIn(0f, 1f)
                else -> 0f
            }
        },
        oneshot = oneshot,
        author = metadata.authors.firstOrNull()?.name?.ifBlank { null },
        mediaStatus = media.status,
        // Carried on the row so the reader opens without a format lookup (EPUB / PDF / DIVINA).
        mediaType = media.mediaType,
        epubDivinaCompatible = media.epubDivinaCompatible,
        deleted = deleted,
        links = metadata.links.map { it.label to it.url },
        notableEntities = metadata.notableEntities.map { it.toNotableEntity() },
        lastReadAtMs = readProgress?.readDate?.let(::parseInstantMs),
    )
}

/** ISO-8601 → epoch millis; Komga emits offsets (OffsetDateTime, incl. Z), parse failures silently fall back to null. */
private fun parseInstantMs(value: String): Long? =
    runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()

/** Map a Komga notable entity to the UI model (kind defensively trimmed + lowercased, mirroring iOS). */
private fun com.kmworks.kmreader.core.api.model.NotableEntityDto.toNotableEntity(): NotableEntity =
    NotableEntity(name = name, kind = kind.trim().lowercase())

/** Map a Komga read list to the UI list entry (count = books). */
fun ReadListDto.toListEntry(): SampleData.ListEntry {
    val stableId = StableIds.readList(id)
    DetailIdRegistry.registerReadList(stableId, id)
    CoverUrlRegistry.registerReadList(stableId, id)
    return SampleData.ListEntry(
        id = stableId,
        title = name,
        count = bookIds.size,
        hue = hueFor(id),
        pinned = false,
        summary = summary,
        ordered = ordered,
    )
}

/** Map a Komga collection to the UI list entry (count = series). */
fun CollectionDto.toListEntry(): SampleData.ListEntry {
    val stableId = StableIds.collection(id)
    DetailIdRegistry.registerCollection(stableId, id)
    CoverUrlRegistry.registerCollection(stableId, id)
    return SampleData.ListEntry(
        id = stableId,
        title = name,
        count = seriesIds.size,
        hue = hueFor(id),
        pinned = false,
        // Komga CollectionDto has no summary field (unlike ReadListDto); omit it to avoid overwriting.
        ordered = ordered,
    )
}

/** Map a kmrs smart list to the UI list entry (count is resolved by the source screen). */
fun SmartListDto.toListEntry(count: Int = 0): SampleData.ListEntry {
    val stableId = StableIds.smartList(id)
    DetailIdRegistry.registerSmartList(stableId, id)
    CoverUrlRegistry.registerSmartList(stableId, id)
    return SampleData.ListEntry(
        id = stableId,
        title = name,
        count = count,
        hue = hueFor(id),
        pinned = false,
        summary = summary,
        smartListTarget = if (target == "SERIES") SmartListTarget.Series else SmartListTarget.Book,
        search = search,
        visibility = visibility,
        sharedWithUserIds = sharedWithUserIds,
    )
}
