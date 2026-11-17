package com.kmworks.kmreader.core.server

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * kmrs private capability keys (mirrors iOS ServerFeature.swift; from the features array of
 * /api/v1/server/info, unknown keys ignored).
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

/** server/info response (only the needed fields; the endpoint 404s on stock Komga). */
@Serializable
data class ServerInfoDto(
    val version: String = "",
    val features: List<String> = emptyList(),
)

/** kmrs settings response (GET /api/v1/settings; the endpoint 404s on stock Komga, all fields optional). */
@Serializable
data class ServerSettingsDto(
    val skipReadOnNext: Boolean? = null,
    val recentReadingDays: Int? = null,
)

/** Probe result; no profile means not yet probed (treated as stock Komga). */
data class ServerProfile(
    val isKmrs: Boolean,
    val features: Set<ServerFeature>,
    val version: String?,
    /** kmrs "skip read books on next" setting (mirrors iOS ServerProfile.skipReadOnNext). */
    val skipReadOnNext: Boolean = false,
    /** kmrs recent-reading window (days); 0 = off (mirrors iOS ServerProfile.recentReadingDays). */
    val recentReadingDays: Int = 0,
) {
    /** Earliest read time kept by Keep Reading / On Deck; null when the window is off (mirrors iOS recentReadingCutoff). */
    fun recentReadingCutoff(now: java.time.Instant = java.time.Instant.now()): java.time.Instant? {
        if (recentReadingDays <= 0) return null
        return now.minus(java.time.Duration.ofDays(recentReadingDays.toLong()))
    }
}

/** Handwritten server/info + settings + logout interface (not covered by the openapi spec). */
interface ServerApi {

    @GET("api/v1/server/info")
    fun getServerInfo(): Call<ServerInfoDto>

    @GET("api/v1/settings")
    fun getServerSettings(): Call<ServerSettingsDto>

    @POST("api/logout")
    fun logout(): Call<ResponseBody>
}
