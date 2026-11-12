package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.ReadListDto
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
            .onEach { DetailIdRegistry.registerCollection(it.id.stableId(), it.id) }
    }

    /** All read lists (GET api/v1/readlists). */
    suspend fun readLists(): List<ReadListDto> {
        val api = sessionManager.clients.value?.readlists ?: return emptyList()
        return api.getReadLists(unpaged = true).awaitBody()?.content.orEmpty()
            .onEach { DetailIdRegistry.registerReadList(it.id.stableId(), it.id) }
    }
}
