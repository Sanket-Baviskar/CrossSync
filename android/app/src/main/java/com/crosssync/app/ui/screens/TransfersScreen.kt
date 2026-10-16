package com.crosssync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crosssync.app.data.local.entity.TransferDirection
import com.crosssync.app.data.local.entity.TransferItemEntity
import com.crosssync.app.data.local.entity.TransferStatus
import com.crosssync.app.service.PeerDevice
import com.crosssync.app.ui.components.*
import com.crosssync.app.ui.theme.*
import com.crosssync.app.ui.viewmodel.TransferViewModel
import com.crosssync.app.utils.toReadableSize
import com.crosssync.app.utils.toReadableSpeed

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.crosssync.app.utils.FileOpener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transfers screen — mirrors [FileTransfersPage.xaml] on Windows.
 *
 * Shows two tabs:
 * - **Active** — in-progress and paused transfers with progress bars.
 * - **Completed** — finished, failed, and cancelled transfers.
 */
@Composable
fun TransfersScreen(
    viewModel: TransferViewModel = hiltViewModel(),
) {
    val activeTransfers by viewModel.activeTransfers.collectAsStateWithLifecycle()
    val completedTransfers by viewModel.completedTransfers.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val otherDevices by viewModel.otherDevices.collectAsStateWithLifecycle()
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // With other phones online, ask where it goes; otherwise straight to the PC
        if (otherDevices.isEmpty()) viewModel.sendFile(uri) else pendingUri = uri
    }

    pendingUri?.let { uri ->
        DestinationDialog(
            phones = otherDevices,
            onSend = { toPc, ids ->
                viewModel.sendFile(uri, toPc, ids)
                pendingUri = null
            },
            onDismiss = { pendingUri = null },
        )
    }

    Scaffold(
        containerColor = BackgroundDeep,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePicker.launch("*/*") },
                containerColor = CrossSyncCyan,
                contentColor = BackgroundDeep,
                icon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
                text = { Text("Send File", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                    text = "Transfers",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (completedTransfers.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearCompleted) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = "Clear completed",
                            tint = OnSurfaceSecondary,
                        )
                    }
                }
            }

            // ---- Tab row ----
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SurfaceDark,
                contentColor = CrossSyncCyan,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Active")
                            if (activeTransfers.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Badge { Text("${activeTransfers.size}") }
                            }
                        }
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Completed") },
                )
            }

            // ---- Content ----
            when (selectedTab) {
                0 -> TransferList(
                    transfers = activeTransfers,
                    emptyLabel = "No active transfers — tap 'Send File to PC'",
                    onCancel = viewModel::cancelTransfer,
                )
                1 -> TransferList(
                    transfers = completedTransfers,
                    emptyLabel = "No completed transfers",
                    onCancel = null,
                )
            }
        }
    }
}

/** Pick the PC and/or other phones for a file (PC preselected). */
@Composable
private fun DestinationDialog(
    phones: List<PeerDevice>,
    onSend: (toPc: Boolean, targetIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var toPc by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to") },
        text = {
            Column {
                DestinationRow("PC", toPc) { toPc = it }
                phones.forEach { phone ->
                    DestinationRow(phone.name, phone.id in chosen) { on ->
                        chosen = if (on) chosen + phone.id else chosen - phone.id
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(toPc, chosen.toList()) },
                enabled = toPc || chosen.isNotEmpty(),
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = SurfaceVariant,
    )
}

@Composable
private fun DestinationRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TransferList(
    transfers: List<TransferItemEntity>,
    emptyLabel: String,
    onCancel: ((String) -> Unit)?,
) {
    if (transfers.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.Inbox,
                    contentDescription = null,
                    tint = OnSurfaceTertiary,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(emptyLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(transfers, key = { it.id }) { item ->
            TransferItemCard(item = item, onCancel = onCancel)
        }
    }
}

@Composable
private fun TransferItemCard(
    item: TransferItemEntity,
    onCancel: ((String) -> Unit)?,
) {
    val progress = if (item.fileSizeBytes > 0)
        (item.bytesTransferred.toFloat() / item.fileSizeBytes.toFloat()).coerceIn(0f, 1f)
    else 0f

    // Received files that finished can be opened; the temp file of an unfinished one can't
    val context = LocalContext.current
    val canOpen = item.direction == TransferDirection.RECEIVING &&
        item.status == TransferStatus.COMPLETED && item.localPath.isNotEmpty()

    val statusColor = when (item.status) {
        TransferStatus.COMPLETED -> StatusConnected
        TransferStatus.FAILED    -> StatusDisconnected
        TransferStatus.PAUSED    -> StatusConnecting
        else                     -> CrossSyncCyan
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (canOpen) Modifier.clip(RoundedCornerShape(16.dp)).clickable { FileOpener.open(context, item.localPath, item.fileName) }
                else Modifier
            ),
    ) {
        // File name + direction icon
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (item.direction == TransferDirection.RECEIVING)
                    Icons.Rounded.FileDownload else Icons.Rounded.FileUpload,
                contentDescription = null,
                tint = CrossSyncCyan,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = item.fileSizeBytes.toReadableSize() + if (canOpen) "  ·  Tap to open" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatusBadge(
                label = item.status.name.lowercase()
                    .replaceFirstChar { it.uppercase() },
                color = statusColor,
            )
        }

        // Progress bar (only for active)
        if (item.status == TransferStatus.ACTIVE || item.status == TransferStatus.PAUSED) {
            Spacer(Modifier.height(10.dp))
            GradientProgressBar(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${(progress * 100).toInt()}%  ·  ${item.bytesTransferred.toReadableSize()}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = if (item.speedBps > 0) item.speedBps.toReadableSpeed() else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = CrossSyncCyan,
                )
            }

            // Cancel button
            onCancel?.let {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { it(item.id) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Icon(
                        Icons.Rounded.Cancel,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(16.dp),
                        tint = StatusDisconnected,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusDisconnected,
                    )
                }
            }
        }

        // Error message
        if (item.status == TransferStatus.FAILED && item.errorMessage != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = StatusDisconnected,
            )
        }
    }
}
