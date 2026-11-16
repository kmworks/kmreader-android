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

/** kmrs 设置响应（GET /api/v1/settings；stock Komga 该端点 404，字段均可缺省）。 */
@Serializable
data class ServerSettingsDto(
    val skipReadOnNext: Boolean? = null,
    val recentReadingDays: Int? = null,
)

/** 探测结果；无 profile 表示尚未探测（按 stock Komga 处理）。 */
data class ServerProfile(
    val isKmrs: Boolean,
    val features: Set<ServerFeature>,
    val version: String?,
    /** kmrs「下一本跳过已读」设置（对齐 iOS ServerProfile.skipReadOnNext）。 */
    val skipReadOnNext: Boolean = false,
    /** kmrs 最近阅读窗口（天），0 = 关闭（对齐 iOS ServerProfile.recentReadingDays）。 */
    val recentReadingDays: Int = 0,
) {
    /** Keep Reading / On Deck 保留的最早阅读时间；窗口关闭时为 null（对齐 iOS recentReadingCutoff）。 */
    fun recentReadingCutoff(now: java.time.Instant = java.time.Instant.now()): java.time.Instant? {
        if (recentReadingDays <= 0) return null
        return now.minus(java.time.Duration.ofDays(recentReadingDays.toLong()))
    }
}

/** server/info + settings + logout 手写接口（openapi spec 未覆盖）。 */
interface ServerApi {

    @GET("api/v1/server/info")
    fun getServerInfo(): Call<ServerInfoDto>

    @GET("api/v1/settings")
    fun getServerSettings(): Call<ServerSettingsDto>

    @POST("api/logout")
    fun logout(): Call<ResponseBody>
}
