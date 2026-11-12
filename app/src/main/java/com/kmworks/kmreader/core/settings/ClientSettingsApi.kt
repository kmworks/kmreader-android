package com.kmworks.kmreader.core.settings

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

/** client-settings 单条值（对齐 iOS ClientSettingDto）。 */
@Serializable
data class ClientSettingDto(
    val value: String = "",
)

/** PATCH 写入体：{key: {"value": ...}}。 */
@Serializable
data class ClientSettingWriteDto(
    val value: String,
)

/**
 * Komga per-user client settings store（对齐 iOS ClientSettingsService，Komga 1.20+；
 * 键必须是小数点命名空间格式否则服务端拒绝）。
 */
interface ClientSettingsApi {

    @GET("api/v1/client-settings/user/list")
    fun getUserSettings(): Call<Map<String, ClientSettingDto>>

    /** 每个 key 独立 upsert，不覆盖其他设备写的键。 */
    @PATCH("api/v1/client-settings/user")
    fun saveUserSettings(@Body body: Map<String, ClientSettingWriteDto>): Call<ResponseBody>
}
