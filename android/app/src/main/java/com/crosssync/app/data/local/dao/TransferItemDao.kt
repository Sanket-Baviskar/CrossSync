package com.crosssync.app.data.local.dao

import androidx.room.*
import com.crosssync.app.data.local.entity.TransferItemEntity
import com.crosssync.app.data.local.entity.TransferStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferItemDao {

    @Query("SELECT * FROM transfer_items ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TransferItemEntity>>

    @Query("SELECT * FROM transfer_items WHERE status = :status ORDER BY startedAt DESC")
    fun observeByStatus(status: TransferStatus): Flow<List<TransferItemEntity>>

    @Query("SELECT * FROM transfer_items WHERE id = :id")
    suspend fun getById(id: String): TransferItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TransferItemEntity)

    @Update
    suspend fun update(item: TransferItemEntity)

    @Query("UPDATE transfer_items SET bytesTransferred = :bytes, speedBps = :speed WHERE id = :id")
    suspend fun updateProgress(id: String, bytes: Long, speed: Long)

    @Query("UPDATE transfer_items SET status = :status, completedAt = :completedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: TransferStatus, completedAt: Long?)

    @Query("UPDATE transfer_items SET status = 'COMPLETED', completedAt = :completedAt, localPath = :localPath, bytesTransferred = fileSizeBytes, speedBps = 0 WHERE id = :id")
    suspend fun markCompleted(id: String, completedAt: Long, localPath: String)

    /** Transfers left ACTIVE/PAUSED by a killed process can never finish. */
    @Query("UPDATE transfer_items SET status = 'FAILED', completedAt = :completedAt, speedBps = 0, errorMessage = :error WHERE status IN ('ACTIVE', 'PAUSED')")
    suspend fun failAllActive(completedAt: Long, error: String)

    @Query("DELETE FROM transfer_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM transfer_items WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearCompleted()
}
