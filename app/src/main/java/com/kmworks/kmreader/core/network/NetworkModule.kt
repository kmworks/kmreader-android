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

/** 当前实例会话：配置变化时重建 ApiClients 与带认证的 ImageLoader。 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext context: Context,
    configStore: ServerConfigStore,
    appSettings: AppSettingsStore,
    scope: CoroutineScope,
) {

    /** 超时设置（AppSettingsStore.networkTimeoutSeconds）变化时同步重建客户端。 */
    val clients: StateFlow<ApiClients?> = combine(
        configStore.config,
        appSettings.networkTimeoutSeconds,
    ) { config, timeoutSeconds ->
        config?.takeIf { it.isConfigured }?.let { ApiClients(it, timeoutSeconds) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** 与当前实例同认证配置的 Coil ImageLoader（封面/缩略图需要认证头），磁盘缓存随设置上限调整。 */
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
}
