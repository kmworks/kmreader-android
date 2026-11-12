package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.BookPagesApi
import com.kmworks.kmreader.core.api.model.PageDto
import com.kmworks.kmreader.core.network.ApiClients
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * DIVINA 阅读器页面管线：页面列表 + 页面/缩略图直连 URL。
 *
 * ApiClients 未暴露 BookPagesApi（core/network 只读），这里用同一会话的
 * 认证 OkHttpClient 本地构建服务；URL 拼接规则与 ThumbnailUrls 一致。
 */
@Singleton
class ReaderRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private var cachedBaseUrl: String? = null
    private var cachedApi: BookPagesApi? = null

    private val clients: ApiClients? get() = sessionManager.clients.value

    /** Bound to the current session; rebuilt when the instance changes. */
    private fun api(): BookPagesApi? {
        val clients = clients ?: return null
        if (cachedBaseUrl != clients.baseUrl) {
            cachedApi = Retrofit.Builder()
                .baseUrl(clients.baseUrl + "/")
                .client(clients.okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(BookPagesApi::class.java)
            cachedBaseUrl = clients.baseUrl
        }
        return cachedApi
    }

    /** Resolve the Komga book id from the UI stable id; null for sample books. */
    fun bookDtoId(stableId: Int): String? = DetailIdRegistry.bookDtoId(stableId)

    /** Page list (number/fileName/mediaType/width/height); empty on error or no session. */
    suspend fun pages(dtoId: String): List<PageDto> =
        api()?.getBookPages(dtoId)?.awaitBody().orEmpty()

    /** Full page image URL (1-based page number). */
    fun pageUrl(dtoId: String, pageNumber: Int): String? =
        clients?.let { "${it.baseUrl}/api/v1/books/$dtoId/pages/$pageNumber" }

    /** Page thumbnail URL (300px, for the thumbnail strip). */
    fun pageThumbnailUrl(dtoId: String, pageNumber: Int): String? =
        clients?.let { "${it.baseUrl}/api/v1/books/$dtoId/pages/$pageNumber/thumbnail" }
}
