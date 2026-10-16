package com.crosssync.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.crosssync.app.data.repository.SettingsRepository
import com.crosssync.app.utils.ClipboardSync
import com.crosssync.app.service.TcpConnectionService
import com.crosssync.app.ui.navigation.CrossSyncNavHost
import com.crosssync.app.ui.theme.CrossSyncTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity container for all Jetpack Compose UI.
 * Navigation between Dashboard, Transfers, Clipboard, and Settings
 * is handled by [CrossSyncNavHost].
 *
 * While focused it can read the clipboard directly (Android 10+ only allows the focused
 * app), so it syncs whatever was copied elsewhere when the user returns to CrossSync.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private var clipboardManager: ClipboardManager? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        syncClipboardIfChanged()
    }

    // Android 13+: without this the service notification and transfer alerts are hidden
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        requestNotificationPermissionIfNeeded()

        // Resume background sync unless the user stopped it
        if (settingsRepository.serviceEnabled.value) {
            TcpConnectionService.start(this)
        }

        enableEdgeToEdge()
        setContent {
            CrossSyncTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CrossSyncNavHost()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onPause() {
        super.onPause()
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            syncClipboardIfChanged()
        }
    }

    private fun syncClipboardIfChanged() {
        if (!settingsRepository.clipboardSyncEnabled.value) return
        val cm = clipboardManager ?: return
        ClipboardSync.sendIfNew(this, runCatching { cm.primaryClip }.getOrNull())
    }
}
