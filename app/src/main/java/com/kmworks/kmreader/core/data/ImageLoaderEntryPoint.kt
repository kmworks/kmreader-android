package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets plain composables reach singletons (auth-aware image caches, persisted settings). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImageLoaderEntryPoint {
    fun sessionManager(): SessionManager
    fun imageCacheStore(): ImageCacheStore
    fun appSettingsStore(): AppSettingsStore
}
