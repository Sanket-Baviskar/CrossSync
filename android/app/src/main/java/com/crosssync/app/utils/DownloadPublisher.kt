package com.crosssync.app.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * Moves a verified received file to where the user can find it.
 *
 * Android 10+: the public Downloads/CrossSync folder via MediaStore (no storage permission
 * needed, and it shows up in the Files app). Older versions: the app's download directory,
 * since writing to shared storage there would need a runtime storage permission.
 */
object DownloadPublisher {

    private const val SUBFOLDER = "CrossSync"

    /** Returns where the file ended up: a content:// URI string or an absolute path. */
    fun publish(context: Context, source: File, displayName: String, fallbackDir: File): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(context, source, displayName)
        } else {
            fallbackDir.mkdirs()
            val target = uniqueFile(fallbackDir, displayName)
            if (!source.renameTo(target)) {
                source.copyTo(target)
                source.delete()
            }
            target.absolutePath
        }
    }

    /** Human-readable location for Settings. */
    fun locationDescription(fallbackDir: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Downloads/$SUBFOLDER" else fallbackDir

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(context: Context, source: File, displayName: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(displayName))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        // MediaStore renames on collision ("name (1).ext"), so existing files are never overwritten
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a Downloads entry")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Could not open the Downloads entry")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        source.delete()
        return uri.toString()
    }

    private fun mimeTypeFor(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** "name.ext", or "name (1).ext" etc. if taken. */
    fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($i)$ext")
            i++
        }
        return candidate
    }
}
