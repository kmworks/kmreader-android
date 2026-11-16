package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.CollectionUpdateDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.api.model.ReadListUpdateDto
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Collections and read lists of the current instance; empty without a session. */
@Singleton
class ListRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    /** All collections (GET api/v1/collections). */
    suspend fun collections(): List<CollectionDto> {
        val api = sessionManager.clients.value?.collections ?: return emptyList()
        return api.getCollections(unpaged = true).awaitBody()?.content.orEmpty()
            .onEach {
                DetailIdRegistry.registerCollection(it.id.stableId(), it.id)
                CoverUrlRegistry.registerCollection(it.id.stableId(), it.id)
            }
    }

    /** All read lists (GET api/v1/readlists). */
    suspend fun readLists(): List<ReadListDto> {
        val api = sessionManager.clients.value?.readlists ?: return emptyList()
        return api.getReadLists(unpaged = true).awaitBody()?.content.orEmpty()
            .onEach {
                DetailIdRegistry.registerReadList(it.id.stableId(), it.id)
                CoverUrlRegistry.registerReadList(it.id.stableId(), it.id)
            }
    }

    /** One page of collections; registers stable id → dto id for detail pages. */
    suspend fun collectionsPage(page: Int = 0, size: Int = 20): Paged<CollectionDto> {
        val api = sessionManager.clients.value?.collections ?: return Paged(emptyList(), false)
        val body = api.getCollections(page = page, size = size).awaitBody()
            ?: return Paged(emptyList(), false)
        val items = body.content.orEmpty()
        items.forEach {
            DetailIdRegistry.registerCollection(it.id.stableId(), it.id)
            CoverUrlRegistry.registerCollection(it.id.stableId(), it.id)
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
            DetailIdRegistry.registerReadList(it.id.stableId(), it.id)
            CoverUrlRegistry.registerReadList(it.id.stableId(), it.id)
        }
        return Paged(items, hasMore = items.size >= size && body.last != true)
    }

    suspend fun updateCollection(dtoId: String, name: String, ordered: Boolean): Boolean {
        val api = sessionManager.clients.value?.collections ?: return false
        return api.updateCollectionById(dtoId, CollectionUpdateDto(name = name, ordered = ordered)).awaitBody() != null
    }

    suspend fun deleteCollection(dtoId: String): Boolean {
        val api = sessionManager.clients.value?.collections ?: return false
        return api.deleteCollectionById(dtoId).awaitBody() != null
    }

    suspend fun updateReadList(dtoId: String, name: String, summary: String, ordered: Boolean): Boolean {
        val api = sessionManager.clients.value?.readlists ?: return false
        return api.updateReadListById(
            dtoId,
            ReadListUpdateDto(name = name, summary = summary, ordered = ordered),
        ).awaitBody() != null
    }

    suspend fun deleteReadList(dtoId: String): Boolean {
        val api = sessionManager.clients.value?.readlists ?: return false
        return api.deleteReadListById(dtoId).awaitBody() != null
    }
}
