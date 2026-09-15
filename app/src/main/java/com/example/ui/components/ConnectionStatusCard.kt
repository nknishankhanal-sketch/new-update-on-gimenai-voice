package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LiveState
import com.example.ui.theme.CoralWarning
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.GlowingIndigo
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreenActive
import com.example.ui.theme.TextMutedDark
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

@Composable
fun ConnectionStatusCard(
    liveState: LiveState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_animation")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val (statusColor, statusTitle, statusSubtitle, statusIcon) = when (liveState) {
        LiveState.CONNECTING -> Quadruple(
            ElectricViolet,
            "Connecting...",
            "Establishing live WebSocket channel",
            Icons.Default.WifiTethering
        )
        LiveState.LISTENING -> Quadruple(
            NeonGreenActive,
            "Live • Listening",
            "Microphone active — speak to Gemini",
            Icons.Default.Wifi
        )
        LiveState.PROCESSING -> Quadruple(
            NeonCyan,
            "Live • Processing",
            "Gemini is generating live response",
            Icons.Default.Wifi
        )
        LiveState.SPEAKING -> Quadruple(
            GlowingIndigo,
            "Live • Speaking",
            "Streaming 24kHz low-latency voice",
            Icons.Default.Wifi
        )
        LiveState.INTERRUPTED -> Quadruple(
            CoralWarning,
            "Interrupted (Barge-in)",
            "AI paused — listening to you",
            Icons.Default.Wifi
        )
        LiveState.ERROR -> Quadruple(
            CoralWarning,
            "Connection Error",
            "WebSocket session dropped — tap to retry",
            Icons.Default.CloudOff
        )
        LiveState.IDLE -> Quadruple(
            TextMutedDark,
            "Idle",
            "Tap power button below to start session",
            Icons.Default.Wifi
        )
    }

    val animatedDotColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(400),
        label = "status_color_anim"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (liveState == LiveState.ERROR) CoralWarning.copy(alpha = 0.6f) else DarkBorder
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("connection_status_card")
            .run {
                if (liveState == LiveState.ERROR || liveState == LiveState.IDLE) {
                    clickable { onReconnect() }
                } else this
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Pulsing dot indicator box
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (liveState != LiveState.IDLE && liveState != LiveState.ERROR) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(animatedDotColor.copy(alpha = pulseAlpha * 0.4f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(animatedDotColor)
                            .testTag("connection_status_indicator")
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusTitle,
                            color = TextPrimaryDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = statusSubtitle,
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            if (liveState == LiveState.ERROR) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CoralWarning.copy(alpha = 0.2f))
                        .border(1.dp, CoralWarning, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("reconnect_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry Connection",
                            tint = CoralWarning,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Retry",
                            color = CoralWarning,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
