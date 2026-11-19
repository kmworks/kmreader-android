package com.kmworks.kmreader.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Reads/writes for the local catalog cache; all queries are scoped to one server instance. */
@Dao
interface CatalogDao {

    @Upsert
    suspend fun upsertBooks(rows: List<CachedBookEntity>)

    @Upsert
    suspend fun upsertSeries(rows: List<CachedSeriesEntity>)

    @Upsert
    suspend fun upsertLists(rows: List<CachedListEntity>)

    @Upsert
    suspend fun upsertListRows(rows: List<CachedListRowEntity>)

    @Upsert
    suspend fun upsertDashboardSections(rows: List<CachedDashboardSectionEntity>)

    @Query("SELECT * FROM cached_books WHERE instanceKey = :instanceKey AND dtoId IN (:ids)")
    suspend fun books(instanceKey: String, ids: List<String>): List<CachedBookEntity>

    /** Whole cached book set of one server instance (offline reading-stats aggregation). */
    @Query("SELECT * FROM cached_books WHERE instanceKey = :instanceKey")
    suspend fun allBooks(instanceKey: String): List<CachedBookEntity>

    /** Whole cached series set of one server instance (offline reading-stats aggregation). */
    @Query("SELECT * FROM cached_series WHERE instanceKey = :instanceKey")
    suspend fun allSeries(instanceKey: String): List<CachedSeriesEntity>

    @Query("SELECT * FROM cached_series WHERE instanceKey = :instanceKey AND dtoId IN (:ids)")
    suspend fun series(instanceKey: String, ids: List<String>): List<CachedSeriesEntity>

    @Query("SELECT * FROM cached_lists WHERE instanceKey = :instanceKey AND kind = :kind ORDER BY name")
    suspend fun lists(instanceKey: String, kind: String): List<CachedListEntity>

    @Query("SELECT * FROM cached_list_rows WHERE instanceKey = :instanceKey AND listKey = :listKey ORDER BY position ASC")
    suspend fun listRows(instanceKey: String, listKey: String): List<CachedListRowEntity>

    @Query("SELECT * FROM cached_dashboard_sections WHERE instanceKey = :instanceKey")
    suspend fun dashboardSections(instanceKey: String): List<CachedDashboardSectionEntity>

    @Query("DELETE FROM cached_list_rows WHERE instanceKey = :instanceKey AND listKey = :listKey")
    suspend fun deleteListRows(instanceKey: String, listKey: String)

    @Query("DELETE FROM cached_books WHERE instanceKey = :instanceKey AND dtoId = :dtoId")
    suspend fun deleteBook(instanceKey: String, dtoId: String)

    @Query("DELETE FROM cached_series WHERE instanceKey = :instanceKey AND dtoId = :dtoId")
    suspend fun deleteSeries(instanceKey: String, dtoId: String)

    @Query("DELETE FROM cached_list_rows WHERE instanceKey = :instanceKey AND itemKind = :itemKind AND dtoId = :dtoId")
    suspend fun deleteListRowsForItem(instanceKey: String, itemKind: String, dtoId: String)

    @Query("DELETE FROM cached_list_rows WHERE instanceKey = :instanceKey AND listKey LIKE :listKeyPrefix || '%'")
    suspend fun deleteListRowsByPrefix(instanceKey: String, listKeyPrefix: String)

    @Query("DELETE FROM cached_lists WHERE instanceKey = :instanceKey")
    suspend fun deleteLists(instanceKey: String)

    @Query("DELETE FROM cached_dashboard_sections WHERE instanceKey = :instanceKey")
    suspend fun deleteDashboardSections(instanceKey: String)

    @Query("DELETE FROM cached_books")
    suspend fun clearBooks()

    @Query("DELETE FROM cached_series")
    suspend fun clearSeries()

    @Query("DELETE FROM cached_lists")
    suspend fun clearLists()

    @Query("DELETE FROM cached_list_rows")
    suspend fun clearListRows()

    @Query("DELETE FROM cached_dashboard_sections")
    suspend fun clearDashboardSections()

    /** Wipe every row: used when the active server changes (all rows are per-server anyway). */
    @Transaction
    suspend fun clearAll() {
        clearBooks()
        clearSeries()
        clearLists()
        clearListRows()
        clearDashboardSections()
    }

    /** Drop one server instance's cached lists and dashboard seeds. */
    @Transaction
    suspend fun clearInstanceLists(instanceKey: String) {
        deleteListRowsByPrefix(instanceKey, "")
        deleteDashboardSections(instanceKey)
    }
}
