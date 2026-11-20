package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.CollectionCreationDto
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.CollectionUpdateDto
import com.kmworks.kmreader.core.api.model.ReadListCreationDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.ReadListUpdateDto
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

    /** One page of collections; registers stable id → dto id for detail pages. */
    suspend fun collectionsPage(page: Int = 0, size: Int = 20): Paged<CollectionDto> {
        val api = sessionManager.clients.value?.collections ?: return Paged(emptyList(), false)
        val body = api.getCollections(page = page, size = size).awaitBody()
            ?: return Paged(emptyList(), false)
        val items = body.content.orEmpty()
        items.forEach {
            DetailIdRegistry.registerCollection(StableIds.collection(it.id), it.id)
            CoverUrlRegistry.registerCollection(StableIds.collection(it.id), it.id)
        }
        return Paged(items, hasMore = items.size >= size && body.last != true)
    }

    /** One page of read lists; registers stable id → dto id for detail pages. */
    suspend fun readListsPage(page: Int = 0, size: Int = 20): Paged<ReadListDto> {
        val api = sessionManager.clients.value?.readlists ?: return Paged(emptyList(), false)
        val body = api.getReadLists(page = page, size = size).awaitBody()
            ?: return Paged(emptyList(), false)
        val items = body.content.orEmpty()
        items.forEach {
            DetailIdRegistry.registerReadList(StableIds.readList(it.id), it.id)
            CoverUrlRegistry.registerReadList(StableIds.readList(it.id), it.id)
        }
        return Paged(items, hasMore = items.size >= size && body.last != true)
    }

    /** Create a collection (POST api/v1/collections, starting empty; mirrors the original CollectionAddSheet). */
    suspend fun createCollection(name: String, ordered: Boolean): CollectionDto? {
        val api = sessionManager.clients.value?.collections ?: return null
        return api.createCollection(
            CollectionCreationDto(name = name, ordered = ordered, seriesIds = emptyList()),
        ).awaitBody()?.also {
            DetailIdRegistry.registerCollection(StableIds.collection(it.id), it.id)
            CoverUrlRegistry.registerCollection(StableIds.collection(it.id), it.id)
            contentChangeBus.notifyChanged()
        }
    }

    /** Create a read list (POST api/v1/readlists, starting empty; mirrors the original ReadListAddSheet). */
    suspend fun createReadList(name: String, summary: String, ordered: Boolean): ReadListDto? {
        val api = sessionManager.clients.value?.readlists ?: return null
        return api.createReadList(
            ReadListCreationDto(bookIds = emptyList(), name = name, ordered = ordered, summary = summary),
        ).awaitBody()?.also {
            DetailIdRegistry.registerReadList(StableIds.readList(it.id), it.id)
            CoverUrlRegistry.registerReadList(StableIds.readList(it.id), it.id)
            contentChangeBus.notifyChanged()
        }
    }

    suspend fun updateCollection(dtoId: String, name: String, ordered: Boolean): Boolean {
        val api = sessionManager.clients.value?.collections ?: return false
        return notifyOn(
            api.updateCollectionById(dtoId, CollectionUpdateDto(name = name, ordered = ordered)).awaitBody() != null,
        )
    }

    suspend fun deleteCollection(dtoId: String): Boolean {
        val api = sessionManager.clients.value?.collections ?: return false
        return notifyOn(api.deleteCollectionById(dtoId).awaitBody() != null)
    }

    suspend fun updateReadList(dtoId: String, name: String, summary: String, ordered: Boolean): Boolean {
        val api = sessionManager.clients.value?.readlists ?: return false
        return notifyOn(
            api.updateReadListById(
                dtoId,
                ReadListUpdateDto(name = name, summary = summary, ordered = ordered),
            ).awaitBody() != null,
        )
    }

    suspend fun deleteReadList(dtoId: String): Boolean {
        val api = sessionManager.clients.value?.readlists ?: return false
        return notifyOn(api.deleteReadListById(dtoId).awaitBody() != null)
    }

    /** Propagate successful writes to listeners (dashboard) that would otherwise wait for an SSE round trip. */
    private fun notifyOn(ok: Boolean): Boolean {
        if (ok) contentChangeBus.notifyChanged()
        return ok
    }
}
