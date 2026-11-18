package com.kmworks.kmreader.core.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KmReaderDatabase =
        Room.databaseBuilder(context, KmReaderDatabase::class.java, "kmreader_cache.db")
            // Pure cache: on a schema bump the old file is dropped rather than migrated.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideCatalogDao(database: KmReaderDatabase): CatalogDao = database.catalogDao()
}
