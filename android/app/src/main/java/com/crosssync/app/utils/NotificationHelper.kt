package com.crosssync.app.utils

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.crosssync.app.CrossSyncApplication
import com.crosssync.app.MainActivity
import com.crosssync.app.R
import com.crosssync.app.service.TcpConnectionService

/**
 * Builds foreground service and transfer event notifications.
 *
 * All notifications use the pre-created channels from [CrossSyncApplication].
 */
object NotificationHelper {

    private const val NOTIFICATION_ID_SERVICE = 1001
    private const val NOTIFICATION_ID_TRANSFER_BASE = 2000

    // ---- Foreground Service Notification ----

    /**
     * Builds the persistent notification for the foreground sync service.
     * Uses low-importance [CrossSyncApplication.CHANNEL_SERVICE].
     */
    fun buildServiceNotification(
        context: Context,
        statusText: String = context.getString(R.string.notification_service_running),
    ): Notification {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Explicit service intent: the old implicit broadcast had no receiver, so Stop did nothing
        val stopIntent = Intent(context, TcpConnectionService::class.java)
            .setAction(TcpConnectionService.ACTION_STOP)
        val stopPending = PendingIntent.getService(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val syncClipIntent = Intent(context, com.crosssync.app.ui.TransparentClipboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        val syncClipPending = PendingIntent.getActivity(
            context, 1, syncClipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CrossSyncApplication.CHANNEL_SERVICE)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_sync,
                "Send Clipboard to PC",
                syncClipPending,
            )
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.notification_action_stop),
                stopPending,
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun getServiceNotificationId() = NOTIFICATION_ID_SERVICE

    // ---- Transfer Notifications ----

    /**
     * Shows a completion notification for a finished file transfer.
     *
     * @param transferId  Unique ID for this transfer (used to update existing notification).
     * @param fileName    Name of the transferred file.
     * @param success     Whether the transfer succeeded.
     * @param openLocation Where a received file was saved; tapping the notification opens it.
     */
    fun showTransferComplete(
        context: Context,
        transferId: Int,
        fileName: String,
        success: Boolean,
        openLocation: String? = null,
    ) {
        val message = if (success) {
            context.getString(R.string.notification_transfer_complete, fileName)
        } else {
            context.getString(R.string.notification_transfer_failed, fileName)
        }

        val notification = NotificationCompat.Builder(context, CrossSyncApplication.CHANNEL_TRANSFERS)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(if (success) R.drawable.ic_check else R.drawable.ic_error)
            .setAutoCancel(true)
            .apply {
                val view = openLocation?.let { FileOpener.viewIntent(context, it, fileName) }
                if (view != null) {
                    setContentText("$message — tap to open")
                    setContentIntent(
                        PendingIntent.getActivity(
                            context, transferId, view,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    )
                }
            }
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_TRANSFER_BASE + transferId, notification)
    }

    /**
     * Builds an in-progress transfer notification with a progress bar.
     */
    fun buildTransferProgressNotification(
        context: Context,
        fileName: String,
        progressPercent: Int,
        direction: String, // "Sending" or "Receiving"
    ): Notification {
        return NotificationCompat.Builder(context, CrossSyncApplication.CHANNEL_TRANSFERS)
            .setContentTitle("$direction: $fileName")
            .setContentText("$progressPercent%")
            .setSmallIcon(R.drawable.ic_sync)
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
