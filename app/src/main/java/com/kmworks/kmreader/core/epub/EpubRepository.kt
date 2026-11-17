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

/** WebPub manifest link (Komga TOC container entries may omit href, hence nullable). */
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

/** Readium locator locations (progression = ratio within the chapter, totalProgression = ratio of the whole book). */
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

/** GET positions response. */
@Serializable
data class EpubPositionsDto(
    val positions: List<EpubLocatorDto> = emptyList(),
    val total: Int = 0,
)

/** Device section of the progression request body. */
@Serializable
data class EpubDeviceDto(
    val id: String = "",
    val name: String = "",
)

/** Readium progression (PUT progression request body / GET response). */
@Serializable
data class EpubProgressionDto(
    val modified: String = "",
    val device: EpubDeviceDto = EpubDeviceDto(),
    val locator: EpubLocatorDto = EpubLocatorDto(),
)

/** Resource stream result (mime from the server Content-Type, encoding only for text types). */
class EpubResource(
    val data: ByteArray,
    val mimeType: String,
    val encoding: String?,
)

/** WebPub manifest / positions / progression endpoints (mirrors the original BookService). */
interface EpubApi {

    @Headers("Accept: application/webpub+json")
    @GET("api/v1/books/{bookId}/manifest")
    fun manifest(@Path("bookId") bookId: String): Call<EpubManifestDto>

    @Headers("Accept: application/vnd.readium.progression+json")
    @GET("api/v1/books/{bookId}/progression")
    fun progression(@Path("bookId") bookId: String): Call<ResponseBody>

    @GET("api/v1/books/{bookId}/positions")
    fun positions(@Path("bookId") bookId: String): Call<EpubPositionsDto>

    // The original updateWebPubProgression uses PUT (not POST), body=R2Progression
    @Headers("Accept: application/vnd.readium.progression+json")
    @PUT("api/v1/books/{bookId}/progression")
    fun updateProgression(
        @Path("bookId") bookId: String,
        @Body progression: EpubProgressionDto,
    ): Call<ResponseBody>
}

/**
 * EPUB WebPub data channel (mirrors BookService WebPub endpoints + EpubResourceSchemeHandler resource streams).
 * Resource URL template: {baseUrl}/api/v1/books/{bookId}/resource?{href} (href joined verbatim; Komga parses the whole string).
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

    /** WebPub manifest; null without a session or on failure. */
    suspend fun manifest(bookDtoId: String): EpubManifestDto? =
        fetchOrNull { api?.manifest(bookDtoId) }

    /** Positions array (chapter weights / seek mapping); null on failure. */
    suspend fun positions(bookDtoId: String): EpubPositionsDto? =
        fetchOrNull { api?.positions(bookDtoId) }

    /** Server-saved progress; 404 / empty body / empty locator count as missing → null (mirrors the original decodeRemoteWebPubProgression). */
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

    /** Throttled read-progress reporting (PUT progression, body mirrors the original R2Progression). */
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

    /**
     * Synchronously forward a resource request (called directly on the WebViewClient.shouldInterceptRequest
     * thread, reusing the authenticated client).
     * Returns null on failure / no session.
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

/** Chapter entry (navigable items flattened from readingOrder). */
data class EpubChapter(
    val index: Int,
    val href: String,
    val title: String,
)
