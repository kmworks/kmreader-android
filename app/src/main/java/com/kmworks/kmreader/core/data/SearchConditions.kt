package com.kmworks.kmreader.core.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * POST books/list、series/list 端点的查询体手写构造：生成的条件 DSL
 * （AnyOfBookAllOfAnyOf 等）全字段必填不可直构，这里按 Komga 实际接受的
 * JSON 形状构造（与 iOS SmartListFilterMapper 同一文档结构）。
 */

/** 单条件叶子：{"<key>":{"operator":op,"value":value}}。 */
internal fun leafCondition(key: String, operator: String, value: String): JsonObject =
    buildJsonObject {
        putJsonObject(key) {
            put("operator", operator)
            put("value", value)
        }
    }

/** 组合条件：{"allOf":[...]}。 */
internal fun allOfCondition(conditions: List<JsonObject>): JsonObject =
    buildJsonObject {
        putJsonArray("allOf") { conditions.forEach { add(it) } }
    }

/** 组合条件：{"anyOf":[...]}。 */
internal fun anyOfCondition(conditions: List<JsonObject>): JsonObject =
    buildJsonObject {
        putJsonArray("anyOf") { conditions.forEach { add(it) } }
    }

/** 对象值叶子：{"<key>":{"operator":op,"value":{...}}}（作者条件等）。 */
internal fun objectLeafCondition(key: String, operator: String, value: JsonObject): JsonObject =
    buildJsonObject {
        putJsonObject(key) {
            put("operator", operator)
            put("value", value)
        }
    }

/** 无值叶子：{"<key>":{"operator":op}}（isTrue 等无 value 的操作符）。 */
internal fun flagLeafCondition(key: String, operator: String): JsonObject =
    buildJsonObject {
        putJsonObject(key) {
            put("operator", operator)
        }
    }

/** 日期叶子：{"<key>":{"operator":op,"dateTime":iso8601}}（对齐 iOS releaseDate/readDate 分支）。 */
internal fun dateLeafCondition(key: String, operator: String, isoDateTime: String): JsonObject =
    buildJsonObject {
        putJsonObject(key) {
            put("operator", operator)
            put("dateTime", isoDateTime)
        }
    }

/** 作者条件：{"author":{"is":{"name":name}}}（对齐 iOS BookSearch.buildCondition）。 */
internal fun authorCondition(name: String): JsonObject =
    objectLeafCondition(
        "author",
        "is",
        buildJsonObject { put("name", name) },
    )

/** 库条件：单库为 libraryId is 叶子，多库为 anyOf（对齐 iOS libraryIds 分支）。 */
internal fun libraryCondition(libraryIds: List<String>): JsonObject {
    val leaves = libraryIds.map { leafCondition("libraryId", "is", it) }
    return if (leaves.size == 1) leaves.single() else anyOfCondition(leaves)
}

/** 合并可选条件：多个→allOf，单个→原样，空→null。 */
internal fun combinedCondition(conditions: List<JsonObject>): JsonObject? = when (conditions.size) {
    0 -> null
    1 -> conditions.single()
    else -> allOfCondition(conditions)
}

/** POST list 请求体：{"condition":...,"fullTextSearch":...}（两者都可省）。 */
internal fun searchBody(condition: JsonObject? = null, fullTextSearch: String? = null): JsonObject =
    buildJsonObject {
        condition?.let { put("condition", it) }
        fullTextSearch?.let { put("fullTextSearch", it) }
    }
