package com.crosssync.app.utils

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.crosssync.app.protocol.ChecksumManager
import com.crosssync.app.protocol.ProtocolConstants
import com.crosssync.app.service.ClipboardAccessibilityService
import com.crosssync.app.service.TcpConnectionService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** What was copied. */
sealed class ClipContent {
    data class Text(val text: String) : ClipContent()

    /** Image bytes saved to [file]; [key] identifies it for echo suppression. */
    data class Image(val file: File, val mime: String, val key: String) : ClipContent()
}

/**
 * Reading and writing clipboard content (text and images), shared by every place that
 * watches the clipboard: the service, the accessibility service, the focus trampoline and
 * the main activity.
 */
object ClipboardSync {

    private const val TAG = "ClipboardSync"
    private const val OUTGOING_DIR = "clip_out"
    const val IMAGES_DIR = "clipboard_images"
    private const val MAX_STORED_IMAGES = 50

    /** Formats Windows decodes natively; anything else (e.g. WebP, HEIC) is converted to PNG. */
    private val PASS_THROUGH_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/bmp")

    /**
     * Reads [clip] and sends it to the PC if it hasn't been synced already.
     * Returns false only if there was no clip to read (null on Android 10+ in the background).
     */
    fun sendIfNew(context: Context, clip: ClipData?): Boolean {
        if (clip == null || clip.itemCount == 0) return false
        if (!autoSyncAllowed(context)) return true
        when (val content = read(context, clip)) {
            is ClipContent.Text ->
                if (ClipboardAccessibilityService.claimIfNew(content.text)) {
                    TcpConnectionService.sendClipboardText(context, content.text)
                }
            is ClipContent.Image ->
                if (ClipboardAccessibilityService.claimIfNew(content.key)) {
                    TcpConnectionService.sendClipboardImage(context, content.file.absolutePath, content.mime)
                } else {
                    content.file.delete()
                }
            null -> Unit
        }
        return true
    }

    /**
     * Automatic sends respect "Stop Sync" and the clipboard sync toggle: sending would start the
     * service and reconnect. Read straight from the prefs [SettingsRepository] writes, since this
     * runs from the accessibility service, activities and the connection service alike.
     */
    private fun autoSyncAllowed(context: Context): Boolean {
        val prefs = context.getSharedPreferences("crosssync_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("service_enabled", true) && prefs.getBoolean("clipboard_sync", true)
    }

    /**
     * Text, an image, or null for anything else (e.g. a link to a file, which used to be sent
     * to the PC as its "content://..." address).
     */
    fun read(context: Context, clip: ClipData): ClipContent? {
        val item = clip.getItemAt(0) ?: return null
        val uri = item.uri
        if (uri != null) {
            val mime = context.contentResolver.getType(uri)
                ?: (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
                    .firstOrNull { it.startsWith("image/") }
            if (mime?.startsWith("image/") == true) return readImage(context, uri, mime)
            if (item.text == null) return null
        }
        val text = (item.text ?: item.coerceToText(context))?.toString()
        return if (text.isNullOrBlank()) null else ClipContent.Text(text)
    }

    private fun readImage(context: Context, uri: Uri, mime: String): ClipContent.Image? = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read == -1) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > ProtocolConstants.MAX_CLIPBOARD_IMAGE_BYTES) {
                    Log.w(TAG, "Clipboard image too large to sync")
                    return null
                }
            }
            buffer.toByteArray()
        } ?: return null

        val (bytes, outMime) = if (mime in PASS_THROUGH_MIMES) raw to mime else toPng(raw) ?: return null
        val file = File(context.cacheDir, "$OUTGOING_DIR/${UUID.randomUUID()}.${extensionFor(outMime)}")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        ClipContent.Image(file, outMime, keyFor(bytes))
    }.onFailure { Log.w(TAG, "Could not read clipboard image: ${it.message}") }.getOrNull()

    private fun toPng(bytes: ByteArray): Pair<ByteArray, String>? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray() to "image/png"
    }

    /** Echo-suppression key for an image (shares the "last synced" slot with text). */
    fun keyFor(imageBytes: ByteArray): String = "img:" + ChecksumManager.computeSha256(imageBytes)

    fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        else -> "png"
    }

    /**
     * Saves an image for clipboard history (and, for received images, for the clipboard itself).
     * Keeps the newest [MAX_STORED_IMAGES]; older history entries then show as expired.
     */
    fun storeImage(context: Context, bytes: ByteArray, mime: String): File {
        val dir = File(context.filesDir, IMAGES_DIR).apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}_${UUID.randomUUID()}.${extensionFor(mime)}")
        file.writeBytes(bytes)
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_STORED_IMAGES)
            ?.forEach { it.delete() }
        return file
    }

    /** A clip other apps can paste: a content:// URI from this app's FileProvider. */
    fun imageClip(context: Context, file: File): ClipData {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return ClipData.newUri(context.contentResolver, "CrossSync image", uri)
    }
}
