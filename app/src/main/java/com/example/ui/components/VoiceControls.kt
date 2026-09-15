package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
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
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.VibrantMagenta

@Composable
fun VoiceControls(
    liveState: LiveState,
    isMuted: Boolean,
    isSpeakerMuted: Boolean,
    micVolume: Float,
    isUserSpeaking: Boolean = false,
    onToggleLiveSession: () -> Unit,
    onToggleMuteMic: () -> Unit,
    onToggleMuteSpeaker: () -> Unit,
    onFinishSpeaking: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onToggleTranscript: () -> Unit,
    onTestVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSessionActive = liveState != LiveState.IDLE && liveState != LiveState.ERROR

    val buttonScale by animateFloatAsState(
        targetValue = if (isSessionActive) 1.08f else 1.0f,
        animationSpec = tween(durationMillis = 300),
        label = "button_scale"
    )

    val liveBadgeText = when (liveState) {
        LiveState.LISTENING -> if (isUserSpeaking) "🎙️ USER SPEAKING..." else "👂 LISTENING (VAD ACTIVE)"
        LiveState.SPEAKING -> "🔊 AI SPEAKING"
        LiveState.PROCESSING -> "⚡ AI PROCESSING..."
        LiveState.INTERRUPTED -> "⚡ INTERRUPTED (BARGE-IN)"
        LiveState.CONNECTING -> "🔌 CONNECTING..."
        LiveState.ERROR -> "❌ CONNECTION ERROR"
        LiveState.IDLE -> "TAP TO START LIVE"
    }

    val liveBadgeColor = when (liveState) {
        LiveState.LISTENING -> if (isUserSpeaking) NeonCyan else NeonCyan.copy(alpha = 0.8f)
        LiveState.SPEAKING -> VibrantMagenta
        LiveState.PROCESSING -> ElectricViolet
        LiveState.INTERRUPTED -> CoralWarning
        LiveState.CONNECTING -> GlowingIndigo
        LiveState.ERROR -> CoralWarning
        LiveState.IDLE -> TextSecondaryDark
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Live Status Badge & Done Speaking Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Surface(
                color = liveBadgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, liveBadgeColor.copy(alpha = 0.4f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(liveBadgeColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = liveBadgeText,
                        color = TextPrimaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            if (liveState == LiveState.LISTENING) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = GlowingIndigo,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
                    modifier = Modifier.clickable { onFinishSpeaking() }
                ) {
                    Text(
                        text = "⚡ Done Speaking",
                        color = TextPrimaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // Action Control Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute Mic Button
            IconButton(
                onClick = onToggleMuteMic,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (isMuted) CoralWarning.copy(alpha = 0.2f) else DarkSurface)
                    .border(1.dp, if (isMuted) CoralWarning else DarkBorder, CircleShape)
                    .testTag("mute_mic_button")
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Mute Microphone",
                    tint = if (isMuted) CoralWarning else TextPrimaryDark
                )
            }

            // Big Center Live Session Toggle Button
            Box(
                modifier = Modifier
                    .scale(buttonScale)
                    .size(80.dp)
                    .shadow(
                        elevation = if (isSessionActive) 20.dp else 6.dp,
                        shape = CircleShape,
                        spotColor = if (isSessionActive) NeonCyan else GlowingIndigo
                    )
                    .clip(CircleShape)
                    .background(
                        brush = if (isSessionActive) {
                            Brush.linearGradient(listOf(NeonCyan, GlowingIndigo))
                        } else {
                            Brush.linearGradient(listOf(GlowingIndigo, ElectricViolet))
                        }
                    )
                    .border(
                        width = 2.dp,
                        color = if (isSessionActive) NeonGreenActive else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable { onToggleLiveSession() }
                    .testTag("mic_toggle_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSessionActive) Icons.Default.PowerSettingsNew else Icons.Default.Mic,
                    contentDescription = if (isSessionActive) "End Live Session" else "Start Live Session",
                    tint = TextPrimaryDark,
                    modifier = Modifier.size(38.dp)
                )
            }

            // Mute Speaker Button
            IconButton(
                onClick = onToggleMuteSpeaker,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (isSpeakerMuted) CoralWarning.copy(alpha = 0.2f) else DarkSurface)
                    .border(1.dp, if (isSpeakerMuted) CoralWarning else DarkBorder, CircleShape)
                    .testTag("mute_speaker_button")
            ) {
                Icon(
                    imageVector = if (isSpeakerMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Mute Speaker",
                    tint = if (isSpeakerMuted) CoralWarning else TextPrimaryDark
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Secondary Utility Controls Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Settings Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Voice & Persona Settings",
                    tint = TextSecondaryDark,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Settings",
                    color = TextPrimaryDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Test Voice Audio Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(ElectricViolet.copy(alpha = 0.2f))
                    .border(1.dp, GlowingIndigo, RoundedCornerShape(16.dp))
                    .clickable { onTestVoice() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("test_voice_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Test Voice Output",
                    tint = ElectricViolet,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Test Audio",
                    color = TextPrimaryDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Transcript Sheet Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .clickable { onToggleTranscript() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("transcript_sheet_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "View Live Transcript",
                    tint = NeonCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Transcript",
                    color = TextPrimaryDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
