package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.LiveState
import com.example.ui.theme.CoralWarning
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.GlowingIndigo
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreenActive
import com.example.ui.theme.VibrantMagenta

@Composable
fun AiOrbVisualizer(
    liveState: LiveState,
    micVolume: Float,
    speakerVolume: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")

    // Ambient rotation & pulse values
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_rotation"
    )

    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_breath"
    )

    // Base color selection depending on LiveState
    val primaryPulseColor = when (liveState) {
        LiveState.LISTENING -> NeonCyan
        LiveState.SPEAKING -> VibrantMagenta
        LiveState.PROCESSING -> ElectricViolet
        LiveState.INTERRUPTED -> CoralWarning
        LiveState.CONNECTING -> GlowingIndigo
        LiveState.ERROR -> CoralWarning
        LiveState.IDLE -> NeonCyan.copy(alpha = 0.5f)
    }

    val secondaryPulseColor = when (liveState) {
        LiveState.LISTENING -> NeonGreenActive
        LiveState.SPEAKING -> NeonCyan
        LiveState.PROCESSING -> VibrantMagenta
        LiveState.INTERRUPTED -> ElectricViolet
        LiveState.CONNECTING -> ElectricViolet
        LiveState.ERROR -> CoralWarning
        LiveState.IDLE -> GlowingIndigo.copy(alpha = 0.3f)
    }

    // Active volume gain
    val activeVolume = when (liveState) {
        LiveState.LISTENING -> micVolume
        LiveState.SPEAKING -> speakerVolume
        else -> 0f
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Outer Glowing Pulsing Waves & Radial Frequency Bars Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2f) * 0.45f

            // Dynamic outer ripples reacting to volume
            val ringCount = 4
            for (i in 1..ringCount) {
                val expansionFactor = 1f + (i * 0.18f) + (activeVolume * 0.45f * (i / 2f))
                val radius = baseRadius * expansionFactor * (if (liveState == LiveState.IDLE) breathingScale else 1f)
                val alpha = (0.6f / i) * (0.3f + activeVolume * 0.7f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryPulseColor.copy(alpha = alpha),
                            secondaryPulseColor.copy(alpha = alpha * 0.5f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    center = center,
                    radius = radius
                )

                drawCircle(
                    color = primaryPulseColor.copy(alpha = alpha * 0.8f),
                    center = center,
                    radius = radius,
                    style = Stroke(width = (2f + activeVolume * 6f) * (ringCount - i + 1))
                )
            }

            // Radial Frequency Visualizer Bars encircling the center avatar
            val barCount = 32
            val barStartRadius = baseRadius + 14.dp.toPx()
            for (b in 0 until barCount) {
                val angleRad = Math.toRadians((b * (360f / barCount) + rotationAngle).toDouble())
                val noise = kotlin.math.sin(b * 0.5 + (rotationAngle * 0.05)).toFloat()
                val barHeight = 12.dp.toPx() + (activeVolume * 35.dp.toPx() * (0.5f + 0.5f * noise))
                val startX = center.x + (barStartRadius * kotlin.math.cos(angleRad)).toFloat()
                val startY = center.y + (barStartRadius * kotlin.math.sin(angleRad)).toFloat()
                val endX = center.x + ((barStartRadius + barHeight) * kotlin.math.cos(angleRad)).toFloat()
                val endY = center.y + ((barStartRadius + barHeight) * kotlin.math.sin(angleRad)).toFloat()

                val barAlpha = 0.4f + 0.6f * activeVolume
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(primaryPulseColor.copy(alpha = barAlpha), secondaryPulseColor.copy(alpha = barAlpha * 0.5f)),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY)
                    ),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }

        // Center Hero AI Avatar Artwork
        Box(
            modifier = Modifier
                .size(190.dp)
                .shadow(
                    elevation = (16 + (activeVolume * 24).toInt()).dp,
                    shape = CircleShape,
                    spotColor = primaryPulseColor,
                    ambientColor = secondaryPulseColor
                )
                .clip(CircleShape)
                .border(
                    width = (3 + (activeVolume * 5).toInt()).dp,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            primaryPulseColor,
                            secondaryPulseColor,
                            primaryPulseColor
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_ai_avatar),
                contentDescription = "AI Voice Avatar",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
