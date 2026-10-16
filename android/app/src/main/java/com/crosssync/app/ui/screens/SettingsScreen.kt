package com.crosssync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crosssync.app.BuildConfig
import com.crosssync.app.data.repository.SettingsRepository
import com.crosssync.app.protocol.ProtocolConstants
import com.crosssync.app.ui.components.GlassCard
import com.crosssync.app.ui.components.SectionHeader
import com.crosssync.app.ui.theme.*
import com.crosssync.app.ui.viewmodel.MainViewModel
import com.crosssync.app.utils.DownloadPublisher

/**
 * Settings screen — mirrors [SettingsPage.xaml] on Windows.
 *
 * Sections:
 * - **Connection** — peer IP, port
 * - **Clipboard** — sync toggle
 * - **Storage** — download directory
 * - **Notifications** — transfer alerts
 * - **General** — auto-start on boot, reset
 * - **About** — app version
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val settings = viewModel.settingsRepository
    val peerIp by settings.peerIp.collectAsStateWithLifecycle()
    val peerPort by settings.peerPort.collectAsStateWithLifecycle()
    val autoStart by settings.autoStart.collectAsStateWithLifecycle()
    val clipboardSync by settings.clipboardSyncEnabled.collectAsStateWithLifecycle()
    val downloadDir by settings.downloadDirectory.collectAsStateWithLifecycle()
    val transferNotifications by settings.transferNotificationsEnabled.collectAsStateWithLifecycle()
    val hasPairings by settings.hasPairings.collectAsStateWithLifecycle()

    var showResetDialog by remember { mutableStateOf(false) }
    var showForgetDialog by remember { mutableStateOf(false) }
    var ipDraft by remember(peerIp) { mutableStateOf(peerIp) }
    var portDraft by remember(peerPort) { mutableStateOf(peerPort.toString()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDeep),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        // ---- Connection ----
        item {
            SettingsSection(title = "CONNECTION", icon = Icons.Rounded.Wifi) {
                OutlinedTextField(
                    value = ipDraft,
                    onValueChange = { ipDraft = it },
                    label = { Text("Peer IP Address") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedTextFieldColors(),
                    trailingIcon = {
                        if (ipDraft != peerIp) {
                            IconButton(onClick = { settings.setPeerIp(ipDraft.trim()) }) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "Save IP",
                                    tint = StatusConnected,
                                )
                            }
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = portDraft,
                    onValueChange = { portDraft = it },
                    label = { Text("Port") },
                    placeholder = { Text(ProtocolConstants.DEFAULT_PORT.toString()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedTextFieldColors(),
                    isError = portDraft.toIntOrNull()?.let { it !in 1..65535 } ?: portDraft.isNotEmpty(),
                    supportingText = {
                        if (portDraft.toIntOrNull()?.let { it in 1..65535 } != true) Text("Port must be 1–65535")
                    },
                    trailingIcon = {
                        val portInt = portDraft.toIntOrNull()
                        if (portInt != null && portInt in 1..65535 && portInt != peerPort) {
                            IconButton(onClick = { settings.setPeerPort(portInt) }) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "Save port",
                                    tint = StatusConnected,
                                )
                            }
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Connections are encrypted. A new PC asks for the pairing code shown in CrossSync on that PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceSecondary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showForgetDialog = true },
                    enabled = hasPairings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.LinkOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Forget Paired PCs")
                }
            }
        }

        // ---- Clipboard ----
        item {
            SettingsSection(title = "CLIPBOARD", icon = Icons.Rounded.ContentPaste) {
                ToggleRow(
                    label = "Clipboard Sync",
                    description = "Send what you copy to the PC, and put text copied on the PC onto this phone's clipboard",
                    checked = clipboardSync,
                    onCheckedChange = settings::setClipboardSyncEnabled,
                )
            }
        }

        // ---- 24/7 Background & Accessibility ----
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            SettingsSection(title = "24/7 BACKGROUND SYNC", icon = Icons.Rounded.BatteryChargingFull) {
                Text(
                    text = "Automatic Clipboard Detection (Android 10+)",
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurfacePrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Android blocks background apps from reading clipboard. Enable Accessibility permission to sync copied text 24/7 without opening CrossSync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CrossSyncCyan, contentColor = BackgroundDeep),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Rounded.Accessibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Enable in Accessibility Settings")
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "Unrestricted Battery Usage",
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurfacePrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Prevent Android from killing CrossSync background services during idle periods.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceSecondary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Rounded.BatterySaver, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disable Battery Optimization")
                }
            }
        }

        // ---- Storage ----
        item {
            SettingsSection(title = "STORAGE", icon = Icons.Rounded.Folder) {
                Text(
                    text = "Files from your PC are saved to",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurfaceSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = DownloadPublisher.locationDescription(downloadDir),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceTertiary,
                )
            }
        }

        // ---- Notifications ----
        item {
            SettingsSection(title = "NOTIFICATIONS", icon = Icons.Rounded.Notifications) {
                ToggleRow(
                    label = "Transfer Notifications",
                    description = "Show notifications for file transfer events",
                    checked = transferNotifications,
                    onCheckedChange = settings::setTransferNotificationsEnabled,
                )
            }
        }

        // ---- General ----
        item {
            SettingsSection(title = "GENERAL", icon = Icons.Rounded.Settings) {
                ToggleRow(
                    label = "Start on Boot",
                    description = "Automatically start sync when device boots",
                    checked = autoStart,
                    onCheckedChange = settings::setAutoStart,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusDisconnected),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusDisconnected.copy(alpha = 0.4f)),
                ) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset to Defaults")
                }
            }
        }

        // ---- About ----
        item {
            SettingsSection(title = "ABOUT", icon = Icons.Rounded.Info) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("CrossSync", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceTertiary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android ↔ Windows clipboard & file sync",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    // ---- Forget pairings dialog ----
    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text("Forget Paired PCs") },
            text = { Text("This phone will need a PC's pairing code to connect to it again.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetPairedPcs()
                    showForgetDialog = false
                }) {
                    Text("Forget", color = StatusDisconnected)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) { Text("Cancel") }
            },
            containerColor = SurfaceVariant,
        )
    }

    // ---- Reset dialog ----
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Settings") },
            text = { Text("Restore all settings to their defaults? Paired PCs are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    settings.resetToDefaults()
                    ipDraft = ""
                    portDraft = ProtocolConstants.DEFAULT_PORT.toString()
                    showResetDialog = false
                }) {
                    Text("Reset", color = StatusDisconnected)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
            containerColor = SurfaceVariant,
        )
    }
}

// ---- Helpers ----

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = title,
            trailing = {
                Icon(icon, null, Modifier.size(16.dp), tint = OnSurfaceTertiary)
            },
        )
        GlassCard(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BackgroundDeep,
                checkedTrackColor = CrossSyncCyan,
                uncheckedThumbColor = OnSurfaceTertiary,
                uncheckedTrackColor = SurfaceHighlight,
            ),
        )
    }
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CrossSyncCyan,
    unfocusedBorderColor = OnSurfaceTertiary,
    focusedLabelColor = CrossSyncCyan,
    cursorColor = CrossSyncCyan,
    focusedTextColor = OnSurfacePrimary,
    unfocusedTextColor = OnSurfacePrimary,
)
