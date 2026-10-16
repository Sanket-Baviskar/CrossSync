package com.crosssync.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Deep links into manufacturer-specific permission screens, falling back to App info. */
object OemSettings {

    /** Where "open windows / pop-ups from the background" is granted on this phone. */
    fun openPopupPermission(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            // Xiaomi / Redmi / POCO (MIUI, HyperOS): "Other permissions"
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // Not this manufacturer: try the next one
            }
        }
    }
}
