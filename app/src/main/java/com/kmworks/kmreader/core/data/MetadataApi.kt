package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.PATCH
import retrofit2.http.Path

/**
 * Handwritten PATCH for the series metadata endpoint, whose body sometimes has to carry an explicit
 * JSON null.
 *
 * Komga documents the update DTO as "Set a field to null to unset the metadata. You can omit fields
 * you don't want to update" — so null and absent mean different things. The generated DTO gives
 * every optional field a `= null` default, and the kotlinx serializer skips a property equal to its
 * default: with the typed body the two cases collapse into "absent", and a cleared age rating or book
 * count can never be unset. iOS builds the body as a dictionary and writes NSNull for those fields;
 * this interface takes the already-built JSON body for the same reason.
 */
interface MetadataApi {

    @PATCH("api/v1/series/{id}/metadata")
    fun updateSeriesMetadata(@Path("id") id: String, @Body body: JsonObject): Call<Unit>
}

/** Default-omitting encoder: a null field means "leave alone" and stays out of the body. */
private val patchJson = Json {
    encodeDefaults = false
    explicitNulls = true
}

/**
 * Wire body of a series metadata update. [cleared] names the fields the edit emptied: they are
 * written as explicit nulls (Komga unsets them) instead of being dropped from the request.
 */
internal fun SeriesMetadataUpdateDto.toPatchJson(cleared: Set<String> = emptySet()): JsonObject {
    val body = patchJson.encodeToJsonElement(SeriesMetadataUpdateDto.serializer(), this).jsonObject
    if (cleared.isEmpty()) return body
    return JsonObject(body + cleared.associateWith { JsonNull })
}
