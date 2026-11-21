package com.kmworks.kmreader.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local catalog cache. Version 2 added cached_books.mediaType; the schema is a pure cache, so a schema
 * change may still drop the rows (see DatabaseModule) — version 1→2 migrates them with an ALTER TABLE so
 * an upgrade does not blank the cached catalog, and anything newer falls back to the destructive path.
 */
@Database(
    entities = [
        CachedBookEntity::class,
        CachedSeriesEntity::class,
        CachedListEntity::class,
        CachedListRowEntity::class,
        CachedDashboardSectionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class KmReaderDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
