package com.kmworks.kmreader.ui.lists

import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.data.SampleData.ListEntry

/**
 * The content an "add to list" picker acts on, in UI stable ids.
 *
 * It doubles as the seed of a list created from that picker: collections hold series, read lists hold books.
 * A picker over series can still create a read list — it then holds every book of those series.
 */
data class ListSelection(
    /** Series in context: members of a new collection, or the source of a new read list's books. */
    val seriesIds: List<Int> = emptyList(),
    /** Books in context: members of a new read list. Takes precedence over [seriesIds] when present. */
    val bookIds: List<Int> = emptyList(),
)

/**
 * Create a collection holding [seriesIds] (UI stable ids) and map it to a UI entry.
 *
 * A collection has to be born with its series: Komga rejects an empty `seriesIds` (`minItems: 1`), so the ids
 * are resolved to DTO ids and sent in the creation body. The follow-up "add" call is then a no-op
 * (ActionRepository skips ids the list already has).
 *
 * Returns null = no session, no resolvable series id, or the request failed.
 */
internal suspend fun newCollectionEntry(
    listRepository: ListRepository,
    detailRepository: DetailRepository,
    name: String,
    ordered: Boolean,
    seriesIds: Collection<Int>,
): ListEntry? = createCollectionEntry(
    listRepository = listRepository,
    name = name,
    ordered = ordered,
    seriesDtoIds = seriesIds.mapNotNull { detailRepository.seriesDtoId(it) },
)

/**
 * Create the read list the picker asked for and map it to a UI entry: seeded with the selected books, or with
 * every book of the selected series when the picker targeted series. Returns null like [newCollectionEntry].
 */
internal suspend fun newReadListEntry(
    listRepository: ListRepository,
    detailRepository: DetailRepository,
    name: String,
    summary: String,
    ordered: Boolean,
    selection: ListSelection,
): ListEntry? = if (selection.bookIds.isNotEmpty()) {
    newReadListEntryForBooks(listRepository, detailRepository, name, summary, ordered, selection.bookIds)
} else {
    newReadListEntryForSeries(listRepository, detailRepository, name, summary, ordered, selection.seriesIds)
}

/** Create a read list holding [bookIds] (UI stable ids). */
internal suspend fun newReadListEntryForBooks(
    listRepository: ListRepository,
    detailRepository: DetailRepository,
    name: String,
    summary: String,
    ordered: Boolean,
    bookIds: Collection<Int>,
): ListEntry? = createReadListEntry(
    listRepository = listRepository,
    name = name,
    summary = summary,
    ordered = ordered,
    bookDtoIds = bookIds.mapNotNull { detailRepository.bookDtoId(it) },
)

/** Create a read list holding every book of [seriesIds] (UI stable ids), in series order (GET …/books per series). */
internal suspend fun newReadListEntryForSeries(
    listRepository: ListRepository,
    detailRepository: DetailRepository,
    name: String,
    summary: String,
    ordered: Boolean,
    seriesIds: Collection<Int>,
): ListEntry? = createReadListEntry(
    listRepository = listRepository,
    name = name,
    summary = summary,
    ordered = ordered,
    bookDtoIds = seriesIds
        .mapNotNull { detailRepository.seriesDtoId(it) }
        .flatMap { seriesDtoId -> detailRepository.seriesBooks(seriesDtoId).map { it.id } },
)

/** Create a collection from resolved DTO ids; null = no session or the request failed. */
private suspend fun createCollectionEntry(
    listRepository: ListRepository,
    name: String,
    ordered: Boolean,
    seriesDtoIds: List<String>,
): ListEntry? = listRepository.createCollection(name, ordered, seriesDtoIds)?.let { dto ->
    ListEntry(StableIds.collection(dto.id), dto.name, 0, hueFor(dto.id), false)
}

/** Create a read list from resolved DTO ids; null = no session or the request failed. */
private suspend fun createReadListEntry(
    listRepository: ListRepository,
    name: String,
    summary: String,
    ordered: Boolean,
    bookDtoIds: List<String>,
): ListEntry? = listRepository.createReadList(name, summary, ordered, bookDtoIds)?.let { dto ->
    ListEntry(StableIds.readList(dto.id), dto.name, 0, hueFor(dto.id), false)
}
