package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.network.SessionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets plain composables reach the session (auth-aware ImageLoader). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImageLoaderEntryPoint {
    fun sessionManager(): SessionManager
}
