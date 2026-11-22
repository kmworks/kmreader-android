package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.data.BrowseContentType
import com.kmworks.kmreader.data.BrowseOptions
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.data.ReadStatus
import com.kmworks.kmreader.data.SeriesStatus
import com.kmworks.kmreader.data.SmartListTarget
import com.kmworks.kmreader.data.TriState
import com.kmworks.kmreader.data.defaultBrowseOptions
import com.kmworks.kmreader.data.filterKey
import com.kmworks.kmreader.data.notableEntityFromFilterKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Two-way mapping between a smart list's stored search document and the app's browse options
 * (mirrors KMReader/Features/SmartList/Models/SmartListFilterMapper.swift). The reverse
 * direction only recognizes the condition shapes [searchDocument] can emit; anything else sets
 * [SmartListFilterState.lossy] so the caller can warn instead of silently dropping filters on save.
 */
data class SmartListFilterState(
    val contentType: BrowseContentType,
    val options: BrowseOptions,
    val libraryIds: List<String> = emptyList(),
    val fullTextSearch: String = "",
    val lossy: Boolean = false,
) {
    /** Per-value granularity like the web UI's activeFilterCount; each active tri-state flag counts once. */
    val activeFilterCount: Int
        get() = SmartListFilterMapper.activeFilterCount(contentType, options)
}

object SmartListFilterMapper {

    /** Number of active browse conditions (mirrors the iOS activeFilterCount; full text/libraries excluded). */
    fun activeFilterCount(contentType: BrowseContentType, options: BrowseOptions): Int {
        val flags = listOf(options.oneshot, options.deleted).count { it != TriState.Off } +
            (if (contentType == BrowseContentType.Series) {
                listOf(options.complete).count { it != TriState.Off }
            } else {
                0
            })
        val statuses = options.readStatuses.values.count { it != TriState.Off } +
            (if (contentType == BrowseContentType.Series) {
                options.seriesStatuses.size + options.excludedSeriesStatuses.size
            } else {
                0
            })
        val metadata = options.authors.size + options.tags.size + options.notableEntities.size +
            (if (contentType == BrowseContentType.Series) {
                options.publishers.size + options.genres.size + options.languages.size +
                    options.ageRatings.size + options.releaseYears.size
            } else {
                0
            })
        return flags + statuses + metadata
    }

    /** Smart list target -> browse vocabulary. */
    fun browseContentType(target: SmartListTarget): BrowseContentType = when (target) {
        SmartListTarget.Book -> BrowseContentType.Books
        SmartListTarget.Series -> BrowseContentType.Series
    }

    /** Forward: browse options + library scope + full text -> the stored search document. */
    fun searchDocument(
        contentType: BrowseContentType,
        options: BrowseOptions,
        libraryIds: List<String>,
        fullTextSearch: String,
    ): JsonObject = searchBody(
        condition = combinedCondition(conditions(contentType, options, libraryIds)),
        fullTextSearch = fullTextSearch.trim().ifBlank { null },
    )

    /** Reverse: stored search document -> editable state (lossy when the document holds more than browse options can express). */
    fun filterState(contentType: BrowseContentType, search: JsonObject?): SmartListFilterState {
        val parser = SearchDocumentParser(contentType)
        parser.parse(search)
        return SmartListFilterState(
            contentType = contentType,
            options = parser.toBrowseOptions(),
            libraryIds = parser.libraryIds.toList(),
            fullTextSearch = parser.fullTextSearch,
            lossy = parser.lossy,
        )
    }

    private fun conditions(
        contentType: BrowseContentType,
        options: BrowseOptions,
        libraryIds: List<String>,
    ): List<JsonObject> {
        val conditions = mutableListOf<JsonObject>()
        if (libraryIds.isNotEmpty()) conditions += libraryCondition(libraryIds)
        conditions += readStatusConditions(options)
        if (contentType == BrowseContentType.Series) {
            conditions += seriesStatusGroupings(options)
        }
        if (options.oneshot == TriState.Include) {
            conditions += flagLeafCondition("oneShot", "isTrue")
        }
        if (options.oneshot == TriState.Exclude) {
            conditions += flagLeafCondition("oneShot", "isFalse")
        }
        if (options.deleted == TriState.Include) {
            conditions += flagLeafCondition("deleted", "isTrue")
        }
        if (options.deleted == TriState.Exclude) {
            conditions += flagLeafCondition("deleted", "isFalse")
        }
        if (contentType == BrowseContentType.Series) {
            if (options.complete == TriState.Include) {
                conditions += flagLeafCondition("complete", "isTrue")
            }
            if (options.complete == TriState.Exclude) {
                conditions += flagLeafCondition("complete", "isFalse")
            }
        }
        if (options.authors.isNotEmpty()) {
            val leaves = options.authors.map(::authorCondition)
            conditions += if (options.authorLogicAnd) allOfCondition(leaves) else anyOfCondition(leaves)
        }
        if (options.tags.isNotEmpty()) {
            val leaves = options.tags.map { leafCondition("tag", "is", it) }
            conditions += if (options.tagLogicAnd) allOfCondition(leaves) else anyOfCondition(leaves)
        }
        if (options.notableEntities.isNotEmpty()) {
            val leaves = options.notableEntities.map { notableEntityCondition(it.kind, it.name) }
            conditions += if (options.notableEntitiesAllMatch) allOfCondition(leaves) else anyOfCondition(leaves)
        }
        if (contentType == BrowseContentType.Series) {
            if (options.publishers.isNotEmpty()) {
                conditions += allOfCondition(options.publishers.map { leafCondition("publisher", "is", it) })
            }
            if (options.genres.isNotEmpty()) {
                conditions += allOfCondition(options.genres.map { leafCondition("genre", "is", it) })
            }
            if (options.languages.isNotEmpty()) {
                conditions += allOfCondition(options.languages.map { leafCondition("language", "is", it) })
            }
            if (options.ageRatings.isNotEmpty()) {
                conditions += allOfCondition(options.ageRatings.mapNotNull(::ageRatingCondition))
            }
            val years = options.releaseYears.mapNotNull { it.trim().toIntOrNull() }
            if (years.isNotEmpty()) {
                conditions += allOfCondition(years.map(::releaseYearCondition))
            }
        }
        return conditions
    }

    /** Included statuses are one anyOf, excluded ones one allOf, as separate conditions (mirrors the iOS branches). */
    private fun readStatusConditions(options: BrowseOptions): List<JsonObject> {
        val includes = options.readStatuses
            .filterValues { it == TriState.Include }
            .keys
            .map { leafCondition("readStatus", "is", readStatusApiName(it)) }
        val excludes = options.readStatuses
            .filterValues { it == TriState.Exclude }
            .keys
            .map { leafCondition("readStatus", "isNot", readStatusApiName(it)) }
        return listOfNotNull(
            includes.takeIf { it.isNotEmpty() }?.let(::anyOfCondition),
            excludes.takeIf { it.isNotEmpty() }?.let(::allOfCondition),
        )
    }

    private fun seriesStatusGroupings(options: BrowseOptions): List<JsonObject> {
        val wrap: (List<JsonObject>) -> JsonObject =
            if (options.seriesStatusAllMatch) ::allOfCondition else ::anyOfCondition
        return listOfNotNull(
            options.seriesStatuses
                .takeIf { it.isNotEmpty() }
                ?.let { statuses -> wrap(statuses.map { leafCondition("seriesStatus", "is", it.apiName) }) },
            options.excludedSeriesStatuses
                .takeIf { it.isNotEmpty() }
                ?.let { statuses -> wrap(statuses.map { leafCondition("seriesStatus", "isNot", it.apiName) }) },
        )
    }

    private fun ageRatingCondition(raw: String): JsonObject? {
        val rating = raw.trim().toIntOrNull() ?: return null
        return buildJsonObject {
            putJsonObject("ageRating") {
                put("operator", "is")
                put("value", rating)
            }
        }
    }

    /** One year Y as the after (Y-1)-12-31 / before (Y+1)-01-01 date pair (mirrors the iOS releaseYear branch). */
    private fun releaseYearCondition(year: Int): JsonObject = allOfCondition(
        listOf(
            dateLeafCondition("releaseDate", "after", "${year - 1}-12-31T12:00:00Z"),
            dateLeafCondition("releaseDate", "before", "${year + 1}-01-01T12:00:00Z"),
        ),
    )

    private fun readStatusApiName(status: ReadStatus): String = when (status) {
        ReadStatus.Unread -> "UNREAD"
        ReadStatus.InProgress -> "IN_PROGRESS"
        ReadStatus.Read -> "READ"
    }
}

/** Reverse document walker: keeps every value it can attribute, sets [lossy] for anything else. */
private class SearchDocumentParser(private val contentType: BrowseContentType) {
    private val target = contentType

    val libraryIds = mutableListOf<String>()
    var includeReadStatuses = linkedSetOf<ReadStatus>()
    var excludeReadStatuses = linkedSetOf<ReadStatus>()
    var includeSeriesStatuses = linkedSetOf<SeriesStatus>()
    var excludeSeriesStatuses = linkedSetOf<SeriesStatus>()
    var seriesStatusAllMatch = true
    var oneshot = TriState.Off
    var deleted = TriState.Off
    var complete = TriState.Off
    var fullTextSearch = ""
    var lossy = false

    // Per-dimension values + logic (mirrors MetadataFilterConfig of the iOS mapper).
    var authors: List<String>? = null
    var authorsAllMatch = true
    var tags: List<String>? = null
    var tagsAllMatch = true
    var notableEntities: List<NotableEntity>? = null
    var notableEntitiesAllMatch = true
    var publishers: List<String>? = null
    var genres: List<String>? = null
    var languages: List<String>? = null
    var ageRatings: List<String>? = null
    var releaseYears: List<String>? = null

    fun parse(document: JsonObject?) {
        if (document == null) return
        document["fullTextSearch"].asString()?.let { fullTextSearch = it }
        val condition = document["condition"] ?: return
        if (condition is JsonNull) return
        if (condition !is JsonObject) {
            lossy = true
            return
        }
        if (condition.isEmpty()) return
        parseRoot(condition)
    }

    fun toBrowseOptions(): BrowseOptions = defaultBrowseOptions(contentType).copy(
        readStatuses = buildMap {
            includeReadStatuses.forEach { put(it, TriState.Include) }
            excludeReadStatuses.forEach { put(it, TriState.Exclude) }
        },
        oneshot = oneshot,
        deleted = deleted,
        complete = complete,
        authors = authors.orEmpty(),
        authorLogicAnd = authorsAllMatch,
        tags = tags.orEmpty(),
        tagLogicAnd = tagsAllMatch,
        notableEntities = notableEntities.orEmpty(),
        notableEntitiesAllMatch = notableEntitiesAllMatch,
        publishers = publishers.orEmpty(),
        genres = genres.orEmpty(),
        ageRatings = ageRatings.orEmpty(),
        languages = languages.orEmpty(),
        releaseYears = releaseYears.orEmpty(),
        seriesStatuses = includeSeriesStatuses.toList(),
        excludedSeriesStatuses = excludeSeriesStatuses.toList(),
        seriesStatusAllMatch = seriesStatusAllMatch,
    )

    /** The root is either a single bare condition, a homogeneous anyOf group, or the allOf list the builder emits. */
    private fun parseRoot(condition: JsonObject) {
        val allOf = condition.groupElements("allOf")
        val anyOf = condition.groupElements("anyOf")
        when {
            allOf != null -> allOf.forEach { element ->
                val node = element as? JsonObject
                if (node == null) lossy = true else parseNode(node)
            }
            anyOf != null -> parseGroup(anyOf, allMatch = false)
            else -> condition.leafParts()
                ?.let { (key, body) -> parseLeaf(key, body) }
                ?: run { lossy = true }
        }
    }

    private fun parseNode(node: JsonObject) {
        // A single release-year filter wraps the year pair in allOf, and a lone wrapper becomes the root itself.
        releaseYear(node)?.let { year ->
            if (target != BrowseContentType.Series) {
                lossy = true
            } else {
                releaseYears = releaseYears.orEmpty() + listOf(year.toString())
            }
            return
        }
        val allOf = node.groupElements("allOf")
        val anyOf = node.groupElements("anyOf")
        when {
            allOf != null -> parseGroup(allOf, allMatch = true)
            anyOf != null -> parseGroup(anyOf, allMatch = false)
            else -> node.leafParts()
                ?.let { (key, body) -> parseLeaf(key, body) }
                ?: run { lossy = true }
        }
    }

    /** A wrapper group is representable only when homogeneous: every element a release-year pair or a leaf on the same key. */
    private fun parseGroup(elements: List<JsonElement>, allMatch: Boolean) {
        if (elements.isEmpty()) {
            lossy = true
            return
        }
        val years = mutableListOf<Int>()
        var allYearPairs = true
        for (element in elements) {
            val year = (element as? JsonObject)?.let(::releaseYear)
            if (year == null) {
                allYearPairs = false
                break
            }
            years += year
        }
        if (allYearPairs) {
            if (target != BrowseContentType.Series) {
                lossy = true
                return
            }
            if (!allMatch) lossy = true // the browse options cannot express an anyOf year group
            releaseYears = releaseYears.orEmpty() + years.map(Int::toString)
            return
        }

        val leaves = mutableListOf<Pair<String, JsonObject>>()
        for (element in elements) {
            val leaf = (element as? JsonObject)?.leafParts()
            if (leaf == null) {
                lossy = true
                return
            }
            leaves += leaf
        }
        val key = leaves.firstOrNull()?.first ?: return
        if (leaves.any { it.first != key }) {
            lossy = true
            return
        }
        parseLeafGroup(key, leaves.map { it.second }, allMatch)
    }

    /** Groups apply atomically: a malformed element skips the whole group so a partial filter never looks intentional. */
    private fun parseLeafGroup(key: String, bodies: List<JsonObject>, allMatch: Boolean) {
        when (key) {
            "libraryId" -> {
                if (allMatch) {
                    lossy = true
                    return
                }
                val ids = mutableListOf<String>()
                for (body in bodies) {
                    if (!body.isOperator("is") || body["value"].asString() == null) {
                        lossy = true
                        return
                    }
                    ids += body["value"].asString()!!
                }
                libraryIds += ids
            }
            "readStatus" -> {
                val include = !allMatch && bodies.all { it.isOperator("is") }
                val exclude = allMatch && bodies.all { it.isOperator("isNot") }
                if (!include && !exclude) {
                    lossy = true
                    return
                }
                val statuses = mutableListOf<ReadStatus>()
                for (body in bodies) {
                    val raw = body["value"].asString()
                    val status = raw?.let(::readStatusFromApiName)
                    if (status == null) {
                        lossy = true
                        return
                    }
                    statuses += status
                }
                if (include) includeReadStatuses += statuses else excludeReadStatuses += statuses
            }
            "seriesStatus" -> {
                if (target != BrowseContentType.Series) {
                    lossy = true
                    return
                }
                val include = bodies.all { it.isOperator("is") }
                val exclude = bodies.all { it.isOperator("isNot") }
                if (include == exclude) {
                    lossy = true
                    return
                }
                val statuses = mutableListOf<SeriesStatus>()
                for (body in bodies) {
                    val status = body["value"].asString()?.let(SeriesStatus::fromApiName)
                    if (status == null) {
                        lossy = true
                        return
                    }
                    statuses += status
                }
                if (include) includeSeriesStatuses += statuses else excludeSeriesStatuses += statuses
                seriesStatusAllMatch = allMatch
            }
            "author", "tag", "genre", "publisher", "language", "ageRating", "notableEntity" ->
                parseMetadataLeafGroup(key, bodies, allMatch)
            else -> lossy = true
        }
    }

    private fun parseLeaf(key: String, body: JsonObject) {
        when (key) {
            "libraryId" -> {
                val id = body["value"].asString()
                if (!body.isOperator("is") || id == null) {
                    lossy = true
                    return
                }
                libraryIds += id
            }
            "readStatus" -> {
                val status = body["value"].asString()?.let(::readStatusFromApiName)
                if (status == null) {
                    lossy = true
                    return
                }
                when {
                    body.isOperator("is") -> includeReadStatuses += status
                    body.isOperator("isNot") -> excludeReadStatuses += status
                    else -> lossy = true
                }
            }
            "seriesStatus" -> {
                val status = if (target == BrowseContentType.Series) {
                    body["value"].asString()?.let(SeriesStatus::fromApiName)
                } else {
                    null
                }
                if (status == null) {
                    lossy = true
                    return
                }
                when {
                    body.isOperator("is") -> includeSeriesStatuses += status
                    body.isOperator("isNot") -> excludeSeriesStatuses += status
                    else -> {
                        lossy = true
                        return
                    }
                }
                seriesStatusAllMatch = true
            }
            "oneShot", "deleted", "complete" -> parseFlagLeaf(key, body)
            "author", "tag", "genre", "publisher", "language", "ageRating", "notableEntity" ->
                parseMetadataLeafGroup(key, listOf(body), allMatch = true)
            else -> lossy = true
        }
    }

    /** isTrue maps to an included flag, isFalse to an excluded one (the tri-state only has a positive flag value). */
    private fun parseFlagLeaf(key: String, body: JsonObject) {
        if (key == "complete" && target != BrowseContentType.Series) {
            lossy = true
            return
        }
        val flag = when {
            body.isOperator("isTrue") -> TriState.Include
            body.isOperator("isFalse") -> TriState.Exclude
            else -> {
                lossy = true
                return
            }
        }
        when (key) {
            "oneShot" -> oneshot = flag
            "deleted" -> deleted = flag
            else -> complete = flag
        }
    }

    private fun parseMetadataLeafGroup(key: String, bodies: List<JsonObject>, allMatch: Boolean) {
        val values = mutableListOf<String>()
        for (body in bodies) {
            val value = if (body.isOperator("is")) metadataValue(key, body) else null
            if (value == null) {
                lossy = true
                return
            }
            values += value
        }
        when (key) {
            "author" -> {
                authors = authors.orEmpty() + values
                authorsAllMatch = allMatch
            }
            "tag" -> {
                tags = tags.orEmpty() + values
                tagsAllMatch = allMatch
            }
            "genre" -> {
                if (!allMatch) lossy = true
                genres = genres.orEmpty() + values
            }
            "publisher" -> {
                if (!allMatch) lossy = true
                publishers = publishers.orEmpty() + values
            }
            "language" -> {
                if (!allMatch) lossy = true
                languages = languages.orEmpty() + values
            }
            "notableEntity" -> {
                val entities = values.mapNotNull(::notableEntityFromFilterKey)
                if (entities.size != values.size) lossy = true
                notableEntities = notableEntities.orEmpty() + entities
                notableEntitiesAllMatch = allMatch
            }
            else -> {
                if (!allMatch) lossy = true
                ageRatings = ageRatings.orEmpty() + values
            }
        }
    }

    /** Book filters only carry authors and tags; the remaining metadata kinds exist only in the series search filters. */
    private fun metadataValue(key: String, body: JsonObject): String? = when (key) {
        "author" -> (body["value"] as? JsonObject)?.get("name").asString()
        "tag" -> body["value"].asString()
        "genre", "publisher", "language" ->
            if (target == BrowseContentType.Series) body["value"].asString() else null
        "notableEntity" -> {
            val value = body["value"] as? JsonObject
            val kind = value?.get("kind").asString()
            val name = value?.get("name").asString()
            if (kind == null || name == null) null else NotableEntity(name = name, kind = kind).filterKey
        }
        "ageRating" -> if (target == BrowseContentType.Series) body["value"].asInt()?.toString() else null
        else -> null
    }

    /** The builder encodes year Y as after (Y-1)-12-31T12:00:00Z paired with before (Y+1)-01-01T12:00:00Z. */
    private fun releaseYear(node: JsonObject): Int? {
        val pair = node.groupElements("allOf")?.takeIf { it.size == 2 } ?: return null
        val after = (pair[0] as? JsonObject)?.leafParts() ?: return null
        val before = (pair[1] as? JsonObject)?.leafParts() ?: return null
        if (after.first != "releaseDate" || before.first != "releaseDate") return null
        if (!after.second.isOperator("after") || !before.second.isOperator("before")) return null
        val afterDate = after.second["dateTime"].asString() ?: return null
        val beforeDate = before.second["dateTime"].asString() ?: return null
        val afterYear = yearPrefix(afterDate, "-12-31T12:00:00Z") ?: return null
        val beforeYear = yearPrefix(beforeDate, "-01-01T12:00:00Z") ?: return null
        if (afterYear + 1 != beforeYear - 1) return null
        return afterYear + 1
    }

    private fun yearPrefix(dateTime: String, suffix: String): Int? =
        dateTime.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)?.toIntOrNull()

    private fun readStatusFromApiName(token: String): ReadStatus? = when (token) {
        "UNREAD" -> ReadStatus.Unread
        "IN_PROGRESS" -> ReadStatus.InProgress
        "READ" -> ReadStatus.Read
        else -> null
    }
}

private fun JsonObject.groupElements(wrapperKey: String): JsonArray? =
    if (size == 1) this[wrapperKey] as? JsonArray else null

private fun JsonObject.leafParts(): Pair<String, JsonObject>? {
    if (size != 1) return null
    val entry = entries.first()
    val body = entry.value as? JsonObject ?: return null
    return entry.key to body
}

private fun JsonObject.isOperator(name: String): Boolean =
    (this["operator"] as? JsonPrimitive)?.let { it.isString && it.content == name } == true

private fun JsonElement?.asString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.asInt(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    primitive.content.toIntOrNull()?.let { return it }
    val double = primitive.content.toDoubleOrNull() ?: return null
    return if (double % 1.0 == 0.0) double.toInt() else null
}

/** Smart list target -> the server's target value. */
fun SmartListTarget.toDtoTarget(): String = when (this) {
    SmartListTarget.Book -> "BOOK"
    SmartListTarget.Series -> "SERIES"
}
