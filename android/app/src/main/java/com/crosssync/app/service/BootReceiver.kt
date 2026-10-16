package com.crosssync.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.crosssync.app.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Starts sync after boot (if "Start on Boot" is on) and after an app update (if sync was
 * running), unless the user stopped sync.
 *
 * The service uses the connectedDevice foreground type, which Android 15 still allows to
 * start from BOOT_COMPLETED (dataSync is no longer allowed there).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val shouldStart = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> settingsRepository.autoStart.value
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            else -> false
        }
        if (shouldStart && settingsRepository.serviceEnabled.value) {
            TcpConnectionService.start(context)
        }
    }
}
