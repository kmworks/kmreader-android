package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.NotableEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** v2 paged string list wrapper (Komga v2 returns a Page object even when unpaged). */
@Serializable
data class ReferentialStringPageDto(
    val content: List<String> = emptyList(),
)

/** v2 paged int list wrapper（age-ratings）。 */
@Serializable
data class ReferentialIntPageDto(
    val content: List<Int> = emptyList(),
)

/** Author (mirrors iOS AuthorDTO). */
@Serializable
data class ReferentialAuthorDto(
    val name: String,
    val role: String? = null,
)

@Serializable
data class ReferentialAuthorPageDto(
    val content: List<ReferentialAuthorDto> = emptyList(),
)

/** Notable entity (mirrors iOS NotableEntity / the generated NotableEntityDto). */
@Serializable
data class ReferentialNotableEntityDto(
    val kind: String,
    val name: String,
)

/** v3 flat PageResponse (unpaged is also an object; only page/size/totalPages are normalized; mirrors kmrs PageResponse). */
@Serializable
data class ReferentialNotableEntityPageDto(
    val content: List<ReferentialNotableEntityDto> = emptyList(),
)

/**
 * Handwritten referential API (mirrors iOS ReferentialService: v2 first, deprecated v1 fallback;
 * the generated client has no fallback semantics, so ApiClients.referential is not used).
 */
interface ReferentialApi {

    @GET("api/v2/publishers")
    fun getPublishersV2(@Query("unpaged") unpaged: Boolean = true): Call<ReferentialStringPageDto>

    @GET("api/v1/publishers")
    fun getPublishersV1(): Call<List<String>>

    @GET("api/v2/genres")
    fun getGenresV2(
        @Query("unpaged") unpaged: Boolean = true,
        @Query("library_id") libraryIds: List<String>? = null,
        @Query("collection_id") collectionId: String? = null,
    ): Call<ReferentialStringPageDto>

    @GET("api/v1/genres")
    fun getGenresV1(): Call<List<String>>

    /** include=BOTH (series + books) or BOOK (book tags only). */
    @GET("api/v2/tags")
    fun getTagsV2(
        @Query("include") include: String,
        @Query("unpaged") unpaged: Boolean = true,
        @Query("library_id") libraryIds: List<String>? = null,
        @Query("collection_id") collectionId: String? = null,
        @Query("series_id") seriesId: String? = null,
        @Query("readlist_id") readListId: String? = null,
    ): Call<ReferentialStringPageDto>

    @GET("api/v1/tags")
    fun getTagsV1(): Call<List<String>>

    @GET("api/v1/tags/book")
    fun getBookTagsV1(): Call<List<String>>

    @GET("api/v2/languages")
    fun getLanguagesV2(
        @Query("unpaged") unpaged: Boolean = true,
        @Query("library_id") libraryIds: List<String>? = null,
        @Query("collection_id") collectionId: String? = null,
    ): Call<ReferentialStringPageDto>

    @GET("api/v1/languages")
    fun getLanguagesV1(): Call<List<String>>

    @GET("api/v2/age-ratings")
    fun getAgeRatingsV2(
        @Query("unpaged") unpaged: Boolean = true,
        @Query("library_id") libraryIds: List<String>? = null,
        @Query("collection_id") collectionId: String? = null,
    ): Call<ReferentialIntPageDto>

    @GET("api/v1/age-ratings")
    fun getAgeRatingsV1(): Call<List<String>>

    @GET("api/v2/series/release-years")
    fun getReleaseYearsV2(
        @Query("unpaged") unpaged: Boolean = true,
        @Query("library_id") libraryIds: List<String>? = null,
        @Query("collection_id") collectionId: String? = null,
    ): Call<ReferentialStringPageDto>

    @GET("api/v1/series/release-dates")
    fun getReleaseYearsV1(): Call<List<String>>

    @GET("api/v2/authors")
    fun getAuthorsV2(
        @Query("unpaged") unpaged: Boolean = true,
        @Query("series_id") seriesId: String? = null,
        @Query("library_id") libraryIds: List<String>? = null,
        @Query("collection_id") collectionId: String? = null,
        @Query("readlist_id") readListId: String? = null,
    ): Call<ReferentialAuthorPageDto>

    @GET("api/v1/authors")
    fun getAuthorsV1(): Call<List<ReferentialAuthorDto>>

    /** Notable entities (kmrs v3; no v1/v2 fallback — older servers lack the concept, 404 means empty). */
    @GET("api/v3/notable-entities")
    fun getNotableEntitiesV3(
        @Query("unpaged") unpaged: Boolean = true,
        @Query("library_id") libraryIds: List<String>? = null,
        @Query("collection_id") collectionId: String? = null,
        @Query("series_id") seriesId: String? = null,
        @Query("readlist_id") readListId: String? = null,
    ): Call<ReferentialNotableEntityPageDto>
}

/**
 * Repository of metadata option values (mirrors iOS ReferentialService). Every method returns an empty
 * list on failure and empty without a session. Scope parameters mirror the original
 * MetadataFilterItemSource: multi-value libraryIds for libraries, collectionId for collections, seriesId
 * for series, readListId for read lists (publisher is unrestricted, same as the original).
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

    suspend fun genres(
        libraryIds: List<String>? = null,
        collectionId: String? = null,
    ): List<String> = fetchStrings(
        { getGenresV2(libraryIds = libraryIds, collectionId = collectionId).execute() },
        { getGenresV1().execute() },
    )

    /** Series + book tags (v2 include=BOTH, falling back to v1 /tags). */
    suspend fun tags(
        libraryIds: List<String>? = null,
        collectionId: String? = null,
    ): List<String> = fetchStrings(
        {
            getTagsV2(
                include = "BOTH",
                libraryIds = libraryIds,
                collectionId = collectionId,
            ).execute()
        },
        { getTagsV1().execute() },
    )

    /** Book-only tags (v2 include=BOOK, falling back to v1 /tags/book). */
    suspend fun bookTags(
        seriesId: String? = null,
        readListId: String? = null,
        libraryIds: List<String>? = null,
    ): List<String> = fetchStrings(
        {
            getTagsV2(
                include = "BOOK",
                libraryIds = libraryIds,
                seriesId = seriesId,
                readListId = readListId,
            ).execute()
        },
        { getBookTagsV1().execute() },
    )

    suspend fun languages(
        libraryIds: List<String>? = null,
        collectionId: String? = null,
    ): List<String> = fetchStrings(
        { getLanguagesV2(libraryIds = libraryIds, collectionId = collectionId).execute() },
        { getLanguagesV1().execute() },
    )

    /** v1 maps an empty age rating to the literal "None", which must be filtered out (mirrors iOS). */
    suspend fun ageRatings(
        libraryIds: List<String>? = null,
        collectionId: String? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val api = api ?: return@withContext emptyList()
        try {
            val response = api.getAgeRatingsV2(libraryIds = libraryIds, collectionId = collectionId)
                .execute()
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

    suspend fun releaseYears(
        libraryIds: List<String>? = null,
        collectionId: String? = null,
    ): List<String> = fetchStrings(
        { getReleaseYearsV2(libraryIds = libraryIds, collectionId = collectionId).execute() },
        { getReleaseYearsV1().execute() },
    )

    /** Notable entities (mirrors iOS ReferentialService.getNotableEntities, v3 unpaged). */
    suspend fun notableEntities(
        libraryIds: List<String>? = null,
        collectionId: String? = null,
        seriesId: String? = null,
        readListId: String? = null,
    ): List<NotableEntity> = withContext(Dispatchers.IO) {
        val api = api ?: return@withContext emptyList()
        try {
            val response = api.getNotableEntitiesV3(
                libraryIds = libraryIds,
                collectionId = collectionId,
                seriesId = seriesId,
                readListId = readListId,
            ).execute()
            if (response.isSuccessful) {
                return@withContext response.body()?.content.orEmpty()
                    .map { NotableEntity(name = it.name, kind = it.kind.trim().lowercase()) }
            }
        } catch (_: Exception) {
        }
        emptyList()
    }

    /** Deduplicated author names (mirrors iOS getAuthorsNames). */
    suspend fun authors(
        seriesId: String? = null,
        libraryIds: List<String>? = null,
        collectionId: String? = null,
        readListId: String? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val api = api ?: return@withContext emptyList()
        try {
            val response = api.getAuthorsV2(
                seriesId = seriesId,
                libraryIds = libraryIds,
                collectionId = collectionId,
                readListId = readListId,
            ).execute()
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

    /** v2 first, falling back to v1 on failure (exception or non-2xx); empty on double failure. */
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
