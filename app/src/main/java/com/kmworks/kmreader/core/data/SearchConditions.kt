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

/** POST list 请求体：{"condition":...,"fullTextSearch":...}（两者都可省）。 */
internal fun searchBody(condition: JsonObject? = null, fullTextSearch: String? = null): JsonObject =
    buildJsonObject {
        condition?.let { put("condition", it) }
        fullTextSearch?.let { put("fullTextSearch", it) }
    }
