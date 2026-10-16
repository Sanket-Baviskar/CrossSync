package com.crosssync.app.data.local.dao

import androidx.room.*
import com.crosssync.app.data.local.entity.ClipboardItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardItemDao {

    @Query("SELECT * FROM clipboard_items WHERE isDeleted = 0 ORDER BY receivedAt DESC LIMIT 100")
    fun observeAll(): Flow<List<ClipboardItemEntity>>

    @Query("SELECT * FROM clipboard_items WHERE id = :id")
    suspend fun getById(id: String): ClipboardItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ClipboardItemEntity)

    @Query("SELECT imagePath FROM clipboard_items WHERE imagePath IS NOT NULL")
    suspend fun imagePaths(): List<String>

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM clipboard_items")
    suspend fun clearAll()

    /** Keeps the newest [keep] entries so history doesn't grow without bound. */
    @Query("DELETE FROM clipboard_items WHERE isDeleted = 1 OR id NOT IN (SELECT id FROM clipboard_items ORDER BY receivedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM clipboard_items WHERE isDeleted = 1")
    suspend fun purgeDeleted()
}
