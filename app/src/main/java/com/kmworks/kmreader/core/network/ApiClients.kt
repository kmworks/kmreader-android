package com.kmworks.kmreader.core.network

import com.kmworks.kmreader.core.api.APIKeysApi
import com.kmworks.kmreader.core.api.AnnouncementsApi
import com.kmworks.kmreader.core.api.BooksApi
import com.kmworks.kmreader.core.api.CollectionsApi
import com.kmworks.kmreader.core.api.CurrentUserApi
import com.kmworks.kmreader.core.api.DuplicatePagesApi
import com.kmworks.kmreader.core.api.LibrariesApi
import com.kmworks.kmreader.core.api.ReadlistsApi
import com.kmworks.kmreader.core.api.ReferentialMetadataApi
import com.kmworks.kmreader.core.api.SeriesApi
import com.kmworks.kmreader.core.api.UserSessionApi
import com.kmworks.kmreader.core.data.MetadataApi
import com.kmworks.kmreader.core.data.SmartListsApi
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.server.ServerApi
import java.io.IOException
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
 * All Komga API clients built from an instance configuration.
 * Authentication: an apiKey goes through the `X-API-Key` header; otherwise every request carries Basic
 * Auth (Komga supports both; a session cookie is not required).
 */
class ApiClients(
    config: ServerConfig,
    timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    reachability: ServerReachability? = null,
) {

    init {
        // A rebuilt client means another instance (or another timeout): the old verdict is about a
        // different server, so reachability starts over instead of flashing the offline indicator.
        reachability?.reset()
    }

    /** Normalized base URL (used to compose direct URLs such as thumbnails). */
    val baseUrl: String = config.normalizedBaseUrl

    /** Identity of the instance this client was built for (namespaces the on-disk image caches). */
    val cacheNamespace: String = config.cacheNamespace

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder().apply {
                if (config.useApiKey) {
                    header("X-API-Key", config.apiKey)
                } else {
                    header("Authorization", Credentials.basic(config.username, config.password))
                }
            }.build()
            // Network-layer failure instrumentation: IO exceptions and non-2xx responses go to AppLog (evidence available on the logs page / in shares).
            // The same two outcomes feed ServerReachability: a response of any status means the server
            // answered (reachable), an IOException means nothing answered (a step towards offline).
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                // A cancelled call (an image whose row scrolled away, a superseded request) proves nothing
                // about the server: counting those let fast scrolling flip the offline indicator on its own.
                if (!chain.call().isCanceled()) {
                    AppLog.e("HTTP", "${request.method} ${request.url} network error", e)
                    reachability?.recordFailure()
                }
                throw e
            }
            if (!response.isSuccessful) {
                AppLog.w("HTTP", "HTTP ${response.code} ${request.method} ${request.url}")
            }
            reachability?.recordSuccess()
            response
        }
        .addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
        )
        .build()

    /** Derived client for the SSE long connection: read timeout zeroed, auth interceptor inherited. */
    private val sseHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Build an authenticated SSE EventSource (endpoint supplied by the caller). */
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

    val apiKeys: APIKeysApi by lazy { retrofit.create(APIKeysApi::class.java) }

    val duplicatePages: DuplicatePagesApi by lazy { retrofit.create(DuplicatePagesApi::class.java) }
    val collections: CollectionsApi by lazy { retrofit.create(CollectionsApi::class.java) }
    val readlists: ReadlistsApi by lazy { retrofit.create(ReadlistsApi::class.java) }
    val referential: ReferentialMetadataApi by lazy { retrofit.create(ReferentialMetadataApi::class.java) }
    val announcements: AnnouncementsApi by lazy { retrofit.create(AnnouncementsApi::class.java) }
    val userSession: UserSessionApi by lazy { retrofit.create(UserSessionApi::class.java) }

    /** kmrs smart-lists (handwritten interface, not covered by the openapi spec; 404 on stock servers). */
    val smartLists: SmartListsApi by lazy { retrofit.create(SmartListsApi::class.java) }

    /** server/info capability probing + logout (handwritten interface). */
    val serverApi: ServerApi by lazy { retrofit.create(ServerApi::class.java) }

    /** metadata PATCH that has to be able to send explicit JSON nulls (handwritten interface). */
    val metadata: MetadataApi by lazy { retrofit.create(MetadataApi::class.java) }

    companion object {
        /** Default network timeout (seconds), adjustable on the settings page (AppSettingsStore.networkTimeoutSeconds). */
        const val DEFAULT_TIMEOUT_SECONDS = 30
    }
}
