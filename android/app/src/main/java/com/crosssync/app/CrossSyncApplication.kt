package com.crosssync.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for CrossSync.
 * Initializes Hilt DI and creates notification channels at startup.
 */
@HiltAndroidApp
class CrossSyncApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Persistent service channel (low importance — no sound)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing CrossSync foreground service notification"
                setShowBadge(false)
            }

            // Transfer events channel (default importance — can make sound)
            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFERS,
                getString(R.string.notification_channel_transfers),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "File transfer start, completion, and error notifications"
                setShowBadge(true)
            }

            manager.createNotificationChannels(listOf(serviceChannel, transferChannel))
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "crosssync_service"
        const val CHANNEL_TRANSFERS = "crosssync_transfers"
    }
}
