package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * Smart list repository（kmrs 私有端点）。
 * 能力门控：stock Komga 无此端点会 404 —— 列表查询失败一律返回空（调用方隐藏该组，
 * 不报错）；单条/内容查询返回 null/空，UI 回退假数据。
 */
@Singleton
class SmartListRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    fun smartListDtoId(stableId: Int): String? = DetailIdRegistry.smartListDtoId(stableId)

    fun smartListThumbnailUrl(smartListId: String): String? =
        sessionManager.clients.value?.smartListThumbnailUrl(smartListId)

    fun bookThumbnailUrl(bookId: String): String? =
        sessionManager.clients.value?.bookThumbnailUrl(bookId)

    fun seriesThumbnailUrl(seriesId: String): String? =
        sessionManager.clients.value?.seriesThumbnailUrl(seriesId)

    /** 全部智能列表（unpaged）；失败（404 等能力缺失）返回空列表。 */
    suspend fun smartLists(): List<SmartListDto> {
        val api = sessionManager.clients.value?.smartLists ?: return emptyList()
        val list = api.getSmartLists(unpaged = true).awaitBody()?.content.orEmpty()
        list.forEach { DetailIdRegistry.registerSmartList(it.id.hashCode(), it.id) }
        return list
    }

    suspend fun smartListDetail(dtoId: String): SmartListDto? {
        val api = sessionManager.clients.value?.smartLists ?: return null
        return api.getSmartList(dtoId).awaitBody()
            ?.also { DetailIdRegistry.registerSmartList(it.id.hashCode(), it.id) }
    }

    /** 命中书籍（body 直接透传服务端存的 search 文档，缺省空文档）。 */
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

    /** 命中系列。 */
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

    /** 新建（target: BOOK | SERIES）。 */
    suspend fun createSmartList(
        name: String,
        summary: String,
        target: String,
        search: JsonObject,
    ): SmartListDto? {
        val api = sessionManager.clients.value?.smartLists ?: return null
        return api.createSmartList(
            SmartListCreateDto(name = name, summary = summary, target = target, search = search),
        ).awaitBody()?.also { DetailIdRegistry.registerSmartList(it.id.hashCode(), it.id) }
    }

    /** 更新名称/简介/条件；search 为 null 时省略该键以保护服务端现有条件。 */
    suspend fun updateSmartList(
        dtoId: String,
        name: String,
        summary: String,
        search: JsonObject?,
    ): Boolean {
        val api = sessionManager.clients.value?.smartLists ?: return false
        return api.updateSmartList(
            id = dtoId,
            body = SmartListUpdateDto(name = name, summary = summary, search = search),
        ).awaitBody() != null
    }

    suspend fun deleteSmartList(dtoId: String): Boolean {
        val api = sessionManager.clients.value?.smartLists ?: return false
        return api.deleteSmartList(dtoId).awaitBody() != null
    }
}
