package com.kmworks.kmreader.ui.lists

import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.data.SampleData.ListEntry

/**
 * Create a read list and map it to a UI entry (StableIds registration happens inside ListRepository).
 * Returns null = no session or the request failed.
 */
internal suspend fun newReadListEntry(
    listRepository: ListRepository,
    name: String,
    summary: String,
    ordered: Boolean,
): ListEntry? = listRepository.createReadList(name, summary, ordered)?.let {
    ListEntry(StableIds.readList(it.id), it.name, 0, hueFor(it.id), false)
}

/** Create a collection and map it to a UI entry; returns null = no session or the request failed. */
internal suspend fun newCollectionEntry(
    listRepository: ListRepository,
    name: String,
    ordered: Boolean,
): ListEntry? = listRepository.createCollection(name, ordered)?.let {
    ListEntry(StableIds.collection(it.id), it.name, 0, hueFor(it.id), false)
}
