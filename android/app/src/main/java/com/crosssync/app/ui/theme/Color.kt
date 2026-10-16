package com.crosssync.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---- Primary brand palette (deep cyan / teal family) ----
val CrossSyncCyan = Color(0xFF00D4FF)
val CrossSyncCyanDark = Color(0xFF0097B5)
val CrossSyncCyanContainer = Color(0xFF003543)

// ---- Secondary accent (vivid violet) ----
val CrossSyncViolet = Color(0xFF7C6FFF)
val CrossSyncVioletDark = Color(0xFF5A4ECC)
val CrossSyncVioletContainer = Color(0xFF1A1440)

// ---- Surface & background hierarchy (dark mode only) ----
val BackgroundDeep   = Color(0xFF090D12)    // deepest — base background
val SurfaceDark      = Color(0xFF111820)    // cards, sheets
val SurfaceVariant   = Color(0xFF1A2432)    // elevated cards
val SurfaceHighlight = Color(0xFF243040)    // hover / pressed

// ---- Semantic colours ----
val StatusConnected    = Color(0xFF2ECC71)   // green
val StatusConnecting   = Color(0xFFF39C12)   // amber
val StatusDisconnected = Color(0xFFE74C3C)   // red

// ---- Text / icon hierarchy ----
val OnSurfacePrimary   = Color(0xFFE8F0F8)   // primary text
val OnSurfaceSecondary = Color(0xFF8DAABB)   // secondary / label text
val OnSurfaceTertiary  = Color(0xFF4A6070)   // disabled / placeholder

// ---- Transfer progress gradient ----
val ProgressStart = Color(0xFF00D4FF)
val ProgressEnd   = Color(0xFF7C6FFF)
