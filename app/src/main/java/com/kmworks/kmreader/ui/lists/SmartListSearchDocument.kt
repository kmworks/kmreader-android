package com.kmworks.kmreader.ui.lists

import com.kmworks.kmreader.data.SmartListTarget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 编辑器规则 → 服务端 search 文档（镜像原作 SmartListFilterMapper.buildCondition 的 JSON 键结构）。
 * 文档形态：{"condition": <node>}；节点为裸叶 {"<key>": {"operator": op, "value": v}}
 * 或组 {"allOf": [...]} / {"anyOf": [...]}；无规则时省略 condition 键（空文档 = 全部命中）。
 * 说明：「页数」在原作条件 DSL 中无对应键，编辑器不提供该字段（零 API 差异）。
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

/** 目标类型 → 服务端 target 值。 */
fun SmartListTarget.toDtoTarget(): String = when (this) {
    SmartListTarget.Book -> "BOOK"
    SmartListTarget.Series -> "SERIES"
}

private fun SmartListRule.toConditionNode(): JsonObject? = when (field) {
    "阅读状态" -> {
        val status = when (value) {
            "已读" -> "READ"
            "阅读中" -> "IN_PROGRESS"
            else -> "UNREAD"
        }
        leaf("readStatus", isOperator(), status)
    }
    "标签" -> leaf("tag", isOperator(), value)
    "作者" -> buildJsonObject {
        putJsonObject("author") {
            put("operator", isOperator())
            putJsonObject("value") { put("name", value) }
        }
    }
    "发布年份" -> yearConditionNode()
    else -> null
}

/** 等于/包含/大于/小于 在非日期字段上退化为 is；不等于 → isNot（原作仅产出 is/isNot）。 */
private fun SmartListRule.isOperator(): String = if (operator == "不等于") "isNot" else "is"

/** 年份对齐原作编码：等于=Y 全年区间对，大于=Y 年底之后，小于=次年元旦之前；其余按等于处理。 */
private fun SmartListRule.yearConditionNode(): JsonObject? {
    val year = value.trim().toIntOrNull() ?: return null
    return when (operator) {
        "大于" -> dateLeaf("after", "${year}-12-31T12:00:00Z")
        "小于" -> dateLeaf("before", "${year + 1}-01-01T12:00:00Z")
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
