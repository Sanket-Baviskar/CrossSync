package com.crosssync.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.crosssync.app.data.repository.SettingsRepository
import com.crosssync.app.utils.ClipboardSync
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AccessibilityService that enables background clipboard synchronization on Android 10+.
 *
 * Android 10 (API 29+) prevents background apps from reading [ClipboardManager.getPrimaryClip]
 * unless they have input focus. This service notices "Copy" taps; if the clipboard still can't be
 * read, it briefly opens [com.crosssync.app.ui.TransparentClipboardActivity] to gain focus
 * (accessibility services are allowed to start activities from the background).
 */
@AndroidEntryPoint
class ClipboardAccessibilityService : AccessibilityService() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var clipboardManager: ClipboardManager? = null
    private var lastTriggerAt = 0L

    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkAndSyncClipboard("OnPrimaryClipChangedListener")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _running.value = true
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(primaryClipListener)
        Log.i(TAG, "ClipboardAccessibilityService connected")
    }

    /**
     * Detects that something was copied. Many apps never report the click itself (Jetpack Compose
     * apps such as ChatGPT and Claude send no TYPE_VIEW_CLICKED for touch, and the text-selection
     * toolbar's Copy doesn't either), so several signals are used:
     * - a click on a view labelled "Copy" (classic View apps: WhatsApp, Gemini);
     * - a toast saying "Copied" — Samsung's system shows "Copied to clipboard." for every copy
     *   in every app, and many apps toast their own ("Message copied");
     * - the system clipboard pop-up that stock Android 13+ shows after any copy.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val source = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED ->
                if (isCopyClick(event)) "CopyClick" else null

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                if (mentionsCopied(event)) "CopiedToast" else null

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (isSystemClipboardOverlay(event)) "ClipboardOverlay" else null

            else -> null
        } ?: return

        // One copy can produce several signals (e.g. WhatsApp: click + toast); handle it once
        val now = SystemClock.elapsedRealtime()
        // Our own write of a PC clip makes Samsung toast "Copied": don't steal focus for it
        if (now - ownWriteAt < OWN_WRITE_GRACE_MS) return
        if (now - lastTriggerAt < TRIGGER_DEBOUNCE_MS) return
        lastTriggerAt = now

        serviceScope.launch {
            delay(150) // let the app finish writing the clipboard
            checkAndSyncClipboard(source)
        }
    }

    private fun isCopyClick(event: AccessibilityEvent): Boolean {
        val text = event.text.joinToString(" ")
        val desc = event.contentDescription?.toString() ?: ""
        val id = runCatching { event.source?.viewIdResourceName }.getOrNull() ?: ""
        return copyWords.any { text.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true) } ||
            id.contains("copy", ignoreCase = true)
    }

    private fun mentionsCopied(event: AccessibilityEvent): Boolean =
        event.text.any { t -> t != null && copiedPhrases.any { t.contains(it, ignoreCase = true) } }

    /** "Copy" in English and in the phone's language (the system's own label). */
    private val copyWords: List<String> by lazy {
        (listOf("copy") + listOfNotNull(systemString("copy"))).distinct()
    }

    /** "copied" plus the system's localized "Text copied to clipboard" wording. */
    private val copiedPhrases: List<String> by lazy {
        (listOf("copied") + listOfNotNull(systemString("text_copied"), systemString("copied")))
            .map { it.trim().trimEnd('.', '!', '。').lowercase() }
            .filter { it.length >= 3 }
            .distinct()
    }

    private fun systemString(name: String): String? = runCatching {
        val res = android.content.res.Resources.getSystem()
        res.getIdentifier(name, "string", "android").takeIf { it != 0 }?.let(res::getString)
    }.getOrNull()

    private fun isSystemClipboardOverlay(event: AccessibilityEvent): Boolean {
        if (event.packageName != "com.android.systemui") return false
        val className = event.className?.toString() ?: ""
        if (className.contains("clipboard", ignoreCase = true) || mentionsCopied(event)) return true
        // Stock Android 13+ (Pixel, Motorola, ...): the overlay is a plain FrameLayout with no
        // text, recognisable only by its view ID (needs flagReportViewIds)
        return runCatching {
            val root = event.source ?: return false
            root.findAccessibilityNodeInfosByViewId(CLIPBOARD_OVERLAY_ID).isNotEmpty() ||
                hasClipboardId(root, depth = 0) // OEM builds that renamed the AOSP view
        }.getOrDefault(false)
    }

    private fun hasClipboardId(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int): Boolean {
        if (node.viewIdResourceName?.contains("clipboard", ignoreCase = true) == true) return true
        if (depth >= 3) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasClipboardId(child, depth + 1)) return true
        }
        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "ClipboardAccessibilityService interrupted")
    }

    override fun onDestroy() {
        _running.value = false
        clipboardManager?.removePrimaryClipChangedListener(primaryClipListener)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun checkAndSyncClipboard(source: String) {
        if (!settingsRepository.clipboardSyncEnabled.value) return
        // "Stop Sync" means stop: sending a clip would start the service and reconnect
        if (!settingsRepository.serviceEnabled.value) return

        serviceScope.launch {
            try {
                val cm = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                if (ClipboardSync.sendIfNew(this@ClipboardAccessibilityService, cm?.primaryClip)) {
                    // Clipboard was readable, so the focus trampoline isn't needed
                    Log.d(TAG, "Captured clip ($source)")
                    return@launch
                }
                // Clip is null when Android 10+ blocks background reads: grab focus briefly
                com.crosssync.app.ui.TransparentClipboardActivity.launch(this@ClipboardAccessibilityService)
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading clipboard in accessibility service: ${e.message}")
                com.crosssync.app.ui.TransparentClipboardActivity.launch(this@ClipboardAccessibilityService)
            }
        }
    }

    companion object {
        private const val TAG = "ClipboardA11yService"
        private const val TRIGGER_DEBOUNCE_MS = 1_000L
        private const val CLIPBOARD_OVERLAY_ID = "com.android.systemui:id/clipboard_ui"
        private const val OWN_WRITE_GRACE_MS = 1_500L

        @Volatile private var ownWriteAt = -OWN_WRITE_GRACE_MS

        /** Call right before CrossSync itself puts something on the clipboard. */
        fun markOwnClipboardWrite() {
            ownWriteAt = SystemClock.elapsedRealtime()
        }

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        @Volatile
        var lastSyncedText: String? = null

        /**
         * Atomically records [text] as the last synced clip. Returns false if it was already
         * synced (an echo of text received from the PC, or a copy another listener already sent),
         * so this service, [TcpConnectionService], the app and the trampoline never double-send.
         */
        @Synchronized
        fun claimIfNew(text: String): Boolean {
            if (text == lastSyncedText) return false
            lastSyncedText = text
            return true
        }
    }
}
