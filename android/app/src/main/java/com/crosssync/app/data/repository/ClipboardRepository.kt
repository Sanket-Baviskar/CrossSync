package com.crosssync.app.data.repository

import com.crosssync.app.data.local.dao.ClipboardItemDao
import com.crosssync.app.data.local.entity.ClipboardContentType
import com.crosssync.app.data.local.entity.ClipboardItemEntity
import com.crosssync.app.data.local.entity.ClipboardSource
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository managing synced clipboard history.
 * Bridges [com.crosssync.app.service.TcpConnectionService] (producer) and [ClipboardViewModel] (consumer).
 */
@Singleton
class ClipboardRepository @Inject constructor(
    private val dao: ClipboardItemDao,
) {

    /** Live stream of clipboard entries (newest first, max 100). */
    val clipboardHistory: Flow<List<ClipboardItemEntity>> = dao.observeAll()

    /** Saves a text clipboard entry received from PC. */
    suspend fun addTextFromPc(text: String) = add(text, ClipboardSource.PC)

    /** Saves a text clipboard entry captured from this Android device. */
    suspend fun addTextFromAndroid(text: String) = add(text, ClipboardSource.ANDROID)

    private suspend fun add(text: String, source: ClipboardSource) {
        dao.upsert(
            ClipboardItemEntity(
                id = UUID.randomUUID().toString(),
                contentType = ClipboardContentType.TEXT,
                text = text,
                source = source,
            )
        )
        dao.trim(MAX_ITEMS)
    }

    /** Saves an image clipboard entry (path to cached bitmap file). */
    suspend fun addImage(imagePath: String, source: ClipboardSource) {
        dao.upsert(
            ClipboardItemEntity(
                id = UUID.randomUUID().toString(),
                contentType = ClipboardContentType.IMAGE,
                imagePath = imagePath,
                source = source,
            )
        )
        dao.trim(MAX_ITEMS)
    }

    /** Deletes a clipboard entry by ID (and its image file, if any). */
    suspend fun delete(id: String) {
        dao.getById(id)?.imagePath?.let { File(it).delete() }
        dao.delete(id)
    }

    /** Deletes all clipboard entries and their image files. */
    suspend fun clearAll() {
        dao.imagePaths().forEach { File(it).delete() }
        dao.clearAll()
    }

    private companion object {
        const val MAX_ITEMS = 500
    }
}
