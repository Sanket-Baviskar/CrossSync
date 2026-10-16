package com.crosssync.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crosssync.app.data.local.entity.TransferItemEntity
import com.crosssync.app.data.local.entity.TransferStatus
import com.crosssync.app.data.repository.TransferRepository
import com.crosssync.app.service.PeerDevice
import com.crosssync.app.service.TcpConnectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel backing [TransfersScreen].
 *
 * Exposes split views for active and completed transfers, mirroring
 * the tab structure of [FileTransfersPage.xaml] on Windows.
 */
@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TransferRepository,
) : ViewModel() {

    /** All transfers (newest first). */
    val allTransfers: StateFlow<List<TransferItemEntity>> =
        repository.allTransfers
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Only active / paused transfers. */
    val activeTransfers: StateFlow<List<TransferItemEntity>> =
        allTransfers.map { list ->
            list.filter { it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PAUSED }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Completed, failed, and cancelled transfers. */
    val completedTransfers: StateFlow<List<TransferItemEntity>> =
        allTransfers.map { list ->
            list.filter {
                it.status == TransferStatus.COMPLETED
                        || it.status == TransferStatus.FAILED
                        || it.status == TransferStatus.CANCELLED
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Other phones on the same PC; files can go to them through the PC. */
    val otherDevices: StateFlow<List<PeerDevice>> = TcpConnectionService.otherDevices

    // ---- Actions ----

    /** Sends a picked file to the PC and/or other phones (see [TcpConnectionService.sendFile]). */
    fun sendFile(uri: Uri, toPc: Boolean = true, targetIds: List<String> = emptyList()) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var fileName = "file_${System.currentTimeMillis()}"
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
            }
            // Own folder per send: two picks with the same name can't overwrite each other, and a
            // display name containing '/' can't point outside the cache
            val safeName = fileName.replace('/', '_').replace('\\', '_').ifBlank { "file" }
            val tempDir = File(context.cacheDir, "outgoing/${UUID.randomUUID()}").apply { mkdirs() }
            val tempFile = File(tempDir, safeName)
            val input = resolver.openInputStream(uri) ?: return@runCatching
            input.use { stream ->
                tempFile.outputStream().use { output -> stream.copyTo(output) }
            }
            TcpConnectionService.sendFile(context, tempFile.absolutePath, toPc, targetIds)
        }.onFailure { android.util.Log.w("TransferViewModel", "Could not prepare file: ${it.message}") }
    }

    /** Stops the transfer itself (both sides), not just its record. */
    fun cancelTransfer(id: String) {
        TcpConnectionService.cancelTransfer(context, id)
    }

    fun clearCompleted() = viewModelScope.launch {
        repository.clearCompleted()
    }
}
