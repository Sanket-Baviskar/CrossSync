package com.crosssync.app.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.crosssync.app.service.TcpConnectionService
import com.crosssync.app.utils.ClipboardSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Invisible, transient activity used as a focus trampoline to read the system clipboard on Android 10+.
 *
 * Android 10+ restricts [ClipboardManager.getPrimaryClip] to the app that has window focus.
 * This activity opens transparently, reads the clipboard once it has focus, hands the text to
 * [TcpConnectionService], and finishes. A timeout finishes it even if focus never arrives, so an
 * invisible window is never left covering the screen and swallowing touches.
 */
class TransparentClipboardActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishTimeout = Runnable { finishQuietly() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastCreatedAt = SystemClock.elapsedRealtime()
        _launchBlocked.value = false
        disableTransitions()
        handler.postDelayed(finishTimeout, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            readAndSyncClipboard()
            finishQuietly()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishTimeout)
        super.onDestroy()
    }

    private fun finishQuietly() {
        if (isFinishing) return
        finish()
        disableTransitions()
    }

    @Suppress("DEPRECATION")
    private fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    private fun readAndSyncClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            // Read here, while focused: on Android 10+ that's the only time access is allowed
            if (ClipboardSync.sendIfNew(this, cm.primaryClip)) {
                Log.i(TAG, "Captured clipboard with focus")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading clipboard in trampoline: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TransparentClipAct"
        private const val FOCUS_TIMEOUT_MS = 1_000L
        private const val BLOCKED_CHECK_MS = 3_000L

        @Volatile private var lastCreatedAt = 0L
        private val _launchBlocked = MutableStateFlow(false)

        /**
         * True when the phone silently refused to open this activity from the background —
         * Xiaomi/Redmi/POCO ("Display pop-up windows while running in the background"),
         * Vivo/iQOO and some Oppo/Realme builds do that by default — so copies can't be read.
         */
        val launchBlocked: StateFlow<Boolean> = _launchBlocked.asStateFlow()

        fun launch(context: Context) {
            val requestedAt = SystemClock.elapsedRealtime()
            Handler(Looper.getMainLooper()).postDelayed({
                if (lastCreatedAt < requestedAt) {
                    Log.w(TAG, "Trampoline never started: background activity launch blocked by the system")
                    _launchBlocked.value = true
                }
            }, BLOCKED_CHECK_MS)
            try {
                val intent = Intent(context, TransparentClipboardActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
                    )
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed launching transparent trampoline: ${e.message}")
            }
        }
    }
}
