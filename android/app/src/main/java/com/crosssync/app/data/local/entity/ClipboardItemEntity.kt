package com.crosssync.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/** Source of a clipboard entry. */
enum class ClipboardSource { ANDROID, PC }

/** Type of clipboard data. */
enum class ClipboardContentType { TEXT, IMAGE }

/**
 * Room entity representing a synced clipboard entry.
 * Mirrors [ClipboardPayload.cs] from Windows.
 */
@Entity(tableName = "clipboard_items")
data class ClipboardItemEntity(
    @PrimaryKey val id: String,
    val contentType: ClipboardContentType,
    /** Text content (null for images). */
    val text: String? = null,
    /** Absolute path to cached image file (null for text). */
    val imagePath: String? = null,
    val source: ClipboardSource,
    val receivedAt: Instant = Instant.now(),
    /** Whether this entry has been deleted by the user. */
    val isDeleted: Boolean = false,
)
