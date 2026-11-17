package com.kmworks.kmreader.core.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Handwritten construction of query bodies for the POST books/list and series/list endpoints: the generated
 * condition DSL (AnyOfBookAllOfAnyOf etc.) has all-required fields and cannot be built directly, so the JSON
 * shape Komga actually accepts is built here (same document structure as iOS SmartListFilterMapper).
 */

/** Single-condition leaf: {"<key>":{"operator":op,"value":value}}. */
internal fun leafCondition(key: String, operator: String, value: String): JsonObject =
    buildJsonObject {
        putJsonObject(key) {
            put("operator", operator)
            put("value", value)
        }
    }

/** Combined condition: {"allOf":[...]}. */
internal fun allOfCondition(conditions: List<JsonObject>): JsonObject =
    buildJsonObject {
        putJsonArray("allOf") { conditions.forEach { add(it) } }
    }

/** Combined condition: {"anyOf":[...]}. */
internal fun anyOfCondition(conditions: List<JsonObject>): JsonObject =
    buildJsonObject {
        putJsonArray("anyOf") { conditions.forEach { add(it) } }
    }

/** Object-value leaf: {"<key>":{"operator":op,"value":{...}}} (author condition etc.). */
internal fun objectLeafCondition(key: String, operator: String, value: JsonObject): JsonObject =
    buildJsonObject {
        putJsonObject(key) {
            put("operator", operator)
            put("value", value)
        }
    }

/** Valueless leaf: {"<key>":{"operator":op}} (operators without a value, e.g. isTrue). */
internal fun flagLeafCondition(key: String, operator: String): JsonObject =
    buildJsonObject {
        putJsonObject(key) {
            put("operator", operator)
        }
    }

/** Date leaf: {"<key>":{"operator":op,"dateTime":iso8601}} (mirrors the iOS releaseDate/readDate branches). */
internal fun dateLeafCondition(key: String, operator: String, isoDateTime: String): JsonObject =
    buildJsonObject {
        putJsonObject(key) {
            put("operator", operator)
            put("dateTime", isoDateTime)
        }
    }

/** Author condition: {"author":{"is":{"name":name}}} (mirrors iOS BookSearch.buildCondition). */
internal fun authorCondition(name: String): JsonObject =
    objectLeafCondition(
        "author",
        "is",
        buildJsonObject { put("name", name) },
    )

/** Notable entity condition: {"notableEntity":{"operator":"is","value":{"kind":k,"name":n}}}
 * (mirrors the notableEntity leaf of iOS BookSearch/SeriesSearch). */
internal fun notableEntityCondition(kind: String, name: String): JsonObject =
    objectLeafCondition(
        "notableEntity",
        "is",
        buildJsonObject {
            put("kind", kind)
            put("name", name)
        },
    )

/** Library condition: a single library is a libraryId is leaf, several libraries become anyOf (mirrors the iOS libraryIds branch). */
internal fun libraryCondition(libraryIds: List<String>): JsonObject {
    val leaves = libraryIds.map { leafCondition("libraryId", "is", it) }
    return if (leaves.size == 1) leaves.single() else anyOfCondition(leaves)
}

/** Merge optional conditions: several → allOf, one → as-is, empty → null. */
internal fun combinedCondition(conditions: List<JsonObject>): JsonObject? = when (conditions.size) {
    0 -> null
    1 -> conditions.single()
    else -> allOfCondition(conditions)
}

/** POST list request body: {"condition":...,"fullTextSearch":...} (both optional). */
internal fun searchBody(condition: JsonObject? = null, fullTextSearch: String? = null): JsonObject =
    buildJsonObject {
        condition?.let { put("condition", it) }
        fullTextSearch?.let { put("fullTextSearch", it) }
    }
