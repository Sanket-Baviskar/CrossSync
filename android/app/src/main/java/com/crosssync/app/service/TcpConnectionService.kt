package com.crosssync.app.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.crosssync.app.data.local.entity.TransferDirection
import com.crosssync.app.data.repository.ClipboardRepository
import com.crosssync.app.data.repository.SettingsRepository
import com.crosssync.app.data.repository.TransferRepository
import com.crosssync.app.protocol.*
import com.crosssync.app.data.local.entity.ClipboardSource
import com.crosssync.app.utils.ClipboardSync
import com.crosssync.app.utils.DownloadPublisher
import com.crosssync.app.utils.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Core foreground service managing the TLS connection to the Windows PC.
 *
 * Mirrors [NetworkServerService.cs] on Windows.
 *
 * Responsibilities:
 * - One connection loop: connect → TLS → authenticated handshake → read until the connection
 *   dies → back off → reconnect. Restarted when the peer address changes or the network returns.
 * - Pairing: a token per PC certificate fingerprint; unknown PCs need the code shown on the PC.
 * - Read incoming frames; send clipboard text and files (one frame at a time, so clipboard
 *   and pongs interleave with file chunks).
 * - Watch the local clipboard (works on Android 9 and below, or while the app is focused;
 *   [ClipboardAccessibilityService] covers background copies on Android 10+).
 * - Expose connection state as [StateFlow]s for the UI and tile.
 */
/** Another phone connected to the same PC. */
data class PeerDevice(val id: String, val name: String)

/** Where a sent file goes: this PC and/or other phones (forwarded by the PC). */
data class FileRoute(val toPc: Boolean = true, val toDevices: List<String> = emptyList()) {
    val isPcOnly: Boolean get() = toDevices.isEmpty()
}

@AndroidEntryPoint
class TcpConnectionService : LifecycleService() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var transferRepository: TransferRepository
    @Inject lateinit var clipboardRepository: ClipboardRepository

    // ---- Connection state ----

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, PAIRING_REQUIRED, CONNECTED
    }

    private enum class SessionOutcome { FAILED, ENDED, PAIRING_REQUIRED }

    @Volatile private var activeSocket: Socket? = null
    @Volatile private var output: OutputStream? = null
    private var connectionJob: Job? = null
    @Volatile private var generation = 0
    @Volatile private var inBackoff = false
    private val sendMutex = Mutex()      // one frame on the wire at a time
    private val fileSendMutex = Mutex()  // one outgoing file at a time

    // Outgoing file state
    @Volatile private var outgoingTransferId: String? = null
    @Volatile private var outgoingSha256: String? = null
    @Volatile private var cancelOutgoing = false
    private val cancelledQueuedIds = ConcurrentHashMap.newKeySet<String>()

    // Incoming file state (written only by the reader coroutine)
    private class IncomingTransfer(
        val id: String,
        val fileName: String,
        val totalBytes: Long,
        val sha256: String,
        val tmpFile: File,
        val stream: FileOutputStream,
    ) {
        var receivedBytes = 0L
        var lastProgressAt = SystemClock.elapsedRealtime()
        var bytesAtLastProgress = 0L
    }

    @Volatile private var incoming: IncomingTransfer? = null
    @Volatile private var cancelIncomingId: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var clipboardManager: ClipboardManager? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onLocalClipChanged() }

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        _connectionState.value = ConnectionState.DISCONNECTED

        ServiceCompat.startForeground(
            this,
            NotificationHelper.getServiceNotificationId(),
            NotificationHelper.buildServiceNotification(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )

        // Keeps the CPU awake during file transfers
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrossSync::TransferWakeLock")?.apply {
            setReferenceCounted(false)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            transferRepository.failInterrupted()
            cleanupTempFiles()
        }

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        // Reconnect immediately when a network comes back instead of waiting out the backoff.
        // No INTERNET capability required: a LAN or hotspot without internet is fine.
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lifecycleScope.launch {
                    if (inBackoff) {
                        Log.d(TAG, "Network available, reconnecting now")
                        restartConnectionLoop()
                    }
                }
            }
        }
        runCatching {
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback?.let { connectivityManager?.registerNetworkCallback(request, it) }
        }

        // (Re)connect whenever the PC address or port changes
        lifecycleScope.launch {
            combine(settingsRepository.peerIp, settingsRepository.peerPort) { ip, port -> ip to port }
                .distinctUntilChanged()
                .collect { restartConnectionLoop() }
        }

        // Keep the ongoing notification's text in step with the connection
        lifecycleScope.launch {
            combine(_connectionState, _connectedPeerName) { state, name -> state to name }
                .collect { (state, name) -> updateServiceNotification(state, name) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                settingsRepository.setServiceEnabled(false)
                stopSelf()
            }
            ACTION_RECONNECT -> restartConnectionLoop()
            ACTION_SEND_CLIPBOARD -> {
                val text = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT) ?: return START_STICKY
                queueClipboardSend(text)
            }
            ACTION_SEND_CLIPBOARD_IMAGE -> {
                val path = intent.getStringExtra(EXTRA_FILE_PATH) ?: return START_STICKY
                val mime = intent.getStringExtra(EXTRA_MIME) ?: "image/png"
                lifecycleScope.launch { runSafely("send clipboard image") { sendClipboardImageInternal(File(path), mime) } }
            }
            ACTION_SEND_FILE -> {
                val path = intent.getStringExtra(EXTRA_FILE_PATH) ?: return START_STICKY
                val route = FileRoute(
                    toPc = intent.getBooleanExtra(EXTRA_TO_PC, true),
                    toDevices = intent.getStringArrayExtra(EXTRA_TARGET_IDS)?.toList().orEmpty(),
                )
                lifecycleScope.launch { runSafely("send file") { sendFileInternal(File(path), route) } }
            }
            ACTION_CANCEL_TRANSFER -> {
                val id = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return START_STICKY
                cancelTransfer(id)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        generation++ // stale loops must not touch the shared state below
        connectionJob?.cancel()
        closeSocket()
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        runCatching { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedPeerIp.value = null
        _connectedPeerName.value = null
        _otherDevices.value = emptyList()
        _isRunning.value = false
        super.onDestroy()
    }

    private fun updateServiceNotification(state: ConnectionState, peerName: String?) {
        val text = when (state) {
            ConnectionState.CONNECTED -> "Connected to ${peerName ?: "PC"}"
            ConnectionState.CONNECTING -> "Connecting to PC…"
            ConnectionState.PAIRING_REQUIRED -> "Open CrossSync to pair with your PC"
            ConnectionState.DISCONNECTED -> "Set your PC's address in CrossSync"
        }
        // Android 13+: posting needs POST_NOTIFICATIONS (the service still runs without it)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NotificationHelper.getServiceNotificationId(),
                NotificationHelper.buildServiceNotification(this, text),
            )
        }
    }

    // ---- Housekeeping ----

    private fun cleanupTempFiles() {
        runCatching {
            val dayAgo = System.currentTimeMillis() - 86_400_000L
            cacheDir?.listFiles()?.forEach { file ->
                if (file.lastModified() < dayAgo) file.deleteRecursively()
            }
            File(settingsRepository.downloadDirectory.value).listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(TMP_SUFFIX) && file.lastModified() < dayAgo) file.delete()
            }
        }
    }

    // ---- Connection management ----

    /** Must be called on the main thread. */
    private fun restartConnectionLoop() {
        val gen = ++generation
        connectionJob?.cancel()
        closeSocket() // unblocks a connect/read stuck in the previous loop
        connectionJob = lifecycleScope.launch(Dispatchers.IO) { connectionLoop(gen) }
    }

    private fun closeSocket() {
        runCatching { activeSocket?.close() }
    }

    private fun setState(gen: Int, state: ConnectionState) {
        if (gen == generation) _connectionState.value = state
    }

    private suspend fun connectionLoop(gen: Int) {
        var delayMs = INITIAL_RETRY_MS
        while (currentCoroutineContext().isActive) {
            val ip = settingsRepository.peerIp.value
            if (ip.isBlank()) {
                setState(gen, ConnectionState.DISCONNECTED) // restarted when an IP is set
                return
            }

            setState(gen, ConnectionState.CONNECTING)
            val outcome = try {
                runSession(gen, ip, settingsRepository.peerPort.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection to $ip failed: ${e.message}")
                if (e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.NoRouteToHostException) {
                    _lastError.value = "Can't reach the PC at $ip. Check CrossSync is running there and both are on the same network."
                }
                SessionOutcome.FAILED
            }

            when (outcome) {
                SessionOutcome.PAIRING_REQUIRED -> {
                    setState(gen, ConnectionState.PAIRING_REQUIRED) // waits for ACTION_RECONNECT with a code
                    return
                }
                SessionOutcome.ENDED -> delayMs = INITIAL_RETRY_MS
                SessionOutcome.FAILED -> Unit
            }

            // While retrying, show "connecting" rather than flapping to "disconnected"
            setState(gen, ConnectionState.CONNECTING)
            inBackoff = true
            try {
                delay(delayMs)
            } finally {
                inBackoff = false
            }
            delayMs = (delayMs * 1.5).toLong().coerceAtMost(MAX_RETRY_MS)
        }
    }

    /** One connection from connect to close. */
    private suspend fun runSession(gen: Int, ip: String, port: Int): SessionOutcome {
        var sock: Socket = Socket()
        activeSocket = sock
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(ip, port), ProtocolConstants.CONNECT_TIMEOUT_MS)

            val ssl = TlsHelper.wrap(sock, ip, port, ProtocolConstants.CONNECT_TIMEOUT_MS * 2)
            sock = ssl
            activeSocket = ssl
            if (gen != generation) return SessionOutcome.FAILED

            val fingerprint = TlsHelper.fingerprint(ssl)
            val input = BufferedInputStream(ssl.inputStream, 64 * 1024)
            val out = BufferedOutputStream(ssl.outputStream, ProtocolConstants.CHUNK_SIZE + ProtocolConstants.HEADER_SIZE)

            if (!authenticate(input, out, fingerprint)) return SessionOutcome.PAIRING_REQUIRED

            ssl.soTimeout = ProtocolConstants.READ_TIMEOUT_MS
            output = out
            _connectedPeerIp.value = ip
            setState(gen, ConnectionState.CONNECTED)

            readLoop(input)
            return SessionOutcome.ENDED
        } catch (e: JSONException) {
            _lastError.value = "The PC app is out of date. Update CrossSync on Windows."
            return SessionOutcome.FAILED
        } finally {
            output = null
            runCatching { sock.close() }
            withContext(NonCancellable) { abortIncoming(failed = true, error = "Connection lost") }
            if (gen == generation) {
                _connectedPeerIp.value = null
                _connectedPeerName.value = null
                _otherDevices.value = emptyList()
            }
        }
    }

    /**
     * Sends the handshake: the token issued by this PC (matched by certificate fingerprint),
     * or the pairing code the user typed. Returns false if the user needs to (re)pair.
     */
    private fun authenticate(input: InputStream, out: OutputStream, fingerprint: String): Boolean {
        val token = settingsRepository.tokenFor(fingerprint)
        val code = if (token == null) settingsRepository.pendingPairingCode.value else null
        if (token == null && code.isNullOrBlank()) {
            _pairingFingerprint.value = fingerprint
            return false
        }

        val request = JSONObject()
            .put("name", deviceDisplayName())
            .put("deviceId", settingsRepository.deviceId)
            .put("version", ProtocolConstants.PROTOCOL_VERSION)
        if (token != null) request.put("token", token) else request.put("pairingCode", code)
        PacketHeader.writeFrame(out, MessageType.HANDSHAKE, payload = request.toString().toByteArray(StandardCharsets.UTF_8))

        val header = PacketHeader.readFrom(input)
        if (header.type != MessageType.HANDSHAKE ||
            header.payloadLength !in 0..ProtocolConstants.MAX_CONTROL_PAYLOAD_BYTES
        ) {
            throw ProtocolException("Expected handshake reply, got ${header.type}")
        }
        val response = JSONObject(String(input.readExactBytes(header.payloadLength.toInt()), StandardCharsets.UTF_8))
        settingsRepository.clearPendingPairingCode()

        if (!response.optBoolean("accepted", false)) {
            val reason = response.optString("reason")
            if (reason == "unsupported_version") {
                _lastError.value = "The PC app is out of date. Update CrossSync on Windows."
                throw ProtocolException("PC rejected protocol version")
            }
            if (token != null) settingsRepository.removeToken(fingerprint) // the PC removed this phone
            _pairingFingerprint.value = fingerprint
            _lastError.value = when {
                reason == "bad_code" -> "Wrong pairing code. Check the code shown on the PC."
                token != null -> "The PC no longer recognizes this phone. Enter the pairing code shown on the PC."
                else -> null
            }
            return false
        }

        response.optString("token").takeIf { it.isNotEmpty() }?.let { settingsRepository.saveToken(fingerprint, it) }
        _connectedPeerName.value = response.optString("name").ifBlank { null }
        _pairingFingerprint.value = null
        _lastError.value = null
        return true
    }

    /**
     * The name the user gave the phone (Settings → About phone, e.g. "Alex's Galaxy S24"),
     * which the PC shows in its device list and Explorer menu. Falls back to the model code.
     */
    private fun deviceDisplayName(): String =
        runCatching {
            android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private suspend fun readLoop(input: InputStream) {
        while (currentCoroutineContext().isActive) {
            val header = PacketHeader.readFrom(input) // SocketTimeoutException after 60s of silence
            if (header.payloadLength !in 0..ProtocolConstants.maxPayloadLength(header.type)) {
                throw ProtocolException("Payload length ${header.payloadLength} out of range for ${header.type}")
            }
            if (!handleIncoming(header, input)) return
        }
    }

    // ---- Incoming packet dispatch ----

    private fun InputStream.readExactBytes(size: Int): ByteArray {
        val result = ByteArray(size)
        var totalRead = 0
        while (totalRead < size) {
            val count = read(result, totalRead, size - totalRead)
            if (count == -1) throw EOFException("Unexpected EOF while reading $size bytes (got $totalRead)")
            totalRead += count
        }
        return result
    }

    private fun InputStream.skipExact(size: Long) {
        var remaining = size
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() == -1) throw EOFException("Unexpected EOF while skipping payload")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    /** Returns false when the PC asked to disconnect. */
    private suspend fun handleIncoming(header: PacketHeader, input: InputStream): Boolean {
        when (header.type) {
            MessageType.CLIPBOARD_TEXT  -> handleClipboardText(header, input)
            MessageType.CLIPBOARD_IMAGE -> handleClipboardImage(header, input)
            MessageType.FILE_META       -> handleFileMeta(header, input)
            MessageType.FILE_CHUNK      -> handleFileChunk(header, input)
            MessageType.TRANSFER_CANCEL -> handleTransferCancel(header, input)
            MessageType.DEVICE_LIST     -> handleDeviceList(header, input)
            MessageType.PING            -> currentOutput()?.let { sendFrame(it, MessageType.PONG) }
            MessageType.PONG            -> Unit
            MessageType.DISCONNECT      -> return false
            else                        -> input.skipExact(header.payloadLength) // incl. UNKNOWN, repeated HANDSHAKE
        }
        return true
    }

    private fun handleDeviceList(header: PacketHeader, input: InputStream) {
        val json = String(input.readExactBytes(header.payloadLength.toInt()), StandardCharsets.UTF_8)
        _otherDevices.value = runCatching {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                PeerDevice(entry.getString("id"), entry.optString("name").ifBlank { "Phone" })
            }
        }.getOrElse { emptyList() }
    }

    private suspend fun handleClipboardText(header: PacketHeader, input: InputStream) {
        val text = String(input.readExactBytes(header.payloadLength.toInt()), StandardCharsets.UTF_8)

        if (settingsRepository.clipboardSyncEnabled.value) {
            // Mark as synced first so the clipboard listeners don't echo it back to the PC
            ClipboardAccessibilityService.lastSyncedText = text
            ClipboardAccessibilityService.markOwnClipboardWrite()
            withContext(Dispatchers.Main) {
                runCatching {
                    clipboardManager?.setPrimaryClip(ClipData.newPlainText("CrossSync", text))
                }
            }
        }
        clipboardRepository.addTextFromPc(text)
    }

    private suspend fun handleClipboardImage(header: PacketHeader, input: InputStream) {
        val payload = input.readExactBytes(header.payloadLength.toInt())
        val (mime, image) = ClipboardImagePayload.parse(payload) ?: return

        val file = withContext(Dispatchers.IO) { ClipboardSync.storeImage(this@TcpConnectionService, image, mime) }
        if (settingsRepository.clipboardSyncEnabled.value) {
            // Mark as synced first so the clipboard listeners don't echo it back to the PC
            ClipboardAccessibilityService.lastSyncedText = ClipboardSync.keyFor(image)
            ClipboardAccessibilityService.markOwnClipboardWrite()
            withContext(Dispatchers.Main) {
                runCatching {
                    clipboardManager?.setPrimaryClip(ClipboardSync.imageClip(this@TcpConnectionService, file))
                }.onFailure { Log.w(TAG, "Could not put image on clipboard: ${it.message}") }
            }
        }
        clipboardRepository.addImage(file.absolutePath, ClipboardSource.PC)
    }

    private suspend fun handleFileMeta(header: PacketHeader, input: InputStream) {
        // Payload: [nameLen:4][name:nameLen][size:8][sha256:64]
        val buf = input.readExactBytes(header.payloadLength.toInt())
        val fileName: String
        val totalBytes: Long
        val sha256: String
        try {
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
            val nameLen = bb.int
            if (nameLen < 0 || buf.size != 4 + nameLen + 8 + 64) throw ProtocolException("Malformed FILE_META")
            val nameBytes = ByteArray(nameLen).also { bb.get(it) }
            fileName = sanitizeFileName(String(nameBytes, StandardCharsets.UTF_8))
            totalBytes = bb.long
            sha256 = String(ByteArray(64).also { bb.get(it) }, StandardCharsets.US_ASCII)
        } catch (e: BufferUnderflowException) {
            throw ProtocolException("Malformed FILE_META", e)
        }
        if (totalBytes < 0) throw ProtocolException("Negative file size")

        // A new file before the previous one finished means the sender gave up on it
        abortIncoming(failed = true, error = "Interrupted by a new transfer")

        val downloadDir = File(settingsRepository.downloadDirectory.value).apply { mkdirs() }
        val tmpFile = File(downloadDir, "$fileName.${UUID.randomUUID()}$TMP_SUFFIX")

        val id = transferRepository.createTransfer(
            fileName = fileName,
            fileSizeBytes = totalBytes,
            direction = TransferDirection.RECEIVING,
            sha256 = sha256,
            localPath = tmpFile.absolutePath,
        )
        incoming = IncomingTransfer(id, fileName, totalBytes, sha256, tmpFile, FileOutputStream(tmpFile, false))
    }

    private suspend fun handleFileChunk(header: PacketHeader, input: InputStream) {
        val cur = incoming
        if (cur == null) {
            // Chunks of a cancelled/aborted file: consume them to stay in sync
            input.skipExact(header.payloadLength)
            return
        }

        if (cancelIncomingId == cur.id) {
            cancelIncomingId = null
            input.skipExact(header.payloadLength)
            currentOutput()?.let { sendCancel(it, ProtocolConstants.CANCEL_BY_RECEIVER, cur.sha256) }
            abortIncoming(failed = false, error = null)
            return
        }

        val isLast = (header.flags.toInt() and ProtocolFlags.LAST_CHUNK.toInt()) != 0
        val chunkData = input.readExactBytes(header.payloadLength.toInt())

        if (cur.receivedBytes + chunkData.size > cur.totalBytes) {
            abortIncoming(failed = true, error = "Received more data than the announced file size")
            return
        }

        withContext(Dispatchers.IO) { cur.stream.write(chunkData) }
        cur.receivedBytes += chunkData.size

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - cur.lastProgressAt
        if (elapsed >= PROGRESS_INTERVAL_MS) {
            val speedBps = (cur.receivedBytes - cur.bytesAtLastProgress) * 1000 / elapsed.coerceAtLeast(1)
            transferRepository.updateProgress(cur.id, cur.receivedBytes, speedBps)
            cur.lastProgressAt = now
            cur.bytesAtLastProgress = cur.receivedBytes
        }

        if (isLast) finalizeIncomingTransfer(cur)
    }

    private suspend fun handleTransferCancel(header: PacketHeader, input: InputStream) {
        val buf = input.readExactBytes(header.payloadLength.toInt())
        if (buf.size != ProtocolConstants.CANCEL_PAYLOAD_BYTES) return
        val sha = String(buf, 1, 64, StandardCharsets.US_ASCII)

        when (buf[0]) {
            ProtocolConstants.CANCEL_BY_SENDER ->
                if (incoming?.sha256.equals(sha, ignoreCase = true)) {
                    abortIncoming(failed = false, error = "Cancelled on PC")
                }
            ProtocolConstants.CANCEL_BY_RECEIVER ->
                if (outgoingSha256.equals(sha, ignoreCase = true)) cancelOutgoing = true
        }
    }

    private suspend fun finalizeIncomingTransfer(cur: IncomingTransfer) {
        incoming = null
        runCatching {
            cur.stream.flush()
            cur.stream.close()
        }

        val ok = cur.receivedBytes == cur.totalBytes &&
            withContext(Dispatchers.IO) { ChecksumManager.verify(cur.tmpFile, cur.sha256) }

        if (!ok) {
            runCatching { cur.tmpFile.delete() }
            transferRepository.markFailed(cur.id, if (cur.receivedBytes == cur.totalBytes) "Checksum mismatch" else "Incomplete file")
            notifyTransfer(cur.id, cur.fileName, false)
            return
        }

        try {
            val location = withContext(Dispatchers.IO) {
                DownloadPublisher.publish(this@TcpConnectionService, cur.tmpFile, cur.fileName, cur.tmpFile.parentFile!!)
            }
            transferRepository.markCompleted(cur.id, location)
            notifyTransfer(cur.id, cur.fileName, true, location)
        } catch (e: Exception) {
            Log.e(TAG, "Saving ${cur.fileName} failed", e)
            runCatching { cur.tmpFile.delete() }
            transferRepository.markFailed(cur.id, "Could not save file: ${e.message}")
            notifyTransfer(cur.id, cur.fileName, false)
        }
    }

    /** Discards the in-progress incoming file, marking it failed or cancelled. */
    private suspend fun abortIncoming(failed: Boolean, error: String?) {
        val cur = incoming ?: return
        incoming = null
        runCatching { cur.stream.close() }
        runCatching { cur.tmpFile.delete() }
        if (failed) transferRepository.markFailed(cur.id, error) else transferRepository.cancel(cur.id)
    }

    private fun notifyTransfer(id: String, fileName: String, success: Boolean, savedTo: String? = null) {
        if (!settingsRepository.transferNotificationsEnabled.value) return
        runCatching { NotificationHelper.showTransferComplete(this, id.hashCode(), fileName, success, savedTo) }
    }

    /**
     * Reduces a peer-supplied name to a bare file name so it can't escape the download
     * directory (e.g. "../../shared_prefs/x" or an absolute path).
     */
    private fun sanitizeFileName(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
            .trim()
        return if (base.isEmpty() || base == "." || base == "..") {
            "file_${System.currentTimeMillis()}"
        } else {
            base
        }
    }

    // ---- Outgoing messages ----

    private fun currentOutput(): OutputStream? = output

    /**
     * Writes one frame to [target] under the send lock. Fails if [target] is no longer the
     * live connection, so a file started on one connection never continues on the next.
     */
    private suspend fun sendFrame(
        target: OutputStream,
        type: MessageType,
        flags: Byte = ProtocolFlags.NONE,
        payload: ByteArray = EMPTY,
        offset: Int = 0,
        length: Int = payload.size,
    ) = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            if (target !== output) throw IOException("Connection is no longer active")
            PacketHeader.writeFrame(target, type, flags, payload, offset, length)
        }
    }

    private suspend fun sendCancel(target: OutputStream, kind: Byte, sha256: String) {
        val payload = ByteArray(ProtocolConstants.CANCEL_PAYLOAD_BYTES)
        payload[0] = kind
        sha256.padEnd(64).take(64).toByteArray(StandardCharsets.US_ASCII).copyInto(payload, 1)
        sendFrame(target, MessageType.TRANSFER_CANCEL, payload = payload)
    }

    /**
     * Runs an outgoing send without letting its exception escape [lifecycleScope] (which would
     * crash the app). An I/O failure means the stream is dead or left mid-frame, so the socket is
     * closed; the reader then exits and the connection loop reconnects.
     */
    private suspend fun runSafely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Failed to $what: ${e.message}")
            closeSocket()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to $what", e)
        }
    }

    private fun onLocalClipChanged() {
        if (!settingsRepository.clipboardSyncEnabled.value) return
        // On Android 10+ this returns null unless the app is focused; the accessibility service covers that case
        val clip = runCatching { clipboardManager?.primaryClip }.getOrNull()
        ClipboardSync.sendIfNew(this, clip)
    }

    private fun queueClipboardSend(text: String) {
        lifecycleScope.launch {
            clipboardRepository.addTextFromAndroid(text)
            runSafely("send clipboard") { sendClipboardTextInternal(text) }
        }
    }

    /**
     * The live connection, waiting up to [SEND_CONNECT_WAIT_MS] if it isn't up yet — e.g. right
     * after a share started the service, or while it's reconnecting after a network change.
     */
    private suspend fun awaitOutput(): OutputStream? {
        currentOutput()?.let { return it }
        withTimeoutOrNull(SEND_CONNECT_WAIT_MS) {
            _connectionState.first { it == ConnectionState.CONNECTED }
        }
        return currentOutput()
    }

    private suspend fun sendClipboardTextInternal(text: String) {
        val target = awaitOutput() ?: return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > ProtocolConstants.MAX_CLIPBOARD_TEXT_BYTES) return // receiver would reject it
        sendFrame(target, MessageType.CLIPBOARD_TEXT, payload = bytes)
    }

    /** Sends a copied image (a temp file from [ClipboardSync.read]) and records it in history. */
    private suspend fun sendClipboardImageInternal(file: File, mime: String) {
        try {
            val image = withContext(Dispatchers.IO) { file.readBytes() }
            val stored = withContext(Dispatchers.IO) { ClipboardSync.storeImage(this@TcpConnectionService, image, mime) }
            clipboardRepository.addImage(stored.absolutePath, ClipboardSource.ANDROID)

            val target = awaitOutput() ?: return
            sendFrame(target, MessageType.CLIPBOARD_IMAGE, payload = ClipboardImagePayload.build(mime, image))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    private suspend fun sendFileInternal(file: File, route: FileRoute) = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext

        // Record it now so it shows (and can be cancelled) while queued behind another file
        val transferId = transferRepository.createTransfer(
            fileName = file.name,
            fileSizeBytes = file.length(),
            direction = TransferDirection.SENDING,
            localPath = file.absolutePath,
        )

        try {
            fileSendMutex.withLock {
                if (cancelledQueuedIds.remove(transferId)) return@withLock

                val target = awaitOutput()
                if (target == null) {
                    transferRepository.markFailed(transferId, "PC not connected")
                    notifyTransfer(transferId, file.name, false)
                    return@withLock
                }

                val sha256 = ChecksumManager.computeSha256(file)
                val fileSize = file.length()
                outgoingTransferId = transferId
                outgoingSha256 = sha256
                cancelOutgoing = false
                runCatching { wakeLock?.acquire(60 * 60 * 1000L) }

                // Tell the PC where this file goes; plain "PC only" needs no route (and suits older PCs)
                if (!route.isPcOnly) {
                    val routeJson = JSONObject()
                        .put("pc", route.toPc)
                        .put("to", org.json.JSONArray(route.toDevices))
                    sendFrame(target, MessageType.FILE_ROUTE, payload = routeJson.toString().toByteArray(StandardCharsets.UTF_8))
                }

                val nameBytes = file.name.toByteArray(StandardCharsets.UTF_8)
                val metaPayload = ByteBuffer.allocate(4 + nameBytes.size + 8 + 64)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(nameBytes.size)
                    .put(nameBytes)
                    .putLong(fileSize)
                    .put(sha256.toByteArray(StandardCharsets.US_ASCII))
                    .array()
                sendFrame(target, MessageType.FILE_META, payload = metaPayload)

                // Empty file: the loop below never runs, so send a single empty LAST_CHUNK
                // or the receiver waits forever with its .tmp file open.
                if (fileSize == 0L) {
                    sendFrame(target, MessageType.FILE_CHUNK, ProtocolFlags.LAST_CHUNK)
                }

                var bytesSent = 0L
                val buffer = ByteArray(ProtocolConstants.CHUNK_SIZE)
                var lastSpeedSample = SystemClock.elapsedRealtime()
                var bytesAtLastSample = 0L

                FileInputStream(file).use { fis ->
                    while (bytesSent < fileSize) {
                        val read = fis.read(buffer, 0, minOf(buffer.size.toLong(), fileSize - bytesSent).toInt())
                        if (read == -1) break

                        if (cancelOutgoing) {
                            sendCancel(target, ProtocolConstants.CANCEL_BY_SENDER, sha256)
                            transferRepository.cancel(transferId)
                            return@withLock
                        }

                        bytesSent += read
                        val flags = if (bytesSent >= fileSize) ProtocolFlags.LAST_CHUNK else ProtocolFlags.NONE
                        sendFrame(target, MessageType.FILE_CHUNK, flags, buffer, 0, read)

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSpeedSample >= PROGRESS_INTERVAL_MS) {
                            val speed = (bytesSent - bytesAtLastSample) * 1000 / (now - lastSpeedSample).coerceAtLeast(1)
                            transferRepository.updateProgress(transferId, bytesSent, speed)
                            bytesAtLastSample = bytesSent
                            lastSpeedSample = now
                        }
                    }
                }

                if (bytesSent < fileSize) {
                    // File shrank while sending: tell the PC to discard it
                    sendCancel(target, ProtocolConstants.CANCEL_BY_SENDER, sha256)
                    transferRepository.markFailed(transferId, "File changed while sending")
                    return@withLock
                }
                transferRepository.markCompleted(transferId)
                notifyTransfer(transferId, file.name, true)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { transferRepository.markFailed(transferId, "Interrupted") }
            throw e
        } catch (e: Exception) {
            transferRepository.markFailed(transferId, e.message ?: "Transfer interrupted")
            notifyTransfer(transferId, file.name, false)
            throw e
        } finally {
            outgoingTransferId = null
            outgoingSha256 = null
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            // Files picked in the UI are copied into their own folder in cacheDir; remove the copy
            if (file.absolutePath.startsWith(cacheDir.absolutePath)) {
                runCatching {
                    val parent = file.parentFile
                    file.delete()
                    if (parent != null && parent != cacheDir && parent.list()?.isEmpty() == true) parent.delete()
                }
            }
        }
    }

    private fun cancelTransfer(id: String) {
        when (id) {
            outgoingTransferId -> cancelOutgoing = true
            incoming?.id -> cancelIncomingId = id // applied by the reader on the next chunk
            else -> {
                // Queued behind another file, or a stale entry
                cancelledQueuedIds.add(id)
                lifecycleScope.launch { transferRepository.cancel(id) }
            }
        }
    }

    companion object {
        private const val TAG = "TcpConnectionService"
        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val SEND_CONNECT_WAIT_MS = 30_000L
        private const val TMP_SUFFIX = ".crosssync.tmp"
        private val EMPTY = ByteArray(0)

        const val ACTION_STOP = "com.crosssync.ACTION_STOP"
        const val ACTION_RECONNECT = "com.crosssync.ACTION_RECONNECT"
        const val ACTION_SEND_CLIPBOARD = "com.crosssync.ACTION_SEND_CLIPBOARD"
        const val ACTION_SEND_FILE = "com.crosssync.ACTION_SEND_FILE"
        const val ACTION_SEND_CLIPBOARD_IMAGE = "com.crosssync.ACTION_SEND_CLIPBOARD_IMAGE"
        const val EXTRA_MIME = "mime"
        const val ACTION_CANCEL_TRANSFER = "com.crosssync.ACTION_CANCEL_TRANSFER"
        const val EXTRA_CLIPBOARD_TEXT = "clipboard_text"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_TRANSFER_ID = "transfer_id"
        const val EXTRA_TO_PC = "to_pc"
        const val EXTRA_TARGET_IDS = "target_ids"

        // ---- Global observable state (accessed by ViewModels & Tile) ----

        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _connectedPeerIp = MutableStateFlow<String?>(null)
        val connectedPeerIp: StateFlow<String?> = _connectedPeerIp.asStateFlow()

        private val _connectedPeerName = MutableStateFlow<String?>(null)
        val connectedPeerName: StateFlow<String?> = _connectedPeerName.asStateFlow()

        /** Fingerprint of the PC waiting to be paired, for the user to compare with the PC's screen. */
        private val _pairingFingerprint = MutableStateFlow<String?>(null)
        val pairingFingerprint: StateFlow<String?> = _pairingFingerprint.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        /** The other phones connected to the same PC, which files can be sent to via the PC. */
        private val _otherDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
        val otherDevices: StateFlow<List<PeerDevice>> = _otherDevices.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        // ---- Static convenience helpers ----

        /** Starts the service in the foreground (from the UI, tile or boot). */
        fun start(context: Context, action: String? = null) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TcpConnectionService::class.java).setAction(action),
                )
            }.onFailure { Log.w(TAG, "Could not start service: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TcpConnectionService::class.java))
        }

        fun sendClipboardText(context: Context, text: String) = deliver(context) {
            action = ACTION_SEND_CLIPBOARD
            putExtra(EXTRA_CLIPBOARD_TEXT, text)
        }

        /**
         * Sends a file to the PC ([toPc]) and/or, through the PC, to the phones in [targetIds]
         * (IDs from [otherDevices]).
         */
        fun sendFile(
            context: Context,
            filePath: String,
            toPc: Boolean = true,
            targetIds: List<String> = emptyList(),
        ) = deliver(context) {
            action = ACTION_SEND_FILE
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_TO_PC, toPc)
            putExtra(EXTRA_TARGET_IDS, targetIds.toTypedArray())
        }

        /** Sends a copied image saved at [filePath] (deleted after sending). */
        fun sendClipboardImage(context: Context, filePath: String, mime: String) = deliver(context) {
            action = ACTION_SEND_CLIPBOARD_IMAGE
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_MIME, mime)
        }

        fun cancelTransfer(context: Context, transferId: String) = deliver(context) {
            action = ACTION_CANCEL_TRANSFER
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }

        /**
         * Hands an action to the running service. Plain startService throws from the background
         * when the service isn't running (Android 8+), so that case is logged instead of crashing.
         */
        private fun deliver(context: Context, configure: Intent.() -> Unit) {
            runCatching {
                context.startService(Intent(context, TcpConnectionService::class.java).apply(configure))
            }.onFailure { Log.w(TAG, "Service not running; action dropped: ${it.message}") }
        }
    }
}
