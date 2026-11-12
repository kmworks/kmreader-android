package com.kmworks.kmreader.core.network

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
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
    scope: CoroutineScope,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val clients: StateFlow<ApiClients?> = configStore.config
        .mapLatest { config -> config?.takeIf { it.isConfigured }?.let(::ApiClients) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 与当前实例同认证配置的 Coil ImageLoader（封面/缩略图需要认证头）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val imageLoader: StateFlow<ImageLoader?> = clients
        .mapLatest { clients ->
            clients?.let {
                ImageLoader.Builder(context)
                    .components {
                        add(OkHttpNetworkFetcherFactory(callFactory = it.okHttpClient))
                    }
                    .build()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)
}
