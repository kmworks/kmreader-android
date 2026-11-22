package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * Smart list repository (kmrs private endpoint).
 * Capability gating: stock Komga has no such endpoint and answers 404 — list queries always return empty on
 * failure (the caller hides the section instead of erroring); single-item/content queries return null/empty
 * and the UI falls back to fake data.
 */
@Singleton
class SmartListRepository @Inject constructor(
    private val sessionManager: SessionManager,
    private val contentChangeBus: ContentChangeBus,
) {

    fun smartListDtoId(stableId: Int): String? = DetailIdRegistry.smartListDtoId(stableId)

    fun smartListThumbnailUrl(smartListId: String): String? =
        sessionManager.clients.value?.smartListThumbnailUrl(smartListId)

    fun bookThumbnailUrl(bookId: String): String? =
        sessionManager.clients.value?.bookThumbnailUrl(bookId)

    fun seriesThumbnailUrl(seriesId: String): String? =
        sessionManager.clients.value?.seriesThumbnailUrl(seriesId)

    /** One page of smart lists; on failure (404 etc. — capability missing) returns an empty page. */
    suspend fun smartListsPage(page: Int, size: Int): Paged<SmartListDto> {
        val api = sessionManager.clients.value?.smartLists ?: return Paged(emptyList(), hasMore = false)
        val body = api.getSmartLists(page = page, size = size).awaitBody()
        val items = body?.content.orEmpty()
        items.forEach { DetailIdRegistry.registerSmartList(StableIds.smartList(it.id), it.id) }
        return Paged(items, hasMore = items.size >= size && body?.last != true)
    }

    suspend fun smartListDetail(dtoId: String): SmartListDto? {
        val api = sessionManager.clients.value?.smartLists ?: return null
        return api.getSmartList(dtoId).awaitBody()
            ?.also { DetailIdRegistry.registerSmartList(StableIds.smartList(it.id), it.id) }
    }

    /** Matched books (body passes through the search document stored server-side; defaults to an empty document). */
    suspend fun smartListBooks(
        dtoId: String,
        search: JsonObject?,
        page: Int = 0,
        size: Int = 50,
    ): Paged<BookDto> {
        val api = sessionManager.clients.value?.smartLists ?: return Paged(emptyList(), hasMore = false)
        val body = api.getSmartListBooks(
            id = dtoId,
            search = search ?: JsonObject(emptyMap()),
            page = page,
            size = size,
            sort = listOf("metadata.numberSort,asc"),
        ).awaitBody()
        val items = body?.content.orEmpty()
        return Paged(items, hasMore = items.size >= size && body?.last != true)
    }

    /** Matched series. */
    suspend fun smartListSeries(
        dtoId: String,
        search: JsonObject?,
        page: Int = 0,
        size: Int = 50,
    ): Paged<SeriesDto> {
        val api = sessionManager.clients.value?.smartLists ?: return Paged(emptyList(), hasMore = false)
        val body = api.getSmartListSeries(
            id = dtoId,
            search = search ?: JsonObject(emptyMap()),
            page = page,
            size = size,
            sort = listOf("metadata.titleSort,asc"),
        ).awaitBody()
        val items = body?.content.orEmpty()
        return Paged(items, hasMore = items.size >= size && body?.last != true)
    }

    /** Create (target: BOOK | SERIES). Sharing fields are admin-only and left null for regular users. */
    suspend fun createSmartList(
        name: String,
        summary: String,
        target: String,
        search: JsonObject,
        visibility: String? = null,
        sharedWithUserIds: List<String>? = null,
    ): SmartListDto? {
        val api = sessionManager.clients.value?.smartLists ?: return null
        return api.createSmartList(
            SmartListCreateDto(
                name = name,
                summary = summary,
                target = target,
                search = search,
                visibility = visibility,
                sharedWithUserIds = sharedWithUserIds,
            ),
        ).awaitBody()?.also {
            DetailIdRegistry.registerSmartList(StableIds.smartList(it.id), it.id)
            contentChangeBus.notify(smartListsChanged(listOf(it.id), ContentChangeKind.Created))
        }
    }

    /** Update name/summary/criteria/sharing; null fields are omitted so existing server values survive. */
    suspend fun updateSmartList(
        dtoId: String,
        name: String,
        summary: String,
        search: JsonObject?,
        visibility: String? = null,
        sharedWithUserIds: List<String>? = null,
    ): Boolean {
        val api = sessionManager.clients.value?.smartLists ?: return false
        val ok = api.updateSmartList(
            id = dtoId,
            body = SmartListUpdateDto(
                name = name,
                summary = summary,
                search = search,
                visibility = visibility,
                sharedWithUserIds = sharedWithUserIds,
            ),
        ).awaitSuccess()
        if (ok) contentChangeBus.notify(smartListsChanged(listOf(dtoId), ContentChangeKind.MetadataChanged))
        return ok
    }

    suspend fun deleteSmartList(dtoId: String): Boolean {
        val api = sessionManager.clients.value?.smartLists ?: return false
        val ok = api.deleteSmartList(dtoId).awaitSuccess()
        if (ok) contentChangeBus.notify(smartListsChanged(listOf(dtoId), ContentChangeKind.Deleted))
        return ok
    }

    /** Pickable share targets; empty without a session or for non-admins (the endpoint is admin-only). */
    suspend fun shareTargets(): List<SmartListShareTargetDto> {
        val api = sessionManager.clients.value?.smartLists ?: return emptyList()
        return api.getSmartListShareTargets().awaitBody().orEmpty()
    }
}
