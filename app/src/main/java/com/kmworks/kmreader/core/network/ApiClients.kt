package com.kmworks.kmreader.core.network

import com.kmworks.kmreader.core.api.AnnouncementsApi
import com.kmworks.kmreader.core.api.BooksApi
import com.kmworks.kmreader.core.api.CollectionsApi
import com.kmworks.kmreader.core.api.CurrentUserApi
import com.kmworks.kmreader.core.api.LibrariesApi
import com.kmworks.kmreader.core.api.ReadlistsApi
import com.kmworks.kmreader.core.api.ReferentialMetadataApi
import com.kmworks.kmreader.core.api.SeriesApi
import com.kmworks.kmreader.core.api.UserSessionApi
import com.kmworks.kmreader.core.data.SmartListsApi
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

/**
 * 由实例配置构建的全部 Komga API 客户端。
 * 认证：apiKey 走 `X-API-Key` 头；否则每次请求带 Basic Auth（Komga 同时支持，
 * 会话 cookie 非必需）。
 */
class ApiClients(config: ServerConfig) {

    /** 规范化 base URL（缩略图等直连地址拼接用）。 */
    val baseUrl: String = config.normalizedBaseUrl

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder().apply {
                if (config.useApiKey) {
                    header("X-API-Key", config.apiKey)
                } else {
                    header("Authorization", Credentials.basic(config.username, config.password))
                }
            }.build()
            chain.proceed(request)
        }
        .addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
        )
        .build()

    /** SSE 长连用派生客户端：读超时置 0，认证拦截器继承。 */
    private val sseHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** 建立带认证的 SSE EventSource（端点由调用方给定）。 */
    fun newEventSource(request: Request, listener: EventSourceListener): EventSource =
        EventSources.createFactory(sseHttpClient).newEventSource(request, listener)

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(config.normalizedBaseUrl + "/")
        .client(okHttpClient)
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val currentUser: CurrentUserApi by lazy { retrofit.create(CurrentUserApi::class.java) }
    val libraries: LibrariesApi by lazy { retrofit.create(LibrariesApi::class.java) }
    val series: SeriesApi by lazy { retrofit.create(SeriesApi::class.java) }
    val books: BooksApi by lazy { retrofit.create(BooksApi::class.java) }
    val collections: CollectionsApi by lazy { retrofit.create(CollectionsApi::class.java) }
    val readlists: ReadlistsApi by lazy { retrofit.create(ReadlistsApi::class.java) }
    val referential: ReferentialMetadataApi by lazy { retrofit.create(ReferentialMetadataApi::class.java) }
    val announcements: AnnouncementsApi by lazy { retrofit.create(AnnouncementsApi::class.java) }
    val userSession: UserSessionApi by lazy { retrofit.create(UserSessionApi::class.java) }

    /** kmrs smart-lists（手写接口，openapi spec 未覆盖；stock 服务器 404）。 */
    val smartLists: SmartListsApi by lazy { retrofit.create(SmartListsApi::class.java) }
}
