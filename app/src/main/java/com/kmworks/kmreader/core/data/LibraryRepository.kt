package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Libraries of the current instance; empty without a session. */
@Singleton
class LibraryRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    suspend fun libraries(): List<LibraryDto> {
        val api = sessionManager.clients.value?.libraries ?: return emptyList()
        return api.getLibraries().awaitBody().orEmpty()
    }
}
