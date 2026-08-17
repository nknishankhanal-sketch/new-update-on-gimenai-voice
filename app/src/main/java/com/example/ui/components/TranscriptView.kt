package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ChatMessage
import com.example.data.QuickPrompts
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.GlowingIndigo
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TranscriptView(
    messages: List<ChatMessage>,
    onSendTextMessage: (String) -> Unit,
    onPlayAudio: (String) -> Unit,
    onSpeakText: (String) -> Unit = {},
    isAutoSpeakEnabled: Boolean = true,
    onToggleAutoSpeak: () -> Unit = {},
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
    isExpandedHeight: Boolean = false
) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .padding(16.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AI Voice & Text Conversation",
                    color = TextPrimaryDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Nexus AI Nepali Companion",
                    color = TextSecondaryDark,
                    fontSize = 11.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Auto-Voice Toggle Pill
                Surface(
                    color = if (isAutoSpeakEnabled) NeonCyan.copy(alpha = 0.15f) else DarkSurfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isAutoSpeakEnabled) NeonCyan else DarkBorder
                    ),
                    modifier = Modifier.clickable { onToggleAutoSpeak() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Auto Voice Output",
                            tint = if (isAutoSpeakEnabled) NeonCyan else TextSecondaryDark,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isAutoSpeakEnabled) "Voice ON" else "Voice OFF",
                            color = if (isAutoSpeakEnabled) NeonCyan else TextSecondaryDark,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = onClearHistory,
                    modifier = Modifier.testTag("clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear History",
                        tint = TextSecondaryDark
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Suggestion Prompts
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(QuickPrompts.list) { prompt ->
                Surface(
                    color = DarkSurfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier.clickable {
                        onSendTextMessage(prompt)
                    }
                ) {
                    Text(
                        text = prompt,
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Message List
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isExpandedHeight) 380.dp else 220.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "💬 No messages yet",
                        color = TextPrimaryDark,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Select a prompt above, speak into your mic, or type below.",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isExpandedHeight) 380.dp else 220.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageItem(
                        message = msg,
                        onPlayAudio = onPlayAudio,
                        onSpeakText = onSpeakText,
                        onCopyText = { text ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("AI Message", text))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Text Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = {
                    Text(
                        text = "Ask Gemini anything...",
                        color = TextSecondaryDark,
                        fontSize = 14.sp
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("transcript_text_input"),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = DarkBorder,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant,
                    focusedTextColor = TextPrimaryDark,
                    unfocusedTextColor = TextPrimaryDark
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (textInput.isNotBlank()) {
                            onSendTextMessage(textInput)
                            textInput = ""
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (textInput.isNotBlank()) {
                        onSendTextMessage(textInput)
                        textInput = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(NeonCyan)
                    .testTag("send_text_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send Message",
                    tint = DarkSurface
                )
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    onPlayAudio: (String) -> Unit,
    onSpeakText: (String) -> Unit,
    onCopyText: (String) -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormatter.format(Date(message.timestamp)) }

    val isUser = message.sender == ChatMessage.Sender.USER
    val isSystem = message.sender == ChatMessage.Sender.SYSTEM
    val isTool = message.sender == ChatMessage.Sender.TOOL

    val bubbleBg = when {
        isUser -> GlowingIndigo
        isSystem -> DarkSurfaceVariant
        isTool -> androidx.compose.ui.graphics.Color(0xFF1E293B)
        else -> ElectricViolet.copy(alpha = 0.35f)
    }

    val alignment = when {
        isUser -> Alignment.End
        else -> Alignment.Start
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bubbleBg,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            border = when {
                isTool -> androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF38BDF8))
                !isUser && !isSystem -> androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f))
                else -> null
            },
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(if (isUser) 0.75f else 0.92f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (message.sender) {
                                ChatMessage.Sender.USER -> "You"
                                ChatMessage.Sender.ASSISTANT -> "Nexus AI (Gemini Live)"
                                ChatMessage.Sender.SYSTEM -> "System"
                                ChatMessage.Sender.TOOL -> "🛠️ Tool Call"
                            },
                            color = when (message.sender) {
                                ChatMessage.Sender.USER -> NeonCyan
                                ChatMessage.Sender.TOOL -> androidx.compose.ui.graphics.Color(0xFF38BDF8)
                                else -> TextPrimaryDark
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (message.isInterrupted) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = androidx.compose.ui.graphics.Color(0xFFEF4444).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFEF4444))
                            ) {
                                Text(
                                    text = "⚡ Interrupted",
                                    color = androidx.compose.ui.graphics.Color(0xFFFCA5A5),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formattedTime,
                            color = TextSecondaryDark,
                            fontSize = 10.sp
                        )
                        if (!isUser && !isSystem && !isTool) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy text",
                                tint = TextSecondaryDark,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { onCopyText(message.text) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = message.text,
                    color = TextPrimaryDark,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                // Tool Call Details
                if (message.toolInfo != null) {
                    val tool = message.toolInfo
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = DarkSurface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Function: ${tool.toolName}",
                                color = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (tool.argumentsJson.isNotBlank() && tool.argumentsJson != "{}") {
                                Text(
                                    text = "Args: ${tool.argumentsJson}",
                                    color = TextSecondaryDark,
                                    fontSize = 10.sp
                                )
                            }
                            if (tool.resultJson != null) {
                                Text(
                                    text = "Result: ${tool.resultJson}",
                                    color = androidx.compose.ui.graphics.Color(0xFF86EFAC),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                // Audio Playback & TTS Action Buttons for Assistant responses
                if (!isUser && !isSystem && !isTool) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Spoken Voice (TTS / Voice Output)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurface)
                                .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable { onSpeakText(message.text) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Listen voice response",
                                tint = NeonCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "🔊 Gemini Voice",
                                color = NeonCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Live audio chunk if available
                        if (message.pcmAudioBase64 != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkSurface)
                                    .clickable { onPlayAudio(message.pcmAudioBase64) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Replay Audio",
                                    tint = ElectricViolet,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Live Stream Chunk",
                                    color = ElectricViolet,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

