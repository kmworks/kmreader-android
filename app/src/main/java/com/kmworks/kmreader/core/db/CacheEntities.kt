package com.kmworks.kmreader.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Local row caches for the catalog lists (the "data cache" iOS gets from GRDB), mirroring the
 * shaped data that list/card rendering needs instead of the full Komga DTOs.
 *
 * Every table is keyed by [instanceKey] (server base URL + user, see ImageCachePaths) so switching
 * servers can never mix rows, and by the Komga string id (dtoId) — never by the UI's in-process
 * Int stable id, which is a session-local hash and must be re-assigned when a row is restored.
 */
@Entity(tableName = "cached_books", primaryKeys = ["instanceKey", "dtoId"])
data class CachedBookEntity(
    val instanceKey: String,
    val dtoId: String,
    val title: String,
    val number: String,
    val seriesTitle: String,
    /** Owning series dto id; lets offline downloads recover their series badge and series detail. */
    val seriesId: String? = null,
    val hue: Float,
    val pageCount: Int,
    val progress: Float?,
    val oneshot: Boolean,
    val author: String?,
    val mediaStatus: String,
    /**
     * Server media type; decides which reader opens (EPUB / PDF / DIVINA). The SQL default is declared so
     * the v1 -> v2 ALTER TABLE and a fresh install both produce the same column Room validates against.
     */
    @ColumnInfo(defaultValue = "''")
    val mediaType: String = "",
    val lastReadAtMs: Long?,
    /** Wall-clock write time; kept for diagnostics/future expiry, not read by the UI yet. */
    val updatedAt: Long,
)

@Entity(tableName = "cached_series", primaryKeys = ["instanceKey", "dtoId"])
data class CachedSeriesEntity(
    val instanceKey: String,
    val dtoId: String,
    val title: String,
    val author: String,
    val hue: Float,
    val bookCount: Int,
    val unreadCount: Int,
    val progress: Float?,
    val updatedAt: Long,
)

/** Collection/read-list rows (the Lists screen and the dashboard's list shortcuts). */
@Entity(tableName = "cached_lists", primaryKeys = ["instanceKey", "kind", "dtoId"])
data class CachedListEntity(
    val instanceKey: String,
    /** [LIST_KIND_COLLECTION] or [LIST_KIND_READLIST]. */
    val kind: String,
    val dtoId: String,
    val name: String,
    val count: Int,
    val hue: Float,
    val pinned: Boolean,
    val updatedAt: Long,
)

/**
 * Ordered membership of a named list (listKey), e.g. "library:series", "library:books".
 * [position] is the order the screen showed, so a restored list keeps its sort.
 */
@Entity(tableName = "cached_list_rows", primaryKeys = ["instanceKey", "listKey", "position"])
data class CachedListRowEntity(
    val instanceKey: String,
    val listKey: String,
    val position: Int,
    /** [ITEM_KIND_BOOK] / [ITEM_KIND_SERIES] / [ITEM_KIND_COLLECTION] / [ITEM_KIND_READLIST]. */
    val itemKind: String,
    val dtoId: String,
)

/**
 * First-page id list of one dashboard section (the iOS DashboardSectionCacheStore equivalent).
 * [itemsJson] is a JSON array of {"kind":"book|series","dtoId":"..."} refs; the shaped rows above
 * supply the card fields.
 */
@Entity(tableName = "cached_dashboard_sections", primaryKeys = ["instanceKey", "sectionId"])
data class CachedDashboardSectionEntity(
    val instanceKey: String,
    val sectionId: String,
    val itemsJson: String,
    val updatedAt: Long,
)

internal const val LIST_KIND_COLLECTION = "collection"
internal const val LIST_KIND_READLIST = "readlist"

internal const val ITEM_KIND_BOOK = "book"
internal const val ITEM_KIND_SERIES = "series"
internal const val ITEM_KIND_COLLECTION = "collection"
internal const val ITEM_KIND_READLIST = "readlist"
