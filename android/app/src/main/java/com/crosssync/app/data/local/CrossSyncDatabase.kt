package com.crosssync.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.crosssync.app.data.local.dao.ClipboardItemDao
import com.crosssync.app.data.local.dao.TransferItemDao
import com.crosssync.app.data.local.entity.ClipboardItemEntity
import com.crosssync.app.data.local.entity.TransferItemEntity

/**
 * Room database for offline caching of transfer history and clipboard items.
 */
@Database(
    entities = [
        TransferItemEntity::class,
        ClipboardItemEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class CrossSyncDatabase : RoomDatabase() {
    abstract fun transferItemDao(): TransferItemDao
    abstract fun clipboardItemDao(): ClipboardItemDao
}
