package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.network.ApiClients
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

/**
 * Shared factory for generated/handwritten API services outside ApiClients (core/network is read-only).
 * Reuses the same session's authenticated OkHttpClient and baseUrl; rebuilt when baseUrl becomes invalid.
 */
internal object LocalApiFactory {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val lock = Any()
    private var baseUrl: String? = null
    private val services = mutableMapOf<Class<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> create(clients: ApiClients, service: Class<T>): T = synchronized(lock) {
        if (baseUrl != clients.baseUrl) {
            services.clear()
            baseUrl = clients.baseUrl
        }
        services.getOrPut(service) {
            Retrofit.Builder()
                .baseUrl(clients.baseUrl + "/")
                .client(clients.okHttpClient)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(service)
        } as T
    }
}
