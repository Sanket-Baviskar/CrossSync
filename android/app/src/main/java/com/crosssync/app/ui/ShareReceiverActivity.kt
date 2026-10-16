package com.crosssync.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import android.app.AlertDialog
import com.crosssync.app.service.FileRoute
import com.crosssync.app.service.PeerDevice
import com.crosssync.app.service.TcpConnectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Share-sheet target: "Share → CrossSync" from WhatsApp, Gallery, Files, etc.
 *
 * - Files (ACTION_SEND / ACTION_SEND_MULTIPLE with EXTRA_STREAM) are sent to the PC, or —
 *   when other phones are connected to it — to whichever of PC / phones the user picks
 *   (the PC forwards files meant for other phones).
 * - Plain text (EXTRA_TEXT only) goes to the PC clipboard, which relays it to the other phones.
 *
 * Shared files are content:// URIs that this app may only read while this activity is alive,
 * so each is copied into the cache before the activity finishes; the service sends the copy and
 * deletes it afterwards. The activity has no UI beyond a toast.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = sharedUris(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        if (uris.isEmpty() && text.isNullOrBlank()) {
            finish()
            return
        }

        // Sharing is an explicit request to send, so make sure sync is running
        TcpConnectionService.start(this)

        if (uris.isEmpty()) {
            TcpConnectionService.sendClipboardText(this, text!!)
            toast(if (isConnected()) "Sent to PC clipboard" else "Will send to PC clipboard once connected")
            finish()
            return
        }

        val phones = TcpConnectionService.otherDevices.value
        if (phones.isEmpty()) {
            send(uris, FileRoute(), "PC")
        } else {
            askWhereToSend(uris, phones)
        }
    }

    /** Other phones are online: let the user pick the PC and/or phones (PC preselected). */
    private fun askWhereToSend(uris: List<Uri>, phones: List<PeerDevice>) {
        val labels = arrayOf("PC") + phones.map { it.name }
        val checked = BooleanArray(labels.size).also { it[0] = true }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (uris.size == 1) "Send to" else "Send ${uris.size} files to")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Send", null) // set below, so an empty choice doesn't close the dialog
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen = phones.filterIndexed { i, _ -> checked[i + 1] }
                if (!checked[0] && chosen.isEmpty()) {
                    toast("Pick at least one destination")
                    return@setOnClickListener
                }
                dialog.dismiss()
                val names = (if (checked[0]) listOf("PC") else emptyList()) + chosen.map { it.name }
                send(uris, FileRoute(toPc = checked[0], toDevices = chosen.map { it.id }), names.joinToString(", "))
            }
        }
        dialog.show()
    }

    private fun send(uris: List<Uri>, route: FileRoute, destination: String) {
        toast(
            (if (uris.size == 1) "Sending to $destination…" else "Sending ${uris.size} files to $destination…") +
                if (isConnected()) "" else " (waiting for connection)"
        )

        lifecycleScope.launch {
            var failed = 0
            for (uri in uris) {
                val copy = withContext(Dispatchers.IO) { copyToCache(uri) }
                if (copy != null) {
                    TcpConnectionService.sendFile(
                        this@ShareReceiverActivity, copy.absolutePath,
                        toPc = route.toPc, targetIds = route.toDevices,
                    )
                } else {
                    failed++
                }
            }
            if (failed > 0) toast("$failed file(s) couldn't be read")
            finish()
        }
    }

    private fun isConnected() =
        TcpConnectionService.connectionState.value == TcpConnectionService.ConnectionState.CONNECTED

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        )
        Intent.ACTION_SEND_MULTIPLE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }.orEmpty()
        else -> emptyList()
    }

    /** Copies a shared item into its own cache folder, keeping its display name. */
    private fun copyToCache(uri: Uri): File? = runCatching {
        val name = displayName(uri)
        val dir = File(cacheDir, "outgoing/${UUID.randomUUID()}").apply { mkdirs() }
        val target = File(dir, name)
        contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target
    }.onFailure { Log.w(TAG, "Could not read shared item $uri: ${it.message}") }.getOrNull()

    private fun displayName(uri: Uri): String {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val name = queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: "shared_${System.currentTimeMillis()}"
        // A display name with a path separator must not point outside the cache folder
        return name.replace('/', '_').replace('\\', '_').ifBlank { "shared_${System.currentTimeMillis()}" }
    }

    private fun toast(message: String) =
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "ShareReceiver"
    }
}
