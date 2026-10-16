package com.crosssync.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.crosssync.app.data.repository.SettingsRepository
import com.crosssync.app.service.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel backing [DashboardScreen] and acting as the global service orchestrator.
 *
 * Exposes:
 * - [connectionState] — live connection status.
 * - [connectedPeerIp] / [connectedPeerName] — the connected Windows PC.
 * - [pairingFingerprint] / [lastError] — what the pairing prompt shows.
 * - [discoveredDevices] — PCs found via UDP scan.
 * - [isDiscovering] — true while [DeviceDiscoveryWorker] is running.
 *
 * Mirrors [MainViewModel.cs] on Windows.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val settingsRepository: SettingsRepository,
) : ViewModel() {

    val connectionState: StateFlow<TcpConnectionService.ConnectionState> =
        TcpConnectionService.connectionState

    val connectedPeerIp: StateFlow<String?> = TcpConnectionService.connectedPeerIp
    val connectedPeerName: StateFlow<String?> = TcpConnectionService.connectedPeerName
    val pairingFingerprint: StateFlow<String?> = TcpConnectionService.pairingFingerprint
    val lastError: StateFlow<String?> = TcpConnectionService.lastError
    val isServiceRunning: StateFlow<Boolean> = TcpConnectionService.isRunning
    val isAccessibilityActive: StateFlow<Boolean> = ClipboardAccessibilityService.running
    val isClipboardReadBlocked: StateFlow<Boolean> = com.crosssync.app.ui.TransparentClipboardActivity.launchBlocked

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var discoveryJob: Job? = null

    // ---- Service control ----

    fun startSync() {
        settingsRepository.setServiceEnabled(true)
        TcpConnectionService.start(context)
    }

    /** Stops sync and remembers that, so opening the app or rebooting doesn't restart it. */
    fun stopSync() {
        settingsRepository.setServiceEnabled(false)
        TcpConnectionService.stop(context)
    }

    // ---- Pairing ----

    /** Uses [code] (shown on the PC) for the next handshake and reconnects. */
    fun submitPairingCode(code: String) {
        settingsRepository.submitPairingCode(code)
        settingsRepository.setServiceEnabled(true)
        TcpConnectionService.start(context, TcpConnectionService.ACTION_RECONNECT)
    }

    fun forgetPairedPcs() {
        settingsRepository.forgetAllPairings()
        if (TcpConnectionService.isRunning.value) {
            TcpConnectionService.start(context, TcpConnectionService.ACTION_RECONNECT)
        }
    }

    // ---- Device discovery ----

    /** Runs [DeviceDiscoveryWorker] and shows the results of this scan only. */
    fun discoverDevices() {
        discoveryJob?.cancel()
        _isDiscovering.value = true
        _discoveredDevices.value = emptyList()

        val requestId = DeviceDiscoveryWorker.enqueue(context)

        discoveryJob = viewModelScope.launch {
            // Observe this request (not the unique-work name, which would replay the previous
            // scan's result) and stop once it finishes, so collectors don't pile up.
            val info = WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(requestId)
                .filterNotNull()
                .first { it.state.isFinished }

            if (info.state == WorkInfo.State.SUCCEEDED) {
                val raw = info.outputData.getString(DeviceDiscoveryWorker.KEY_DISCOVERED)
                _discoveredDevices.value = raw
                    ?.split(",")
                    ?.filter { it.contains("|") }
                    ?.map {
                        val (ip, host) = it.split("|", limit = 2)
                        DiscoveredDevice(ip, host)
                    } ?: emptyList()
            }
            _isDiscovering.value = false
        }
    }

    /** Connects to a discovered device (sets IP in settings and starts sync). */
    fun connectToDevice(device: DiscoveredDevice) {
        settingsRepository.setPeerIp(device.ip)
        startSync()
    }
}
