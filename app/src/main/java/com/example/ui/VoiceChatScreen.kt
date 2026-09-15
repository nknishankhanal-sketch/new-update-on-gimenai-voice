package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.LiveState
import com.example.ui.components.AiOrbVisualizer
import com.example.ui.components.ConnectionStatusBanner
import com.example.ui.components.ConnectionStatusCard
import com.example.ui.components.SettingsDialog
import com.example.ui.components.TranscriptView
import com.example.ui.components.VoiceControls
import com.example.ui.theme.CoralWarning
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.DeepDarkBg
import com.example.ui.theme.GlowingIndigo
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

import com.example.ui.components.MissingApiKeyDialog

@Composable
fun VoiceChatScreen(
    viewModel: LiveVoiceViewModel,
    hasRecordAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val liveState by viewModel.liveState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val micVolume by viewModel.micVolume.collectAsStateWithLifecycle()
    val isUserSpeaking by viewModel.isUserSpeaking.collectAsStateWithLifecycle()
    val speakerVolume by viewModel.speakerVolume.collectAsStateWithLifecycle()
    val selectedVoice by viewModel.selectedVoice.collectAsStateWithLifecycle()
    val selectedPersona by viewModel.selectedPersona.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isSpeakerMuted by viewModel.isSpeakerMuted.collectAsStateWithLifecycle()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsStateWithLifecycle()
    val showTranscriptSheet by viewModel.showTranscriptSheet.collectAsStateWithLifecycle()
    val showMissingApiKeyDialog by viewModel.showMissingApiKeyDialog.collectAsStateWithLifecycle()
    val customApiKey by viewModel.customApiKey.collectAsStateWithLifecycle()
    val autoSpeakResponses by viewModel.autoSpeakResponses.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    var activeTab by remember { mutableIntStateOf(0) } // 0: Live Voice, 1: Text Chat

    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.setMicPermissionGranted(hasRecordAudioPermission)
        viewModel.initContext(context)
    }

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage.collect { err ->
            snackbarHostState.showSnackbar(err)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DeepDarkBg,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top App Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(GlowingIndigo)
                                .border(1.dp, NeonCyan, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Gemini Live Icon",
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Gemini AI Pro",
                                color = TextPrimaryDark,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${selectedVoice.displayName} • ${selectedPersona.title}",
                                color = TextSecondaryDark,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Mode Switcher Segmented Button (Voice vs Text Chat)
                    Surface(
                        color = DarkSurface,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                    ) {
                        Row(modifier = Modifier.padding(3.dp)) {
                            // Voice Tab
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (activeTab == 0) GlowingIndigo else DarkSurface)
                                    .clickable { activeTab = 0 }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.RecordVoiceOver,
                                        contentDescription = "Live Voice Mode",
                                        tint = if (activeTab == 0) NeonCyan else TextSecondaryDark,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Voice",
                                        color = if (activeTab == 0) TextPrimaryDark else TextSecondaryDark,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Chat Tab
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (activeTab == 1) GlowingIndigo else DarkSurface)
                                    .clickable { activeTab = 1 }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Chat,
                                        contentDescription = "AI Text Chat Mode",
                                        tint = if (activeTab == 1) NeonCyan else TextSecondaryDark,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Chat",
                                        color = if (activeTab == 1) TextPrimaryDark else TextSecondaryDark,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Permission Warning Banner if missing microphone access
                if (!hasRecordAudioPermission) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CoralWarning.copy(alpha = 0.15f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CoralWarning),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Microphone Permission Required",
                                    tint = CoralWarning,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Microphone permission is required for live audio pipeline.",
                                    color = TextPrimaryDark,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onRequestPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = CoralWarning),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("grant_permission_button")
                            ) {
                                Text(text = "Grant", color = TextPrimaryDark, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // API Key & Security Notice
                val effectiveKey = viewModel.getEffectiveApiKey()
                val isKeyConfigured = effectiveKey.isNotBlank()

                if (!isKeyConfigured) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CoralWarning.copy(alpha = 0.15f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CoralWarning),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openSettingsDialog() }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "API Key Missing",
                                tint = CoralWarning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Gemini API Key Required",
                                    color = CoralWarning,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tap here to paste your API Key or set GEMINI_API_KEY in Secrets.",
                                    color = TextPrimaryDark,
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = "Settings ⚙️",
                                color = NeonCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openSettingsDialog() }
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Security Notice",
                                tint = NeonCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "GEMINI_API_KEY active • Voice & Text ready",
                                color = TextSecondaryDark,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Connection Status Banner
                ConnectionStatusBanner(
                    state = connectionState,
                    onRetry = { viewModel.reconnect() },
                    onOpenSettings = { viewModel.openSettingsDialog() },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Tab Content Rendering
                if (activeTab == 0) {
                    // VOICE MODE
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        ConnectionStatusCard(
                            liveState = liveState,
                            onReconnect = {
                                if (!hasRecordAudioPermission) {
                                    onRequestPermission()
                                } else {
                                    viewModel.toggleLiveSession()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            AiOrbVisualizer(
                                liveState = liveState,
                                micVolume = micVolume,
                                speakerVolume = speakerVolume,
                                modifier = Modifier.size(260.dp)
                            )
                        }

                        VoiceControls(
                            liveState = liveState,
                            isMuted = isMuted,
                            isSpeakerMuted = isSpeakerMuted,
                            micVolume = micVolume,
                            isUserSpeaking = isUserSpeaking,
                            onToggleLiveSession = {
                                if (!hasRecordAudioPermission) {
                                    onRequestPermission()
                                } else {
                                    viewModel.toggleLiveSession()
                                }
                            },
                            onToggleMuteMic = { viewModel.toggleMuteMic() },
                            onToggleMuteSpeaker = { viewModel.toggleMuteSpeaker() },
                            onFinishSpeaking = { viewModel.finishUserTurn() },
                            onOpenSettings = { viewModel.openSettingsDialog() },
                            onToggleTranscript = { viewModel.toggleTranscriptSheet() },
                            onTestVoice = { viewModel.testVoiceAudio() }
                        )
                    }
                } else {
                    // TEXT CHAT MODE
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        TranscriptView(
                            messages = messages,
                            onSendTextMessage = { text -> viewModel.sendTextMessage(text) },
                            onPlayAudio = { base64 -> viewModel.playAudioMessage(base64) },
                            onSpeakText = { text -> viewModel.speakText(text) },
                            isAutoSpeakEnabled = autoSpeakResponses,
                            onToggleAutoSpeak = { viewModel.toggleAutoSpeak() },
                            onClearHistory = { viewModel.clearHistory() },
                            isExpandedHeight = true
                        )
                    }
                }
            }

            // Slide-up Transcript Overlay in Voice Mode
            if (activeTab == 0) {
                AnimatedVisibility(
                    visible = showTranscriptSheet,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    TranscriptView(
                        messages = messages,
                        onSendTextMessage = { text -> viewModel.sendTextMessage(text) },
                        onPlayAudio = { base64 -> viewModel.playAudioMessage(base64) },
                        onSpeakText = { text -> viewModel.speakText(text) },
                        isAutoSpeakEnabled = autoSpeakResponses,
                        onToggleAutoSpeak = { viewModel.toggleAutoSpeak() },
                        onClearHistory = { viewModel.clearHistory() },
                        isExpandedHeight = false
                    )
                }
            }

            // Configuration Settings Dialog
            if (showSettingsDialog) {
                SettingsDialog(
                    selectedVoice = selectedVoice,
                    selectedPersona = selectedPersona,
                    customApiKey = customApiKey,
                    onSaveApiKey = { viewModel.setCustomApiKey(it) },
                    onTestConnection = { viewModel.testVoiceAudio() },
                    onSelectVoice = { voice -> viewModel.selectVoice(voice) },
                    onSelectPersona = { persona -> viewModel.selectPersona(persona) },
                    onDismiss = { viewModel.closeSettingsDialog() }
                )
            }
        }
    }
}

