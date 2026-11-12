package com.kmworks.kmreader.core.server

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * kmrs 私有能力键（对齐 iOS ServerFeature.swift；来自 /api/v1/server/info 的 features 数组，
 * 未知键忽略）。
 */
enum class ServerFeature(val raw: String) {
    ServerStats("serverStats"),
    ReadingStats("readingStats"),
    SmartLists("smartLists"),
    Sessions("sessions"),
    Komf("komf"),
    Trackers("trackers"),
    UpdateCheck("updateCheck");

    companion object {
        fun fromRaw(raw: String): ServerFeature? = entries.firstOrNull { it.raw == raw }
    }
}

/** server/info 响应（只取需要的字段；stock Komga 该端点 404）。 */
@Serializable
data class ServerInfoDto(
    val version: String = "",
    val features: List<String> = emptyList(),
)

/** 探测结果；无 profile 表示尚未探测（按 stock Komga 处理）。 */
data class ServerProfile(
    val isKmrs: Boolean,
    val features: Set<ServerFeature>,
    val version: String?,
)

/** server/info + logout 手写接口（openapi spec 未覆盖 server/info）。 */
interface ServerApi {

    @GET("api/v1/server/info")
    fun getServerInfo(): Call<ServerInfoDto>

    @POST("api/logout")
    fun logout(): Call<ResponseBody>
}
