package com.crosssync.app.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Builds "open this received file" intents for notifications and the transfer list. */
object FileOpener {

    /** Opens a received file, or says why it can't. Returns whether a viewer was started. */
    fun open(context: Context, location: String, fileName: String): Boolean {
        if (!exists(context, location)) {
            Toast.makeText(context, "$fileName no longer exists", Toast.LENGTH_SHORT).show()
            return false
        }
        val intent = viewIntent(context, location, fileName)
        return try {
            if (intent == null) throw ActivityNotFoundException()
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open $fileName", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun exists(context: Context, location: String): Boolean =
        if (location.startsWith("content://")) {
            runCatching {
                context.contentResolver.query(Uri.parse(location), arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
        } else {
            File(location).isFile
        }

    /**
     * [location] is what [DownloadPublisher.publish] returned: a content:// URI (Android 10+)
     * or an absolute path. Returns null if the file can't be shared with other apps.
     */
    fun viewIntent(context: Context, location: String, fileName: String): Intent? {
        val uri = if (location.startsWith("content://")) {
            Uri.parse(location)
        } else {
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(location))
            }.getOrNull()
        } ?: return null

        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Unknown type: let the user pick an app instead of silently finding none
        return if (mime == "*/*") {
            Intent.createChooser(view, "Open $fileName with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            view
        }
    }
}
