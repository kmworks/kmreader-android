package com.kmworks.kmreader.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local catalog cache. Version 2 added cached_books.mediaType; version 3 added cached_books.seriesId so
 * offline downloads can recover the owning series. The schema is a pure cache, so a schema change may still
 * drop the rows (see DatabaseModule); migrations keep the cached catalog across upgrades, and anything newer
 * falls back to the destructive path.
 */
@Database(
    entities = [
        CachedBookEntity::class,
        CachedSeriesEntity::class,
        CachedListEntity::class,
        CachedListRowEntity::class,
        CachedDashboardSectionEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class KmReaderDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
