package com.kmworks.kmreader.core.settings

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH

/** A single client-settings value (mirrors iOS ClientSettingDto). */
@Serializable
data class ClientSettingDto(
    val value: String = "",
)

/** PATCH write body: {key: {"value": ...}}. */
@Serializable
data class ClientSettingWriteDto(
    val value: String,
)

/**
 * Komga per-user client settings store (mirrors iOS ClientSettingsService, Komga 1.20+;
 * keys must be in dotted-namespace format or the server rejects them).
 */
interface ClientSettingsApi {

    @GET("api/v1/client-settings/user/list")
    fun getUserSettings(): Call<Map<String, ClientSettingDto>>

    /** Each key upserts independently and never overwrites keys written by other devices. */
    @PATCH("api/v1/client-settings/user")
    fun saveUserSettings(@Body body: Map<String, ClientSettingWriteDto>): Call<ResponseBody>

    /** Removes keys (deleting a key that no longer exists is not an error). */
    @DELETE("api/v1/client-settings/user")
    fun deleteUserSettings(@Body keys: Set<String>): Call<ResponseBody>
}
