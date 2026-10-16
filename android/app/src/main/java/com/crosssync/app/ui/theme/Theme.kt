package com.crosssync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary            = CrossSyncCyan,
    onPrimary          = BackgroundDeep,
    primaryContainer   = CrossSyncCyanContainer,
    onPrimaryContainer = CrossSyncCyan,

    secondary            = CrossSyncViolet,
    onSecondary          = BackgroundDeep,
    secondaryContainer   = CrossSyncVioletContainer,
    onSecondaryContainer = CrossSyncViolet,

    background    = BackgroundDeep,
    onBackground  = OnSurfacePrimary,
    surface       = SurfaceDark,
    onSurface     = OnSurfacePrimary,
    surfaceVariant    = SurfaceVariant,
    onSurfaceVariant  = OnSurfaceSecondary,

    outline      = OnSurfaceTertiary,
    outlineVariant = SurfaceVariant,

    error    = StatusDisconnected,
    onError  = OnSurfacePrimary,
)

/**
 * App-wide Material3 theme for CrossSync.
 * Always dark — CrossSync is designed for a dark cockpit aesthetic.
 */
@Composable
fun CrossSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = CrossSyncTypography,
        content     = content,
    )
}
