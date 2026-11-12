package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** v2 paged string list wrapper（Komga v2 即使 unpaged 也返回 Page 对象）。 */
@Serializable
data class ReferentialStringPageDto(
    val content: List<String> = emptyList(),
)

/** v2 paged int list wrapper（age-ratings）。 */
@Serializable
data class ReferentialIntPageDto(
    val content: List<Int> = emptyList(),
)

/** 作者（对齐 iOS AuthorDTO）。 */
@Serializable
data class ReferentialAuthorDto(
    val name: String,
    val role: String? = null,
)

@Serializable
data class ReferentialAuthorPageDto(
    val content: List<ReferentialAuthorDto> = emptyList(),
)

/**
 * Handwritten referential API（对齐 iOS ReferentialService：v2 优先、deprecated v1 回退；
 * 生成客户端无回退语义故不走 ApiClients.referential）。
 */
interface ReferentialApi {

    @GET("api/v2/publishers")
    fun getPublishersV2(@Query("unpaged") unpaged: Boolean = true): Call<ReferentialStringPageDto>

    @GET("api/v1/publishers")
    fun getPublishersV1(): Call<List<String>>

    @GET("api/v2/genres")
    fun getGenresV2(@Query("unpaged") unpaged: Boolean = true): Call<ReferentialStringPageDto>

    @GET("api/v1/genres")
    fun getGenresV1(): Call<List<String>>

    /** include=BOTH（系列+书）或 BOOK（仅书标签）。 */
    @GET("api/v2/tags")
    fun getTagsV2(
        @Query("include") include: String,
        @Query("unpaged") unpaged: Boolean = true,
    ): Call<ReferentialStringPageDto>

    @GET("api/v1/tags")
    fun getTagsV1(): Call<List<String>>

    @GET("api/v1/tags/book")
    fun getBookTagsV1(): Call<List<String>>

    @GET("api/v2/languages")
    fun getLanguagesV2(@Query("unpaged") unpaged: Boolean = true): Call<ReferentialStringPageDto>

    @GET("api/v1/languages")
    fun getLanguagesV1(): Call<List<String>>

    @GET("api/v2/age-ratings")
    fun getAgeRatingsV2(@Query("unpaged") unpaged: Boolean = true): Call<ReferentialIntPageDto>

    @GET("api/v1/age-ratings")
    fun getAgeRatingsV1(): Call<List<String>>

    @GET("api/v2/series/release-years")
    fun getReleaseYearsV2(@Query("unpaged") unpaged: Boolean = true): Call<ReferentialStringPageDto>

    @GET("api/v1/series/release-dates")
    fun getReleaseYearsV1(): Call<List<String>>

    @GET("api/v2/authors")
    fun getAuthorsV2(@Query("unpaged") unpaged: Boolean = true): Call<ReferentialAuthorPageDto>

    @GET("api/v1/authors")
    fun getAuthorsV1(): Call<List<ReferentialAuthorDto>>
}

/**
 * 元数据可选值仓库（对齐 iOS ReferentialService）。所有方法失败返回空列表；
 * 无会话返回空。library/collection 过滤参数后续按需再加。
 */
@Singleton
class ReferentialRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private val api: ReferentialApi?
        get() = sessionManager.clients.value
            ?.let { LocalApiFactory.create(it, ReferentialApi::class.java) }

    suspend fun publishers(): List<String> =
        fetchStrings({ getPublishersV2().execute() }, { getPublishersV1().execute() })

    suspend fun genres(): List<String> =
        fetchStrings({ getGenresV2().execute() }, { getGenresV1().execute() })

    /** 系列+书标签（v2 include=BOTH，回退 v1 /tags）。 */
    suspend fun tags(): List<String> =
        fetchStrings({ getTagsV2(include = "BOTH").execute() }, { getTagsV1().execute() })

    /** 仅书标签（v2 include=BOOK，回退 v1 /tags/book）。 */
    suspend fun bookTags(): List<String> =
        fetchStrings({ getTagsV2(include = "BOOK").execute() }, { getBookTagsV1().execute() })

    suspend fun languages(): List<String> =
        fetchStrings({ getLanguagesV2().execute() }, { getLanguagesV1().execute() })

    /** v1 把空年龄分级映射为字面量 "None"，需过滤（对齐 iOS）。 */
    suspend fun ageRatings(): List<String> = withContext(Dispatchers.IO) {
        val api = api ?: return@withContext emptyList()
        try {
            val response = api.getAgeRatingsV2().execute()
            if (response.isSuccessful) {
                return@withContext response.body()?.content.orEmpty().map { it.toString() }
            }
        } catch (_: Exception) {
        }
        try {
            val response = api.getAgeRatingsV1().execute()
            if (response.isSuccessful) {
                return@withContext response.body().orEmpty().filter { it != "None" }
            }
        } catch (_: Exception) {
        }
        emptyList()
    }

    suspend fun releaseYears(): List<String> =
        fetchStrings({ getReleaseYearsV2().execute() }, { getReleaseYearsV1().execute() })

    /** 作者名去重（对齐 iOS getAuthorsNames）。 */
    suspend fun authors(): List<String> = withContext(Dispatchers.IO) {
        val api = api ?: return@withContext emptyList()
        try {
            val response = api.getAuthorsV2().execute()
            if (response.isSuccessful) {
                return@withContext response.body()?.content.orEmpty().distinctNames()
            }
        } catch (_: Exception) {
        }
        try {
            val response = api.getAuthorsV1().execute()
            if (response.isSuccessful) {
                return@withContext response.body().orEmpty().distinctNames()
            }
        } catch (_: Exception) {
        }
        emptyList()
    }

    /** v2 优先，失败（异常或非 2xx）回退 v1；双失败返回空。 */
    private suspend fun fetchStrings(
        v2: ReferentialApi.() -> Response<ReferentialStringPageDto>,
        v1: ReferentialApi.() -> Response<List<String>>,
    ): List<String> = withContext(Dispatchers.IO) {
        val api = api ?: return@withContext emptyList()
        try {
            val response = api.v2()
            if (response.isSuccessful) return@withContext response.body()?.content.orEmpty()
        } catch (_: Exception) {
        }
        try {
            val response = api.v1()
            if (response.isSuccessful) return@withContext response.body().orEmpty()
        } catch (_: Exception) {
        }
        emptyList()
    }

    private fun List<ReferentialAuthorDto>.distinctNames(): List<String> =
        map { it.name }.distinct()
}
