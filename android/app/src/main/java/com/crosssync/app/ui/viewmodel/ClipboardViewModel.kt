package com.crosssync.app.ui.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crosssync.app.data.local.entity.ClipboardItemEntity
import com.crosssync.app.data.repository.ClipboardRepository
import com.crosssync.app.utils.ClipContent
import com.crosssync.app.utils.ClipboardSync
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel backing [ClipboardScreen].
 * Mirrors [ClipboardHistoryPage.xaml] functionality on Windows.
 */
@HiltViewModel
class ClipboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ClipboardRepository,
) : ViewModel() {

    /** Live list of non-deleted clipboard history (newest first). */
    val clipboardHistory: StateFlow<List<ClipboardItemEntity>> =
        repository.clipboardHistory
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Copies a history item (text or image) back to the system clipboard. */
    fun copyToClipboard(item: ClipboardItemEntity) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = when {
            item.text != null -> ClipData.newPlainText("CrossSync", item.text)
            item.imagePath != null && File(item.imagePath).exists() ->
                ClipboardSync.imageClip(context, File(item.imagePath))
            else -> return
        }
        cm.setPrimaryClip(clip)
    }

    /** Sends explicit text to the connected PC via TcpConnectionService. */
    fun sendText(text: String) {
        if (text.isNotBlank()) {
            com.crosssync.app.service.TcpConnectionService.sendClipboardText(context, text)
        }
    }

    /** Reads the current device clipboard and syncs it to the connected PC. */
    fun syncCurrentClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        // An explicit tap: send even if this exact clip was synced before
        when (val content = ClipboardSync.read(context, clip)) {
            is ClipContent.Text -> sendText(content.text)
            is ClipContent.Image ->
                com.crosssync.app.service.TcpConnectionService.sendClipboardImage(context, content.file.absolutePath, content.mime)
            null -> Unit
        }
    }

    /** Soft-deletes a clipboard history entry. */
    fun deleteItem(id: String) = viewModelScope.launch {
        repository.delete(id)
    }

    /** Clears all clipboard history. */
    fun clearAll() = viewModelScope.launch {
        repository.clearAll()
    }
}
