package com.kmworks.kmreader.core.network

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.kmworks.kmreader.core.settings.AppSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import okio.Path.Companion.toOkioPath
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideServerConfigStore(@ApplicationContext context: Context): ServerConfigStore =
        ServerConfigStore(context)

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Current instance session: ApiClients and the authenticated ImageLoader are rebuilt when the configuration changes. */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext context: Context,
    configStore: ServerConfigStore,
    appSettings: AppSettingsStore,
    scope: CoroutineScope,
) {

    /** Clients are rebuilt in step when the timeout setting (AppSettingsStore.networkTimeoutSeconds) changes. */
    val clients: StateFlow<ApiClients?> = combine(
        configStore.config,
        appSettings.networkTimeoutSeconds,
    ) { config, timeoutSeconds ->
        config?.takeIf { it.isConfigured }?.let { ApiClients(it, timeoutSeconds) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Coil ImageLoader sharing the current instance's auth configuration (covers/thumbnails need the auth header); the disk cache follows the settings cap. */
    val imageLoader: StateFlow<ImageLoader?> = combine(
        clients,
        appSettings.imageCacheLimitMb,
    ) { clients, limitMb ->
        clients?.let {
            ImageLoader.Builder(context)
                .diskCache(
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                        .maxSizeBytes(limitMb * 1024L * 1024L)
                        .build(),
                )
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = it.okHttpClient))
                }
                .build()
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Dedicated loader for third-party covers (komf identify results, tracker remote covers): carries no
     * Komga credentials.
     * Some CDNs block okhttp's default UA (`okhttp/x.y.z`), so a regular UA is set explicitly here.
     */
    val externalImageLoader: ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = okhttp3.OkHttpClient.Builder()
                            .addInterceptor { chain ->
                                chain.proceed(
                                    chain.request().newBuilder()
                                        .header("User-Agent", "KMReader/1.0 (Android)")
                                        .build(),
                                )
                            }
                            .build(),
                    ),
                )
            }
            .build()
}
