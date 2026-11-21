package com.kmworks.kmreader.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** v1 -> v2: cached books learned their server media type (the reader no longer looks the format up). */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cached_books ADD COLUMN mediaType TEXT NOT NULL DEFAULT ''")
        // Rows written before the column existed cannot say which reader they open, and guessing would
        // open an EPUB/PDF book in the DIVINA reader: drop the book rows (not the series/lists) so the next
        // refresh repopulates them with their real media type.
        db.execSQL("DELETE FROM cached_books")
        db.execSQL("DELETE FROM cached_list_rows WHERE itemKind = 'book'")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KmReaderDatabase =
        Room.databaseBuilder(context, KmReaderDatabase::class.java, "kmreader_cache.db")
            // Keep the cached catalog across the mediaType column (the cache is still allowed to be dropped
            // by any later schema bump, which is what the destructive fallback is for).
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideCatalogDao(database: KmReaderDatabase): CatalogDao = database.catalogDao()
}
