package com.crosssync.app.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.crosssync.app.data.repository.SettingsRepository
import com.crosssync.app.service.TcpConnectionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile allowing the user to start/stop CrossSync from the Android
 * notification shade without opening the app.
 *
 * Analogous to the system tray icon on Windows.
 *
 * Tap toggles the service (not the connection): while it is running, even if still connecting,
 * a tap stops it. The tile updates live while the shade is open.
 */
@AndroidEntryPoint
class SyncControlTileService : TileService() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val newScope = CoroutineScope(Dispatchers.Main + Job())
        scope = newScope
        newScope.launch {
            combine(TcpConnectionService.isRunning, TcpConnectionService.connectionState) { running, state ->
                running to state
            }.collect { (running, state) -> updateTileState(running, state) }
        }
    }

    override fun onStopListening() {
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (TcpConnectionService.isRunning.value) {
            settingsRepository.setServiceEnabled(false)
            TcpConnectionService.stop(this)
        } else {
            settingsRepository.setServiceEnabled(true)
            TcpConnectionService.start(this)
        }
    }

    private fun updateTileState(running: Boolean, state: TcpConnectionService.ConnectionState) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (!running) "Tap to start" else when (state) {
                TcpConnectionService.ConnectionState.CONNECTED -> "Connected"
                TcpConnectionService.ConnectionState.CONNECTING -> "Connecting…"
                TcpConnectionService.ConnectionState.PAIRING_REQUIRED -> "Pairing needed"
                TcpConnectionService.ConnectionState.DISCONNECTED -> "No PC set"
            }
        }
        tile.updateTile()
    }
}
