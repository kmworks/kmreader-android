package com.kmworks.kmreader.ui.lists

import com.kmworks.kmreader.data.SmartListTarget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Editor rules -> server search document (mirrors the JSON key structure of the original
 * SmartListFilterMapper.buildCondition).
 * Document shape: {"condition": <node>}; a node is either a bare leaf
 * {"<key>": {"operator": op, "value": v}} or a group {"allOf": [...]} / {"anyOf": [...]};
 * with no rules the condition key is omitted (empty document = matches everything).
 * Note: "Pages" has no counterpart key in the original condition DSL, so the editor does
 * not offer that field (zero API difference).
 */
fun buildSmartListSearchDocument(
    matchAll: Boolean,
    rules: List<SmartListRule>,
): JsonObject {
    val nodes = rules.mapNotNull { it.toConditionNode() }
    return buildJsonObject {
        when {
            nodes.isEmpty() -> Unit
            nodes.size == 1 -> put("condition", nodes.first())
            else -> put("condition", buildJsonObject {
                putJsonArray(if (matchAll) "allOf" else "anyOf") { nodes.forEach { add(it) } }
            })
        }
    }
}

/** Target type -> server target value. */
fun SmartListTarget.toDtoTarget(): String = when (this) {
    SmartListTarget.Book -> "BOOK"
    SmartListTarget.Series -> "SERIES"
}

private fun SmartListRule.toConditionNode(): JsonObject? = when (field) {
    "readStatus" -> {
        val status = when (value) {
            "read" -> "READ"
            "inProgress" -> "IN_PROGRESS"
            else -> "UNREAD"
        }
        leaf("readStatus", isOperator(), status)
    }
    "tag" -> leaf("tag", isOperator(), value)
    "author" -> buildJsonObject {
        putJsonObject("author") {
            put("operator", isOperator())
            putJsonObject("value") { put("name", value) }
        }
    }
    "releaseYear" -> yearConditionNode()
    else -> null
}

/** eq/contains/gt/lt degrade to "is" on non-date fields; neq -> "isNot" (the original only emits is/isNot). */
private fun SmartListRule.isOperator(): String = if (operator == "neq") "isNot" else "is"

/** Year encoding mirrors the original: eq = the whole year Y as an interval pair,
 *  gt = after the end of Y, lt = before New Year's Day of Y+1; anything else is treated as eq. */
private fun SmartListRule.yearConditionNode(): JsonObject? {
    val year = value.trim().toIntOrNull() ?: return null
    return when (operator) {
        "gt" -> dateLeaf("after", "${year}-12-31T12:00:00Z")
        "lt" -> dateLeaf("before", "${year + 1}-01-01T12:00:00Z")
        else -> buildJsonObject {
            putJsonArray("allOf") {
                add(dateLeaf("after", "${year - 1}-12-31T12:00:00Z"))
                add(dateLeaf("before", "${year + 1}-01-01T12:00:00Z"))
            }
        }
    }
}

private fun dateLeaf(operator: String, dateTime: String): JsonObject = buildJsonObject {
    putJsonObject("releaseDate") {
        put("operator", operator)
        put("dateTime", dateTime)
    }
}

private fun leaf(key: String, operator: String, value: String): JsonObject =
    buildJsonObject {
        putJsonObject(key) {
            put("operator", operator)
            put("value", value)
        }
    }
