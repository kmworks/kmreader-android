package com.kmworks.kmreader.core.epub

import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PUT
import retrofit2.http.Path

/** WebPub manifest link（Komga 目录容器项可省略 href，故可空）。 */
@Serializable
data class EpubLinkDto(
    val href: String? = null,
    val title: String? = null,
    val type: String? = null,
    val children: List<EpubLinkDto>? = null,
)

/** WebPub manifest（Accept: application/webpub+json）。 */
@Serializable
data class EpubManifestDto(
    val readingOrder: List<EpubLinkDto> = emptyList(),
    val resources: List<EpubLinkDto> = emptyList(),
    val toc: List<EpubLinkDto> = emptyList(),
)

/** Readium locator locations（progression=章节内比例，totalProgression=全书比例）。 */
@Serializable
data class EpubLocatorLocationsDto(
    val progression: Float? = null,
    val position: Int? = null,
    val totalProgression: Float? = null,
)

/** Readium locator。 */
@Serializable
data class EpubLocatorDto(
    val href: String = "",
    val type: String = "text/html",
    val title: String? = null,
    val locations: EpubLocatorLocationsDto? = null,
)

/** GET positions 响应。 */
@Serializable
data class EpubPositionsDto(
    val positions: List<EpubLocatorDto> = emptyList(),
    val total: Int = 0,
)

/** progression 请求体的 device 段。 */
@Serializable
data class EpubDeviceDto(
    val id: String = "",
    val name: String = "",
)

/** Readium progression（PUT progression 请求体 / GET 响应）。 */
@Serializable
data class EpubProgressionDto(
    val modified: String = "",
    val device: EpubDeviceDto = EpubDeviceDto(),
    val locator: EpubLocatorDto = EpubLocatorDto(),
)

/** 资源流结果（mime 来自服务端 Content-Type，encoding 仅文本类型）。 */
class EpubResource(
    val data: ByteArray,
    val mimeType: String,
    val encoding: String?,
)

/** WebPub manifest / positions / progression 端点（对齐原作 BookService）。 */
interface EpubApi {

    @Headers("Accept: application/webpub+json")
    @GET("api/v1/books/{bookId}/manifest")
    fun manifest(@Path("bookId") bookId: String): Call<EpubManifestDto>

    @Headers("Accept: application/vnd.readium.progression+json")
    @GET("api/v1/books/{bookId}/progression")
    fun progression(@Path("bookId") bookId: String): Call<ResponseBody>

    @GET("api/v1/books/{bookId}/positions")
    fun positions(@Path("bookId") bookId: String): Call<EpubPositionsDto>

    // 原作 updateWebPubProgression 用 PUT（非 POST），body=R2Progression
    @Headers("Accept: application/vnd.readium.progression+json")
    @PUT("api/v1/books/{bookId}/progression")
    fun updateProgression(
        @Path("bookId") bookId: String,
        @Body progression: EpubProgressionDto,
    ): Call<ResponseBody>
}

/**
 * EPUB WebPub 数据通道（mirrors BookService WebPub endpoints + EpubResourceSchemeHandler 资源流）。
 * 资源 URL 模板：{baseUrl}/api/v1/books/{bookId}/resource?{href}（href 原样拼接，Komga 整串解析）。
 */
@Singleton
class EpubRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val api: EpubApi?
        get() = sessionManager.clients.value
            ?.let { LocalApiFactory.create(it, EpubApi::class.java) }

    val baseUrl: String?
        get() = sessionManager.clients.value?.baseUrl

    /** WebPub manifest；无会话或失败返回 null。 */
    suspend fun manifest(bookDtoId: String): EpubManifestDto? =
        fetchOrNull { api?.manifest(bookDtoId) }

    /** 位置数组（章节权重/seek 映射用）；失败返回 null。 */
    suspend fun positions(bookDtoId: String): EpubPositionsDto? =
        fetchOrNull { api?.positions(bookDtoId) }

    /** 服务端保存的进度；404/空 body/空 locator 视为缺失返回 null（对齐原作 decodeRemoteWebPubProgression）。 */
    suspend fun savedProgression(bookDtoId: String): EpubProgressionDto? = withContext(Dispatchers.IO) {
        runCatching {
            val response = api?.progression(bookDtoId)?.execute() ?: return@runCatching null
            if (!response.isSuccessful) return@runCatching null
            val body = response.body()?.string().orEmpty()
            if (body.isBlank()) return@runCatching null
            val progression = json.decodeFromString<EpubProgressionDto>(body)
            if (progression.locator.href.isBlank() && progression.locator.locations == null) {
                null
            } else {
                progression
            }
        }.getOrNull()
    }

    /** 节流上报阅读进度（PUT progression，body 对齐原作 R2Progression）。 */
    suspend fun reportProgression(
        bookDtoId: String,
        href: String,
        chapterFraction: Float,
        totalProgression: Float,
        deviceId: String,
        deviceName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val progression = EpubProgressionDto(
                modified = java.time.Instant.now().toString(),
                device = EpubDeviceDto(id = deviceId, name = deviceName),
                locator = EpubLocatorDto(
                    href = href,
                    type = "text/html",
                    locations = EpubLocatorLocationsDto(
                        progression = chapterFraction,
                        totalProgression = totalProgression,
                    ),
                ),
            )
            api?.updateProgression(bookDtoId, progression)?.execute()?.isSuccessful == true
        }.getOrDefault(false)
    }

    /** 资源完整 URL（href 原样拼进 query，对齐 Komga web reader）。 */
    fun resourceUrl(bookDtoId: String, href: String): String? =
        baseUrl?.let { "$it/api/v1/books/$bookDtoId/resource?$href" }

    /**
     * 同步转发资源请求（WebViewClient.shouldInterceptRequest 线程直接调用，复用认证 client）。
     * 返回 null 表示失败/无会话。
     */
    fun fetchResourceBlocking(url: String): EpubResource? {
        val client = sessionManager.clients.value?.okHttpClient ?: return null
        return runCatching {
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body
                val contentType = body.contentType()
                val mime = contentType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
                val encoding = contentType?.charset()?.name()
                    ?: if (isTextMime(mime)) "utf-8" else null
                EpubResource(body.bytes(), mime, encoding)
            }
        }.getOrNull()
    }

    private fun isTextMime(mime: String): Boolean =
        mime.startsWith("text/") || mime == "application/xhtml+xml" ||
            mime == "application/xml" || mime == "application/javascript" ||
            mime == "image/svg+xml"

    private suspend fun <T> fetchOrNull(block: () -> Call<T>?): T? = withContext(Dispatchers.IO) {
        runCatching {
            val response = block()?.execute() ?: return@runCatching null
            if (response.isSuccessful) response.body() else null
        }.getOrNull()
    }
}

/** 章节项（readingOrder 展平后的可导航条目）。 */
data class EpubChapter(
    val index: Int,
    val href: String,
    val title: String,
)
