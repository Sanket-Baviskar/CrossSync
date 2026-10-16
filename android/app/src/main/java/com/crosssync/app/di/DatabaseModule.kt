package com.crosssync.app.di

import android.content.Context
import androidx.room.Room
import com.crosssync.app.data.local.CrossSyncDatabase
import com.crosssync.app.data.local.dao.ClipboardItemDao
import com.crosssync.app.data.local.dao.TransferItemDao
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
    fun provideDatabase(@ApplicationContext context: Context): CrossSyncDatabase =
        Room.databaseBuilder(context, CrossSyncDatabase::class.java, "crosssync.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTransferItemDao(db: CrossSyncDatabase): TransferItemDao =
        db.transferItemDao()

    @Provides
    fun provideClipboardItemDao(db: CrossSyncDatabase): ClipboardItemDao =
        db.clipboardItemDao()
}
