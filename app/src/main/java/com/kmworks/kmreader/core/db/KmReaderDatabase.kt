package com.kmworks.kmreader.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local catalog cache. Version 2 added cached_books.mediaType; version 3 added cached_books.seriesId so
 * offline downloads can recover the owning series; version 4 taught cached_lists its display position and
 * smart lists (and dropped the pre-existing rows, which upsert-only writes had left stale);
 * version 5 added cached_books.epubDivinaCompatible (the reader route needs it beside the media type).
 * The schema is a pure cache, so a schema change may still
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
    version = 5,
    exportSchema = false,
)
abstract class KmReaderDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
