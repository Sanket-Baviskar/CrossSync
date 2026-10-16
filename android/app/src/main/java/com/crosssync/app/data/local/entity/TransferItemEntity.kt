package com.crosssync.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/** Transfer direction from Android's perspective. */
enum class TransferDirection { SENDING, RECEIVING }

/** High-level status of a file transfer. */
enum class TransferStatus { ACTIVE, PAUSED, COMPLETED, FAILED, CANCELLED }

/**
 * Room entity representing a single file transfer (upload or download).
 * Mirrors [TransferItem.cs] from the Windows side.
 */
@Entity(tableName = "transfer_items")
data class TransferItemEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val bytesTransferred: Long = 0L,
    val direction: TransferDirection,
    val status: TransferStatus = TransferStatus.ACTIVE,
    /** Bytes per second — updated periodically. */
    val speedBps: Long = 0L,
    /** SHA-256 checksum announced by sender for post-transfer verification. */
    val sha256: String = "",
    /** Absolute path to the local file (source for sends, dest for receives). */
    val localPath: String = "",
    val startedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    /** Error message if [status] is [TransferStatus.FAILED]. */
    val errorMessage: String? = null,
)
