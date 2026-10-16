package com.crosssync.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import com.crosssync.app.protocol.TlsHelper
import com.crosssync.app.service.DiscoveredDevice
import com.crosssync.app.service.TcpConnectionService
import com.crosssync.app.ui.components.*
import com.crosssync.app.ui.theme.*
import com.crosssync.app.ui.viewmodel.MainViewModel

/**
 * Dashboard screen — the landing page.
 *
 * Shows:
 * - Live connection status hero card with animated orb.
 * - Start/Stop sync button.
 * - Discovered devices list with one-tap connect.
 * - Quick stats (active transfers, clipboard items).
 *
 * Mirrors [DashboardPage.xaml] on Windows.
 */
@Composable
fun DashboardScreen(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectedIp by viewModel.connectedPeerIp.collectAsStateWithLifecycle()
    val connectedName by viewModel.connectedPeerName.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val pairingFingerprint by viewModel.pairingFingerprint.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val isA11yActive by viewModel.isAccessibilityActive.collectAsStateWithLifecycle()
    val isReadBlocked by viewModel.isClipboardReadBlocked.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()

    // Ask for the pairing code whenever a PC needs it; "Not now" hides the dialog until the next prompt
    var pairingDismissed by remember(pairingFingerprint, lastError) { mutableStateOf(false) }
    if (isServiceRunning && connectionState == TcpConnectionService.ConnectionState.PAIRING_REQUIRED && !pairingDismissed) {
        PairingDialog(
            fingerprint = pairingFingerprint,
            error = lastError,
            onSubmit = viewModel::submitPairingCode,
            onDismiss = { pairingDismissed = true },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDeep),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        // ---- Hero connection card ----
        item {
            ConnectionHeroCard(
                state = connectionState,
                isRunning = isServiceRunning,
                connectedIp = connectedIp,
                connectedName = connectedName,
                error = lastError,
                onStartSync = viewModel::startSync,
                onStopSync = viewModel::stopSync,
                onPair = { pairingDismissed = false },
            )
        }

        // ---- 24/7 Background Sync Status Card ----
        item {
            val context = androidx.compose.ui.platform.LocalContext.current

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isA11yActive) Icons.Rounded.Bolt else Icons.Rounded.Info,
                            contentDescription = null,
                            tint = if (isA11yActive) StatusConnected else StatusConnecting,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isA11yActive) "24/7 Background Sync Active" else "Background Sync Needs Permission",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isA11yActive) StatusConnected else StatusConnecting,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isA11yActive) "Copies in any app are sent to PC automatically" else "Enable CrossSync in Accessibility Settings for automatic sync",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceSecondary
                            )
                        }
                    }
                    if (!isA11yActive) {
                        Button(
                            onClick = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CrossSyncCyan, contentColor = BackgroundDeep),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Enable", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // ---- The phone blocks the background clipboard read (Xiaomi, Vivo, some Oppo/Realme) ----
        if (isA11yActive && isReadBlocked) {
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            tint = StatusDisconnected,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Your phone blocked a copy",
                                style = MaterialTheme.typography.titleSmall,
                                color = StatusDisconnected,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "In CrossSync's app settings, allow \"Display pop-up windows while running in the background\" " +
                                    "(Xiaomi/Redmi/POCO) or \"Pop-up windows\" / \"Open new windows\" (Vivo, Oppo, Realme).",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { com.crosssync.app.utils.OemSettings.openPopupPermission(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = CrossSyncCyan, contentColor = BackgroundDeep),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("Open settings", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }

        // ---- Discover section ----
        item {
            SectionHeader(
                title = "NETWORK DEVICES",
                trailing = {
                    TextButton(onClick = viewModel::discoverDevices) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = CrossSyncCyan,
                            )
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = CrossSyncCyan,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            if (isDiscovering) "Scanning…" else "Scan",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrossSyncCyan,
                        )
                    }
                }
            )
        }

        if (discoveredDevices.isEmpty() && !isDiscovering) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.WifiFind,
                            contentDescription = null,
                            tint = OnSurfaceTertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "No devices found — tap Scan",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceTertiary,
                        )
                    }
                }
            }
        }

        items(discoveredDevices) { device ->
            DiscoveredDeviceRow(
                device = device,
                onClick = { viewModel.connectToDevice(device) },
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---- Sub-composables ----

@Composable
private fun ConnectionHeroCard(
    state: TcpConnectionService.ConnectionState,
    isRunning: Boolean,
    connectedIp: String?,
    connectedName: String?,
    error: String?,
    onStartSync: () -> Unit,
    onStopSync: () -> Unit,
    onPair: () -> Unit,
) {
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(CrossSyncCyanContainer, SurfaceDark),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(gradientBrush)
            .padding(20.dp),
    ) {
        Column {
            // Title row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "CrossSync",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnSurfacePrimary,
                    modifier = Modifier.weight(1f),
                )
                ConnectionStatusOrb(state = state)
            }

            Spacer(Modifier.height(4.dp))

            // Status text
            val statusLabel = when {
                !isRunning -> "Stopped"
                state == TcpConnectionService.ConnectionState.CONNECTED -> "Connected · Encrypted"
                state == TcpConnectionService.ConnectionState.CONNECTING -> "Connecting…"
                state == TcpConnectionService.ConnectionState.PAIRING_REQUIRED -> "Pairing needed"
                else -> "No PC set — add its IP in Settings or Scan"
            }
            val statusColor = when {
                !isRunning -> StatusDisconnected
                state == TcpConnectionService.ConnectionState.CONNECTED -> StatusConnected
                state == TcpConnectionService.ConnectionState.DISCONNECTED -> StatusDisconnected
                else -> StatusConnecting
            }
            StatusBadge(label = statusLabel, color = statusColor)

            if (connectedIp != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (connectedName != null) "PC: $connectedName ($connectedIp)" else "PC: $connectedIp",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceSecondary,
                )
            }

            if (isRunning && error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusDisconnected,
                )
            }

            if (isRunning && state == TcpConnectionService.ConnectionState.PAIRING_REQUIRED) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Enter Pairing Code")
                }
            }

            Spacer(Modifier.height(20.dp))

            // Start / Stop button
            val isConnected = isRunning && state == TcpConnectionService.ConnectionState.CONNECTED

            Button(
                onClick = if (isRunning) onStopSync else onStartSync,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) StatusConnected.copy(alpha = 0.2f)
                    else CrossSyncCyan,
                    contentColor = if (isConnected) StatusConnected else BackgroundDeep,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = if (isRunning)
                        Icons.Rounded.StopCircle else Icons.Rounded.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "Stop Sync" else "Start Sync",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PairingDialog(
    fingerprint: String?,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with PC") },
        text = {
            Column {
                Text(
                    "Enter the 6-digit code shown in CrossSync on your PC (Settings → Pairing, or the bottom bar).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (fingerprint != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Check this matches the PC fingerprint shown there:",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceSecondary,
                    )
                    Text(
                        TlsHelper.formatFingerprint(fingerprint),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = OnSurfacePrimary,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = StatusDisconnected)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { input -> code = input.filter { it.isDigit() }.take(6) },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = code.length == 6) { Text("Pair") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
        containerColor = SurfaceVariant,
    )
}

@Composable
private fun DiscoveredDeviceRow(
    device: DiscoveredDevice,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Computer,
                contentDescription = null,
                tint = CrossSyncCyan,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.hostname.ifBlank { "Unknown PC" },
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurfacePrimary,
                )
                Text(
                    text = device.ip,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceSecondary,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "Connect",
                tint = OnSurfaceTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
