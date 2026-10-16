package com.crosssync.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.crosssync.app.protocol.ProtocolConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for persisted user settings, plus pairing state (this phone's device ID and the
 * token each paired PC issued, keyed by that PC's certificate fingerprint).
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("crosssync_settings", Context.MODE_PRIVATE)

    // ---- Observable settings ----

    private val _peerIp = MutableStateFlow(prefs.getString(KEY_PEER_IP, "") ?: "")
    val peerIp: StateFlow<String> = _peerIp.asStateFlow()

    private val _peerPort = MutableStateFlow(prefs.getInt(KEY_PEER_PORT, ProtocolConstants.DEFAULT_PORT))
    val peerPort: StateFlow<Int> = _peerPort.asStateFlow()

    private val _autoStart = MutableStateFlow(prefs.getBoolean(KEY_AUTO_START, false))
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _clipboardSyncEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CLIPBOARD_SYNC, true)
    )
    val clipboardSyncEnabled: StateFlow<Boolean> = _clipboardSyncEnabled.asStateFlow()

    private val _downloadDirectory = MutableStateFlow(
        prefs.getString(KEY_DOWNLOAD_DIR, defaultDownloadDir()) ?: defaultDownloadDir()
    )
    val downloadDirectory: StateFlow<String> = _downloadDirectory.asStateFlow()

    private val _transferNotificationsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_TRANSFER_NOTIFICATIONS, true)
    )
    val transferNotificationsEnabled: StateFlow<Boolean> =
        _transferNotificationsEnabled.asStateFlow()

    /** Whether the user wants sync running (false after Stop), honored on app open and boot. */
    private val _serviceEnabled = MutableStateFlow(prefs.getBoolean(KEY_SERVICE_ENABLED, true))
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled.asStateFlow()

    private val _hasPairings = MutableStateFlow(pairingKeys().isNotEmpty())
    val hasPairings: StateFlow<Boolean> = _hasPairings.asStateFlow()

    /** Pairing code the user just typed; used for the next handshake only, never persisted. */
    private val _pendingPairingCode = MutableStateFlow<String?>(null)
    val pendingPairingCode: StateFlow<String?> = _pendingPairingCode.asStateFlow()

    /** Stable random ID the PC uses to recognize this phone. */
    val deviceId: String
        @Synchronized get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    // ---- Setters ----

    fun setPeerIp(ip: String) {
        _peerIp.value = ip
        prefs.edit { putString(KEY_PEER_IP, ip) }
    }

    fun setPeerPort(port: Int) {
        _peerPort.value = port
        prefs.edit { putInt(KEY_PEER_PORT, port) }
    }

    fun setAutoStart(enabled: Boolean) {
        _autoStart.value = enabled
        prefs.edit { putBoolean(KEY_AUTO_START, enabled) }
    }

    fun setClipboardSyncEnabled(enabled: Boolean) {
        _clipboardSyncEnabled.value = enabled
        prefs.edit { putBoolean(KEY_CLIPBOARD_SYNC, enabled) }
    }

    fun setDownloadDirectory(path: String) {
        _downloadDirectory.value = path
        prefs.edit { putString(KEY_DOWNLOAD_DIR, path) }
    }

    fun setTransferNotificationsEnabled(enabled: Boolean) {
        _transferNotificationsEnabled.value = enabled
        prefs.edit { putBoolean(KEY_TRANSFER_NOTIFICATIONS, enabled) }
    }

    fun setServiceEnabled(enabled: Boolean) {
        _serviceEnabled.value = enabled
        prefs.edit { putBoolean(KEY_SERVICE_ENABLED, enabled) }
    }

    // ---- Pairing ----

    fun tokenFor(fingerprint: String): String? = prefs.getString(KEY_TOKEN_PREFIX + fingerprint, null)

    fun saveToken(fingerprint: String, token: String) {
        prefs.edit { putString(KEY_TOKEN_PREFIX + fingerprint, token) }
        _hasPairings.value = true
    }

    fun removeToken(fingerprint: String) {
        prefs.edit { remove(KEY_TOKEN_PREFIX + fingerprint) }
        _hasPairings.value = pairingKeys().isNotEmpty()
    }

    fun forgetAllPairings() {
        prefs.edit { pairingKeys().forEach { remove(it) } }
        _hasPairings.value = false
    }

    fun submitPairingCode(code: String) {
        _pendingPairingCode.value = code.trim()
    }

    fun clearPendingPairingCode() {
        _pendingPairingCode.value = null
    }

    private fun pairingKeys(): List<String> = prefs.all.keys.filter { it.startsWith(KEY_TOKEN_PREFIX) }

    /** Restores user settings. Pairings and the device ID are kept, so the PC still recognizes this phone. */
    fun resetToDefaults() {
        prefs.edit {
            listOf(
                KEY_PEER_IP, KEY_PEER_PORT, KEY_AUTO_START, KEY_CLIPBOARD_SYNC,
                KEY_DOWNLOAD_DIR, KEY_TRANSFER_NOTIFICATIONS, KEY_SERVICE_ENABLED,
            ).forEach { remove(it) }
        }
        _peerIp.value = ""
        _peerPort.value = ProtocolConstants.DEFAULT_PORT
        _autoStart.value = false
        _clipboardSyncEnabled.value = true
        _downloadDirectory.value = defaultDownloadDir()
        _transferNotificationsEnabled.value = true
        _serviceEnabled.value = true
    }

    private fun defaultDownloadDir(): String =
        (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath + "/CrossSync/Downloads"

    companion object {
        private const val KEY_PEER_IP = "peer_ip"
        private const val KEY_PEER_PORT = "peer_port"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_CLIPBOARD_SYNC = "clipboard_sync"
        private const val KEY_DOWNLOAD_DIR = "download_dir"
        private const val KEY_TRANSFER_NOTIFICATIONS = "transfer_notifications"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN_PREFIX = "pc_token_"
    }
}
