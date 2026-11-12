package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.network.ApiClients
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

/**
 * 在 ApiClients（core/network 只读）之外构建生成/手写 API 服务的共享工厂。
 * 复用同一会话的认证 OkHttpClient 与 baseUrl，按 baseUrl 失效重建。
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
