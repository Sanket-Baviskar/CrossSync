package com.crosssync.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.crosssync.app.service.TcpConnectionService
import com.crosssync.app.ui.theme.*

/**
 * The animated connection status orb shown at the top of [DashboardScreen].
 * Pulses when connecting, solid green when connected, grey when disconnected.
 */
@Composable
fun ConnectionStatusOrb(
    state: TcpConnectionService.ConnectionState,
    modifier: Modifier = Modifier,
) {
    val color = when (state) {
        TcpConnectionService.ConnectionState.CONNECTED    -> StatusConnected
        TcpConnectionService.ConnectionState.CONNECTING  -> StatusConnecting
        TcpConnectionService.ConnectionState.PAIRING_REQUIRED -> StatusConnecting
        TcpConnectionService.ConnectionState.DISCONNECTED -> StatusDisconnected
    }

    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = if (state == TcpConnectionService.ConnectionState.CONNECTING) 1.4f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue  = if (state == TcpConnectionService.ConnectionState.CONNECTING) 0f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Pulse ring
        if (state == TcpConnectionService.ConnectionState.CONNECTING) {
            Box(
                modifier = Modifier
                    .size((24 * pulseScale).dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
        // Core dot
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Glass-morphism style card used throughout the app.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = { Column(modifier = Modifier.padding(16.dp), content = content) },
    )
}

/**
 * A gradient progress bar that transitions from [ProgressStart] to [ProgressEnd].
 *
 * @param progress  0f..1f progress value.
 */
@Composable
fun GradientProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.horizontalGradient(listOf(ProgressStart, ProgressEnd))

    Box(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(SurfaceHighlight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(50))
                .background(gradient),
        )
    }
}

/**
 * Pill-shaped status badge (e.g. "Connected", "Receiving").
 */
@Composable
fun StatusBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Full-width section header with a horizontal divider accent.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = OnSurfaceSecondary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
