package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.CollectionCreationDto
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.CollectionUpdateDto
import com.kmworks.kmreader.core.api.model.ReadListCreationDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.ReadListUpdateDto
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Collections and read lists of the current instance; empty without a session. */
@Singleton
class ListRepository @Inject constructor(
    private val sessionManager: SessionManager,
    private val contentChangeBus: ContentChangeBus,
) {

    /** All collections (GET api/v1/collections). */
    suspend fun collections(): List<CollectionDto> {
        val api = sessionManager.clients.value?.collections ?: return emptyList()
        return api.getCollections(unpaged = true).awaitBody()?.content.orEmpty()
            .onEach {
                DetailIdRegistry.registerCollection(StableIds.collection(it.id), it.id)
                CoverUrlRegistry.registerCollection(StableIds.collection(it.id), it.id)
            }
    }

    /** All read lists (GET api/v1/readlists). */
    suspend fun readLists(): List<ReadListDto> {
        val api = sessionManager.clients.value?.readlists ?: return emptyList()
        return api.getReadLists(unpaged = true).awaitBody()?.content.orEmpty()
            .onEach {
                DetailIdRegistry.registerReadList(StableIds.readList(it.id), it.id)
                CoverUrlRegistry.registerReadList(StableIds.readList(it.id), it.id)
            }
    }

    /** One page of collections, optionally limited to [libraryIds] (GET api/v1/collections?library_id=…); registers stable id → dto id for detail pages. */
    suspend fun collectionsPage(page: Int = 0, size: Int = 20, libraryIds: List<String>? = null): Paged<CollectionDto> {
        val api = sessionManager.clients.value?.collections ?: return Paged(emptyList(), false)
        val body = api.getCollections(page = page, size = size, libraryId = libraryIds).awaitBody()
            ?: return Paged(emptyList(), false)
        val items = body.content.orEmpty()
        items.forEach {
            DetailIdRegistry.registerCollection(StableIds.collection(it.id), it.id)
            CoverUrlRegistry.registerCollection(StableIds.collection(it.id), it.id)
        }
        return Paged(items, hasMore = items.size >= size && body.last != true)
    }

    /** One page of read lists, optionally limited to [libraryIds] (GET api/v1/readlists?library_id=…); registers stable id → dto id for detail pages. */
    suspend fun readListsPage(page: Int = 0, size: Int = 20, libraryIds: List<String>? = null): Paged<ReadListDto> {
        val api = sessionManager.clients.value?.readlists ?: return Paged(emptyList(), false)
        val body = api.getReadLists(page = page, size = size, libraryId = libraryIds).awaitBody()
            ?: return Paged(emptyList(), false)
        val items = body.content.orEmpty()
        items.forEach {
            DetailIdRegistry.registerReadList(StableIds.readList(it.id), it.id)
            CoverUrlRegistry.registerReadList(StableIds.readList(it.id), it.id)
        }
        return Paged(items, hasMore = items.size >= size && body.last != true)
    }

    /**
     * Create a collection holding [seriesDtoIds] (POST api/v1/collections).
     *
     * Komga validates `seriesIds` with `minItems: 1` (see CollectionCreationDto in api/openapi.json), so
     * asking for an empty collection always answers 400. Such a request is refused locally instead, and
     * the callers always pass the series they are about to add.
     */
    suspend fun createCollection(name: String, ordered: Boolean, seriesDtoIds: List<String>): CollectionDto? {
        if (seriesDtoIds.isEmpty()) {
            AppLog.w("Lists", "createCollection('$name') without series ids")
            return null
        }
        val api = sessionManager.clients.value?.collections ?: return null
        return api.createCollection(
            CollectionCreationDto(name = name, ordered = ordered, seriesIds = seriesDtoIds.distinct()),
        ).awaitBody()?.also {
            DetailIdRegistry.registerCollection(StableIds.collection(it.id), it.id)
            CoverUrlRegistry.registerCollection(StableIds.collection(it.id), it.id)
            contentChangeBus.notify(collectionsChanged(listOf(it.id), ContentChangeKind.Created))
        }
    }

    /**
     * Create a read list holding [bookDtoIds] (POST api/v1/readlists).
     *
     * Komga validates `bookIds` with `minItems: 1`, so an empty list is refused locally instead of
     * sending a request that can only answer 400.
     */
    suspend fun createReadList(
        name: String,
        summary: String,
        ordered: Boolean,
        bookDtoIds: List<String>,
    ): ReadListDto? {
        if (bookDtoIds.isEmpty()) {
            AppLog.w("Lists", "createReadList('$name') without book ids")
            return null
        }
        val api = sessionManager.clients.value?.readlists ?: return null
        return api.createReadList(
            ReadListCreationDto(bookIds = bookDtoIds.distinct(), name = name, ordered = ordered, summary = summary),
        ).awaitBody()?.also {
            DetailIdRegistry.registerReadList(StableIds.readList(it.id), it.id)
            CoverUrlRegistry.registerReadList(StableIds.readList(it.id), it.id)
            contentChangeBus.notify(readListsChanged(listOf(it.id), ContentChangeKind.Created))
        }
    }

    /** Rename a collection (PATCH api/v1/collections/{id}, 204 without a body — see [awaitSuccess]). */
    suspend fun updateCollection(dtoId: String, name: String, ordered: Boolean): Boolean {
        val api = sessionManager.clients.value?.collections ?: return false
        val ok = api.updateCollectionById(dtoId, CollectionUpdateDto(name = name, ordered = ordered))
            .awaitSuccess()
        return notifyOn(ok) { collectionsChanged(listOf(dtoId), ContentChangeKind.MetadataChanged) }
    }

    /** Delete a collection (DELETE api/v1/collections/{id}, 204 without a body — see [awaitSuccess]). */
    suspend fun deleteCollection(dtoId: String): Boolean {
        val api = sessionManager.clients.value?.collections ?: return false
        val ok = api.deleteCollectionById(dtoId).awaitSuccess()
        return notifyOn(ok) { collectionsChanged(listOf(dtoId), ContentChangeKind.Deleted) }
    }

    /** Rename a read list (PATCH api/v1/readlists/{id}, 204 without a body — see [awaitSuccess]). */
    suspend fun updateReadList(dtoId: String, name: String, summary: String, ordered: Boolean): Boolean {
        val api = sessionManager.clients.value?.readlists ?: return false
        val ok = api.updateReadListById(
            dtoId,
            ReadListUpdateDto(name = name, summary = summary, ordered = ordered),
        ).awaitSuccess()
        return notifyOn(ok) { readListsChanged(listOf(dtoId), ContentChangeKind.MetadataChanged) }
    }

    /** Delete a read list (DELETE api/v1/readlists/{id}, 204 without a body — see [awaitSuccess]). */
    suspend fun deleteReadList(dtoId: String): Boolean {
        val api = sessionManager.clients.value?.readlists ?: return false
        val ok = api.deleteReadListById(dtoId).awaitSuccess()
        return notifyOn(ok) { readListsChanged(listOf(dtoId), ContentChangeKind.Deleted) }
    }

    /** Propagate a successful write to the screens showing that list; the change is built only on success. */
    private inline fun notifyOn(ok: Boolean, change: () -> ContentChange): Boolean {
        if (ok) contentChangeBus.notify(change())
        return ok
    }
}
