package com.kmworks.kmreader.ui.lists

import com.kmworks.kmreader.data.SmartListTarget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Editor state -> server search document (mirrors the key structure of the original
 * SmartListFilterMapper.buildCondition).
 * Document shape: {"condition": <node>} plus an optional sibling {"fullTextSearch": "..."}; a node is
 * either a bare leaf {"<key>": {"operator": op, "value": v}} or a group {"allOf": [...]} / {"anyOf": [...]}.
 * Libraries are emitted first (single leaf, or anyOf for several), exactly like the original builder;
 * with nothing at all the document is empty (matches everything).
 * Note: "Pages" has no counterpart key in the original condition DSL, so the editor does
 * not offer that field (zero API difference).
 */
fun buildSmartListSearchDocument(
    matchAll: Boolean,
    rules: List<SmartListRule>,
    libraryIds: List<String> = emptyList(),
    fullTextSearch: String = "",
): JsonObject {
    val nodes = buildList {
        libraryNode(libraryIds)?.let(::add)
        addAll(rules.mapNotNull { it.toConditionNode() })
    }
    val condition = when {
        nodes.isEmpty() -> null
        nodes.size == 1 -> nodes.first()
        else -> buildJsonObject {
            putJsonArray(if (matchAll) "allOf" else "anyOf") { nodes.forEach { add(it) } }
        }
    }
    val text = fullTextSearch.trim()
    return buildJsonObject {
        condition?.let { put("condition", it) }
        if (text.isNotEmpty()) put("fullTextSearch", text)
    }
}

/** Library scope: one leaf, or anyOf of leaves for several (mirrors the original builder). */
private fun libraryNode(libraryIds: List<String>): JsonObject? = when (libraryIds.size) {
    0 -> null
    1 -> leaf("libraryId", "is", libraryIds[0])
    else -> buildJsonObject {
        putJsonArray("anyOf") {
            libraryIds.forEach { add(leaf("libraryId", "is", it)) }
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
