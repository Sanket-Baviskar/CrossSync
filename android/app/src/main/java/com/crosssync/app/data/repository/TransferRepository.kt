package com.crosssync.app.data.repository

import com.crosssync.app.data.local.dao.TransferItemDao
import com.crosssync.app.data.local.entity.TransferDirection
import com.crosssync.app.data.local.entity.TransferItemEntity
import com.crosssync.app.data.local.entity.TransferStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository managing the lifecycle of file transfers.
 *
 * Acts as the single source of truth for transfer state, bridging
 * [TcpConnectionService] (producer) and UI ViewModels (consumer).
 */
@Singleton
class TransferRepository @Inject constructor(
    private val dao: TransferItemDao,
) {

    /** Live stream of all transfers ordered by start time descending. */
    val allTransfers: Flow<List<TransferItemEntity>> = dao.observeAll()

    /** Live stream of only active/in-progress transfers. */
    val activeTransfers: Flow<List<TransferItemEntity>> =
        dao.observeByStatus(TransferStatus.ACTIVE)

    /**
     * Creates a new transfer record in the database.
     * Returns the generated transfer ID.
     */
    suspend fun createTransfer(
        fileName: String,
        fileSizeBytes: Long,
        direction: TransferDirection,
        sha256: String = "",
        localPath: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            TransferItemEntity(
                id = id,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                direction = direction,
                sha256 = sha256,
                localPath = localPath,
            )
        )
        return id
    }

    /** Updates progress counters for an active transfer. */
    suspend fun updateProgress(id: String, bytesTransferred: Long, speedBps: Long) {
        dao.updateProgress(id, bytesTransferred, speedBps)
    }

    /** Marks a transfer as completed; [localPath] is where a received file was saved. */
    suspend fun markCompleted(id: String, localPath: String? = null) {
        if (localPath != null) {
            dao.markCompleted(id, Instant.now().toEpochMilli(), localPath)
        } else {
            dao.updateStatus(id, TransferStatus.COMPLETED, Instant.now().toEpochMilli())
        }
    }

    /** Fails transfers a previous process left active (the app was killed mid-transfer). */
    suspend fun failInterrupted() {
        dao.failAllActive(Instant.now().toEpochMilli(), "Interrupted")
    }

    /** Marks a transfer as failed with an optional error message. */
    suspend fun markFailed(id: String, error: String? = null) {
        val item = dao.getById(id) ?: return
        dao.upsert(
            item.copy(
                status = TransferStatus.FAILED,
                completedAt = Instant.now(),
                errorMessage = error,
            )
        )
    }

    /** Marks a transfer as paused. */
    suspend fun markPaused(id: String) {
        dao.updateStatus(id, TransferStatus.PAUSED, null)
    }

    /** Marks a paused transfer as resumed (active). */
    suspend fun markResumed(id: String) {
        dao.updateStatus(id, TransferStatus.ACTIVE, null)
    }

    /** Cancels a transfer. */
    suspend fun cancel(id: String) {
        dao.updateStatus(id, TransferStatus.CANCELLED, Instant.now().toEpochMilli())
    }

    /** Clears all completed, failed, and cancelled transfers. */
    suspend fun clearCompleted() = dao.clearCompleted()
}
