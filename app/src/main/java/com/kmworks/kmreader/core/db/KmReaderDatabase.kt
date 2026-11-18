package com.kmworks.kmreader.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local catalog cache. Version 1; the schema is a pure cache, so a schema change simply drops the
 * rows (see DatabaseModule) instead of running migrations.
 */
@Database(
    entities = [
        CachedBookEntity::class,
        CachedSeriesEntity::class,
        CachedListEntity::class,
        CachedListRowEntity::class,
        CachedDashboardSectionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KmReaderDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
