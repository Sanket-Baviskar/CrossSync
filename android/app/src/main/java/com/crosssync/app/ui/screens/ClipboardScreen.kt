package com.crosssync.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crosssync.app.data.local.entity.ClipboardContentType
import com.crosssync.app.data.local.entity.ClipboardItemEntity
import com.crosssync.app.data.local.entity.ClipboardSource
import com.crosssync.app.ui.components.*
import com.crosssync.app.ui.theme.*
import com.crosssync.app.ui.viewmodel.ClipboardViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Clipboard history screen — mirrors [ClipboardHistoryPage.xaml] on Windows.
 *
 * Displays a scrollable list of clipboard items synced between Android and PC,
 * with source badges, timestamps, and copy/delete actions.
 */
@Composable
fun ClipboardScreen(
    viewModel: ClipboardViewModel = hiltViewModel(),
) {
    val history by viewModel.clipboardHistory.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDeep),
    ) {
        // ---- Top bar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Clipboard",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = "Clear clipboard history",
                        tint = OnSurfaceSecondary,
                    )
                }
            }
        }

        // ---- Quick Text Composer & Sync Button ----
        var textToSend by remember { mutableStateOf("") }
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textToSend,
                        onValueChange = { textToSend = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type or paste text to send to PC...", style = MaterialTheme.typography.bodySmall) },
                        maxLines = 3,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (textToSend.isNotBlank()) {
                                viewModel.sendText(textToSend)
                                textToSend = ""
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = CrossSyncCyan)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send to PC", tint = BackgroundDeep)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = CrossSyncCyan.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Rounded.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = CrossSyncCyan
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Auto-Sync 24/7 Active",
                                style = MaterialTheme.typography.labelMedium,
                                color = CrossSyncCyan
                            )
                            Text(
                                text = "Copies in any app are sent to PC automatically",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceSecondary
                            )
                        }
                    }
                    TextButton(
                        onClick = { viewModel.syncCurrentClipboard() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(14.dp), tint = CrossSyncCyan)
                        Spacer(Modifier.width(4.dp))
                        Text("Force Sync", style = MaterialTheme.typography.labelSmall, color = CrossSyncCyan)
                    }
                }
            }
        }

        // ---- Content ----
        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.ContentPasteOff,
                        contentDescription = null,
                        tint = OnSurfaceTertiary,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No clipboard items synced yet",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Copy text on your PC to see it appear here",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(history, key = { it.id }) { item ->
                    ClipboardItemCard(
                        item = item,
                        onCopy = { viewModel.copyToClipboard(item) },
                        onDelete = { viewModel.deleteItem(item.id) },
                    )
                }
            }
        }
    }

    // ---- Clear all confirmation dialog ----
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear History") },
            text = { Text("Delete all synced clipboard items?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearDialog = false
                }) {
                    Text("Clear", color = StatusDisconnected)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = SurfaceVariant,
        )
    }
}

/** Decodes a downscaled preview off the main thread; null while loading or if the file is gone. */
@Composable
private fun rememberThumbnail(path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) { bitmap = loadThumbnail(path) }
    return bitmap
}

private suspend fun loadThumbnail(path: String?): ImageBitmap? = withContext(Dispatchers.IO) {
    if (path == null || !File(path).exists()) return@withContext null
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 320 || bounds.outWidth / (sample * 2) >= 640) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

private val timeFormatter = DateTimeFormatter
    .ofPattern("MMM d, HH:mm")
    .withZone(ZoneId.systemDefault())

@Composable
private fun ClipboardItemCard(
    item: ClipboardItemEntity,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // Source badge + time
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (item.source == ClipboardSource.PC)
                    Icons.Rounded.Computer else Icons.Rounded.PhoneAndroid,
                contentDescription = null,
                tint = if (item.source == ClipboardSource.PC) CrossSyncCyan else CrossSyncViolet,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (item.source == ClipboardSource.PC) "From PC" else "From Android",
                style = MaterialTheme.typography.labelSmall,
                color = if (item.source == ClipboardSource.PC) CrossSyncCyan else CrossSyncViolet,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = timeFormatter.format(item.receivedAt),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceTertiary,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Content
        when (item.contentType) {
            ClipboardContentType.TEXT -> {
                Text(
                    text = item.text ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                    color = OnSurfacePrimary,
                )
            }
            ClipboardContentType.IMAGE -> {
                val thumbnail = rememberThumbnail(item.imagePath)
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = "Copied image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Image,
                        contentDescription = null,
                        tint = CrossSyncViolet,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (item.imagePath != null && File(item.imagePath).exists()) "Image" else "Image (expired)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCopy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CrossSyncCyan),
                border = androidx.compose.foundation.BorderStroke(1.dp, CrossSyncCyan.copy(alpha = 0.4f)),
            ) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete",
                    tint = OnSurfaceTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
