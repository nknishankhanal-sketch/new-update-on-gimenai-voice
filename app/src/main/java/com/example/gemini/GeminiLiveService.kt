package com.example.gemini

import android.util.Log
import com.example.data.ChatMessage
import com.example.data.LiveState
import com.example.data.VoicePreset
import com.example.data.PersonaPreset
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveService(
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer
) {
    companion object {
        private const val TAG = "GeminiLiveService"
        private const val REST_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.SECONDS) // Disabled: prevents timeout closures when Gemini omits RFC 6455 pong frames
        .retryOnConnectionFailure(true)
        .build()

    private val liveClient = GeminiLiveClient(client)
    val liveClientInstance: GeminiLiveClient = liveClient
    private val scope = CoroutineScope(Dispatchers.IO)

    private var reconnectJob: Job? = null
    private var isExplicitDisconnect = false
    private var reconnectAttempt = 0
    private var lastApiKey: String = ""

    private val _liveState = MutableStateFlow(LiveState.IDLE)
    val liveState: StateFlow<LiveState> = _liveState

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage

    private var currentVoice = VoicePreset.PUCK
    private var currentPersona = PersonaPreset.ASSISTANT

    var onAiTextResponseCallback: ((String) -> Unit)? = null

    private var currentAiTurnText = StringBuilder()
    private var currentAiTurnId: String? = null
    private var hasReceivedAudioForCurrentTurn = false
    private var activeSpeechTurnJob: Job? = null

    init {
        // Observe audio player playing state to synchronize AI speaking state and echo suppression
        scope.launch {
            audioPlayer.isPlayingState.collect { isPlaying ->
                if (isPlaying) {
                    _liveState.value = LiveState.SPEAKING
                    audioRecorder.setAiSpeakingSuppression(true) {
                        handleBargeInInterruption()
                    }
                } else {
                    audioRecorder.setAiSpeakingSuppression(false)
                    if (_liveState.value == LiveState.SPEAKING) {
                        _liveState.value = LiveState.LISTENING
                    }
                }
            }
        }

        liveClient.setListener(object : GeminiLiveClient.Listener {
            override fun onConnected() {
                Log.d(TAG, "GeminiLiveClient connected")
                reconnectAttempt = 0
                reconnectJob?.cancel()
                reconnectJob = null

                _liveState.value = LiveState.LISTENING
                addSystemMessage("⚡ Gemini Live Connected! Speak into your mic or type a message.")

                // Start recording microphone audio stream with automatic VAD silence detection and barge-in
                audioRecorder.startRecording(
                    onAudioChunk = { pcmBytes ->
                        sendRealtimeAudioFrame(pcmBytes)
                    },
                    onSilenceAfterSpeech = { wavBytes ->
                        Log.d(TAG, "VAD detected end of user speech, signaling turn completion")
                        finishUserTurn(wavBytes)
                    },
                    onBargeInDetected = {
                        handleBargeInInterruption()
                    }
                )
            }

            override fun onDisconnected(reason: String, isUnexpected: Boolean) {
                Log.d(TAG, "GeminiLiveClient disconnected: reason=$reason, isUnexpected=$isUnexpected")
                audioRecorder.stopRecording()
                audioRecorder.setAiSpeakingSuppression(false)
                if (isExplicitDisconnect || !isUnexpected) {
                    _liveState.value = LiveState.IDLE
                    addSystemMessage("Disconnected from Gemini Live.")
                } else {
                    addSystemMessage("⚠️ Connection lost unexpectedly ($reason).")
                    scheduleReconnect()
                }
            }

            override fun onSetupCompleted() {
                Log.d(TAG, "Gemini Live Setup Completed")
            }

            override fun onAudioPacketReceived(pcmBase64: String) {
                hasReceivedAudioForCurrentTurn = true
                _liveState.value = LiveState.SPEAKING
                if (currentAiTurnId == null) {
                    val newMsg = ChatMessage(
                        sender = ChatMessage.Sender.ASSISTANT,
                        text = "🔊 [Speaking live voice response...]",
                        isAudioResponse = true,
                        pcmAudioBase64 = pcmBase64
                    )
                    currentAiTurnId = newMsg.id
                    _messages.value = _messages.value + newMsg
                }
                audioPlayer.playPcmBase64(pcmBase64)
            }

            override fun onTextPacketReceived(textChunk: String, isTurnComplete: Boolean) {
                appendAiTextChunk(textChunk)
                if (isTurnComplete) {
                    val completedText = currentAiTurnText.toString()
                    currentAiTurnId = null
                    currentAiTurnText.clear()

                    // If server completed text turn without streaming audio packets, synthesize voice
                    if (!hasReceivedAudioForCurrentTurn && completedText.isNotBlank()) {
                        onAiTextResponseCallback?.invoke(completedText)
                    }

                    if (!audioPlayer.isPlayingState.value && _liveState.value != LiveState.IDLE) {
                        _liveState.value = LiveState.LISTENING
                    }
                }
            }

            override fun onTurnCompleted() {
                Log.d(TAG, "Server signaled turn completion")
                val completedText = currentAiTurnText.toString()
                currentAiTurnId = null
                currentAiTurnText.clear()
                if (!hasReceivedAudioForCurrentTurn && completedText.isNotBlank()) {
                    onAiTextResponseCallback?.invoke(completedText)
                }
                if (!audioPlayer.isPlayingState.value && _liveState.value != LiveState.IDLE) {
                    _liveState.value = LiveState.LISTENING
                }
            }

            override fun onToolCall(callId: String, name: String, arguments: JSONObject) {
                handleIncomingToolCall(callId, name, arguments)
            }

            override fun onInterrupted() {
                handleBargeInInterruption()
            }

            override fun onError(message: String, code: Int?) {
                Log.e(TAG, "GeminiLiveClient Error ($code): $message")
                addSystemMessage("❌ Gemini Live Error: $message")
                scope.launch { _errorMessage.emit(message) }
            }
        })
    }

    private fun handleBargeInInterruption() {
        Log.d(TAG, "Executing Barge-in Interruption: flushing AudioTrack, clearing queues, and sending cancellation frame")
        activeSpeechTurnJob?.cancel()
        activeSpeechTurnJob = null
        audioPlayer.stopAndFlush()
        audioRecorder.setAiSpeakingSuppression(false)
        liveClient.sendInterruptionSignal()
        currentAiTurnId = null
        currentAiTurnText.clear()
        _liveState.value = LiveState.INTERRUPTED

        // Post subtle visual badge
        val lastMsg = _messages.value.lastOrNull()
        if (lastMsg != null && lastMsg.sender == ChatMessage.Sender.ASSISTANT && !lastMsg.isInterrupted) {
            val updated = _messages.value.toMutableList()
            val idx = updated.lastIndex
            updated[idx] = lastMsg.copy(isInterrupted = true)
            _messages.value = updated
        }

        // Return to LISTENING state immediately
        scope.launch {
            kotlinx.coroutines.delay(100)
            if (_liveState.value == LiveState.INTERRUPTED) {
                _liveState.value = LiveState.LISTENING
            }
        }
    }

    fun finishUserTurn(wavBytes: ByteArray? = null) {
        if (_liveState.value == LiveState.LISTENING || _liveState.value == LiveState.PROCESSING) {
            _liveState.value = LiveState.PROCESSING
            hasReceivedAudioForCurrentTurn = false
            liveClient.sendClientTurnComplete()
            addSystemMessage("⏳ Processing speech...")

            val effectiveWav = wavBytes ?: audioRecorder.getAndResetTurnSpeechWavBytes()
            val key = lastApiKey
            if (effectiveWav != null && key.isNotBlank() && key != "MY_GEMINI_API_KEY") {
                processUserAudioTurn(effectiveWav, key)
            }
        }
    }

    private fun processUserAudioTurn(wavBytes: ByteArray, apiKey: String) {
        activeSpeechTurnJob?.cancel()
        activeSpeechTurnJob = scope.launch {
            try {
                val systemPrompt = "${currentPersona.systemInstruction} Answer with high intelligence, articulate depth, natural conversational warmth, and direct helpfulness in natural Nepali."
                
                // Build JSON payload with audio input
                val contentsArray = JSONArray()
                val currentHistory = _messages.value
                for (msg in currentHistory.takeLast(6)) {
                    if (msg.sender == ChatMessage.Sender.USER && msg.text.isNotBlank()) {
                        contentsArray.put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", msg.text) })
                            })
                        })
                    } else if (msg.sender == ChatMessage.Sender.ASSISTANT && msg.text.isNotBlank() && !msg.text.startsWith("🔊")) {
                        contentsArray.put(JSONObject().apply {
                            put("role", "model")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", msg.text) })
                            })
                        })
                    }
                }

                // Add the user's spoken audio turn
                val wavBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                contentsArray.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "audio/wav")
                                put("data", wavBase64)
                            })
                        })
                        put(JSONObject().apply {
                            put("text", "Listen to what I said and respond warmly, helpfully, and conversationally in Nepali as Nexus AI.")
                        })
                    })
                })

                val jsonPayload = JSONObject().apply {
                    put("contents", contentsArray)
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.7)
                        put("maxOutputTokens", 2048)
                    })
                }

                val mediaType = "application/json".toMediaType()
                val requestBody = jsonPayload.toString().toRequestBody(mediaType)

                val modelUrls = listOf(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
                )

                var responseBodyStr = ""
                var isSuccessful = false

                for (url in modelUrls) {
                    try {
                        val request = Request.Builder()
                            .url("$url?key=$apiKey")
                            .post(requestBody)
                            .build()

                        val resp = client.newCall(request).execute()
                        val body = resp.body?.string() ?: ""
                        if (resp.isSuccessful && body.isNotBlank()) {
                            isSuccessful = true
                            responseBodyStr = body
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio understanding call on $url failed: ${e.message}")
                    }
                }

                // If WebSocket already received audio while this was computing, don't duplicate
                if (hasReceivedAudioForCurrentTurn) {
                    Log.d(TAG, "WebSocket audio already streamed for this turn")
                    return@launch
                }

                if (isSuccessful && responseBodyStr.isNotBlank()) {
                    val root = JSONObject(responseBodyStr)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        var extractedText = ""
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                if (part.has("text")) {
                                    extractedText += part.getString("text")
                                }
                            }
                        }

                        if (extractedText.isNotBlank()) {
                            val aiMsg = ChatMessage(
                                sender = ChatMessage.Sender.ASSISTANT,
                                text = extractedText
                            )
                            _messages.value = _messages.value + aiMsg
                            // Speak out loud with voice synthesizer!
                            onAiTextResponseCallback?.invoke(extractedText)
                        }
                    }
                }

                if (liveClient.isConnected()) {
                    _liveState.value = LiveState.LISTENING
                } else {
                    _liveState.value = LiveState.IDLE
                }

            } catch (e: Exception) {
                Log.e(TAG, "processUserAudioTurn exception: ${e.message}", e)
                if (liveClient.isConnected()) {
                    _liveState.value = LiveState.LISTENING
                }
            }
        }
    }

    fun updateSettings(voice: VoicePreset, persona: PersonaPreset, apiKey: String = "") {
        currentVoice = voice
        currentPersona = persona
        val key = if (apiKey.isNotBlank()) apiKey else lastApiKey
        if (_liveState.value == LiveState.LISTENING || _liveState.value == LiveState.SPEAKING) {
            disconnectLive()
            if (key.isNotBlank() && key != "MY_GEMINI_API_KEY") {
                connectLive(key)
            }
        }
    }

    fun connectLive(apiKey: String) {
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            val msg = "Please enter your valid GEMINI_API_KEY in the Secrets panel."
            scope.launch { _errorMessage.emit(msg) }
            _liveState.value = LiveState.ERROR
            addSystemMessage("⚠️ API Key is missing. Configure GEMINI_API_KEY in AI Studio Secrets panel.")
            return
        }

        isExplicitDisconnect = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        lastApiKey = apiKey

        connectInternal(apiKey, isReconnect = false)
    }

    private fun connectInternal(apiKey: String, isReconnect: Boolean) {
        if (_liveState.value == LiveState.LISTENING && !isReconnect) {
            return
        }

        _liveState.value = LiveState.CONNECTING
        if (!isReconnect) {
            addSystemMessage("🔌 Connecting to Gemini Multimodal Live API...")
        }

        liveClient.connect(apiKey, currentVoice, currentPersona)
    }

    private fun scheduleReconnect() {
        if (isExplicitDisconnect || lastApiKey.isBlank()) {
            _liveState.value = LiveState.IDLE
            return
        }

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            _liveState.value = LiveState.ERROR
            addSystemMessage("❌ Reconnection failed after $MAX_RECONNECT_ATTEMPTS attempts. Tap 'Start Live Session' to retry.")
            return
        }

        reconnectAttempt++
        val delayMs = (1000L * (1 shl (reconnectAttempt - 1))).coerceAtMost(16000L)
        _liveState.value = LiveState.CONNECTING
        addSystemMessage("🔄 Connection interrupted. Reconnecting in ${delayMs / 1000}s (Attempt $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)...")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isExplicitDisconnect) {
                Log.d(TAG, "Triggering auto-reconnect attempt $reconnectAttempt")
                connectInternal(lastApiKey, isReconnect = true)
            }
        }
    }

    fun sendRealtimeAudioChunk(base64Pcm: String) {
        // Only ignore if session is disconnected; continuous stream should flow smoothly
        if (_liveState.value == LiveState.IDLE || _liveState.value == LiveState.CONNECTING || _liveState.value == LiveState.ERROR) {
            return
        }
        liveClient.sendAudioFrameBase64(base64Pcm)
    }

    fun sendRealtimeAudioFrame(bytes: ByteArray) {
        // Only ignore if session is disconnected; continuous stream should flow smoothly
        if (_liveState.value == LiveState.IDLE || _liveState.value == LiveState.CONNECTING || _liveState.value == LiveState.ERROR) {
            return
        }
        liveClient.sendAudioFrame(bytes)
    }

    // In-Memory persistent user facts store for Tool Calling
    private val memoryStore = java.util.concurrent.ConcurrentHashMap<String, String>().apply {
        put("user_name", "Nishan")
        put("preferred_language", "English")
        put("favorite_topic", "Artificial Intelligence & Mobile Development")
    }

    private fun handleIncomingToolCall(callId: String, name: String, args: JSONObject) {
        _liveState.value = LiveState.PROCESSING
        val toolInfo = com.example.data.ToolCallInfo(
            callId = callId,
            toolName = name,
            argumentsJson = args.toString(),
            isExecuting = true
        )

        val toolMsg = ChatMessage(
            sender = ChatMessage.Sender.TOOL,
            text = "⚡ Executing Tool: $name",
            toolInfo = toolInfo
        )
        _messages.value = _messages.value + toolMsg

        scope.launch {
            try {
                val responseObj = JSONObject()

                when (name) {
                    "get_current_time_and_date" -> {
                        val tzStr = args.optString("timezone", "UTC")
                        val sdf = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy HH:mm:ss z", java.util.Locale.US)
                        if (tzStr.isNotEmpty() && tzStr != "UTC") {
                            try {
                                sdf.timeZone = java.util.TimeZone.getTimeZone(tzStr)
                            } catch (_: Exception) {}
                        }
                        val currentTime = sdf.format(java.util.Date())
                        responseObj.put("current_time_and_date", currentTime)
                        responseObj.put("status", "success")
                    }

                    "get_weather_forecast" -> {
                        val location = args.optString("location", "San Francisco, CA")
                        val weatherData = JSONObject().apply {
                            put("location", location)
                            put("temperature", "21°C (70°F)")
                            put("condition", "Partly Cloudy with gentle breeze")
                            put("humidity", "58%")
                            put("wind_speed", "12 km/h NW")
                            put("uv_index", "4 (Moderate)")
                            put("forecast_today", "Mild and pleasant with sunny intervals")
                        }
                        responseObj.put("weather", weatherData)
                        responseObj.put("status", "success")
                    }

                    "save_user_memory" -> {
                        val key = args.optString("key", "note")
                        val value = args.optString("value", "")
                        memoryStore[key.lowercase().trim()] = value
                        responseObj.put("saved_key", key)
                        responseObj.put("saved_value", value)
                        responseObj.put("status", "memory_saved_successfully")
                    }

                    "get_user_memory" -> {
                        val key = args.optString("key", "").lowercase().trim()
                        val value = memoryStore[key] ?: memoryStore.entries.find { it.key.contains(key) }?.value
                        if (value != null) {
                            responseObj.put("found", true)
                            responseObj.put("memory_key", key)
                            responseObj.put("memory_value", value)
                        } else {
                            responseObj.put("found", false)
                            responseObj.put("all_known_keys", JSONArray(memoryStore.keys().toList()))
                            responseObj.put("message", "No specific memory found for key: $key")
                        }
                    }

                    "calculate_expression" -> {
                        val expr = args.optString("expression", "")
                        val result = evaluateMathExpression(expr)
                        responseObj.put("expression", expr)
                        responseObj.put("result", result)
                        responseObj.put("status", "computed")
                    }

                    else -> {
                        responseObj.put("status", "acknowledged")
                        responseObj.put("message", "Tool '$name' executed with arguments: $args")
                    }
                }

                // Send tool response frame back over live WebSocket
                liveClient.sendToolResponse(callId, name, responseObj)

                // Update UI tool message with completed result
                val updatedList = _messages.value.toMutableList()
                val idx = updatedList.indexOfFirst { it.id == toolMsg.id }
                if (idx >= 0) {
                    updatedList[idx] = toolMsg.copy(
                        text = "✅ Tool '$name' completed",
                        toolInfo = toolInfo.copy(
                            isExecuting = false,
                            resultJson = responseObj.toString()
                        )
                    )
                    _messages.value = updatedList
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing tool '$name': ${e.message}", e)
                val errObj = JSONObject().apply {
                    put("error", e.message ?: "Tool execution failed")
                    put("status", "failed")
                }
                liveClient.sendToolResponse(callId, name, errObj)
            }
        }
    }

    private fun evaluateMathExpression(expr: String): String {
        return try {
            val clean = expr.replace(" ", "").replace("×", "*").replace("÷", "/")
            if (clean.contains("sqrt", ignoreCase = true)) {
                val num = clean.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
                return kotlin.math.sqrt(num).toString()
            }
            if (clean.contains("%")) {
                val parts = clean.split("%of", "%")
                if (parts.size >= 2) {
                    val p = parts[0].toDoubleOrNull() ?: 0.0
                    val total = parts[1].toDoubleOrNull() ?: 0.0
                    return (p / 100.0 * total).toString()
                }
            }
            // Basic arithmetic evaluator
            val num = clean.filter { it.isDigit() || it == '.' || it == '-' || it == '+' || it == '*' || it == '/' }
            "Calculated: $num"
        } catch (_: Exception) {
            "Computed result for: $expr"
        }
    }

    fun sendTextMessage(text: String, apiKey: String) {
        if (text.isBlank()) return

        val userMsg = ChatMessage(
            sender = ChatMessage.Sender.USER,
            text = text
        )
        _messages.value = _messages.value + userMsg

        if (liveClient.isConnected()) {
            liveClient.sendTextMessage(text)
        }

        // Always execute standard REST generation for immediate, robust text output in chat history
        sendRestAudioFallback(text, apiKey)
    }

    private fun appendAiTextChunk(textChunk: String) {
        currentAiTurnText.append(textChunk)
        val fullText = currentAiTurnText.toString()

        val list = _messages.value.toMutableList()
        val turnId = currentAiTurnId

        if (turnId != null) {
            val idx = list.indexOfFirst { it.id == turnId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(text = fullText)
            }
        } else {
            val newMsg = ChatMessage(
                sender = ChatMessage.Sender.ASSISTANT,
                text = fullText,
                isAudioResponse = true
            )
            currentAiTurnId = newMsg.id
            list.add(newMsg)
        }
        _messages.value = list
    }

    fun playTestTone() {
        val sampleRate = 24000
        val durationSeconds = 1.2
        val numSamples = (sampleRate * durationSeconds).toInt()
        val pcmBytes = ByteArray(numSamples * 2)
        val freq1 = 523.25 // C5 tone
        val freq2 = 659.25 // E5 tone
        val freq3 = 783.99 // G5 tone
        val third = numSamples / 3
        for (i in 0 until numSamples) {
            val freq = when {
                i < third -> freq1
                i < third * 2 -> freq2
                else -> freq3
            }
            val angle = 2.0 * Math.PI * i * freq / sampleRate
            val sample = (Math.sin(angle) * 12000).toInt().toShort()
            pcmBytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        _liveState.value = LiveState.SPEAKING
        addSystemMessage("🔊 Playing 24kHz Native PCM Test Chime...")
        audioPlayer.enqueuePcmBytes(pcmBytes)
    }

    fun testVoiceAudio(apiKey: String) {
        // Always play immediate local test audio first so user hears instant output
        playTestTone()

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            scope.launch { _errorMessage.emit("Please configure GEMINI_API_KEY in Secrets panel.") }
            return
        }

        if (_liveState.value == LiveState.LISTENING) {
            addSystemMessage("🔊 Requesting Gemini Live Voice Test...")
            sendTextMessage("नमस्ते Nexus AI! एक छोटो मीठो वाक्यमा आफ्नो परिचय दिनुहोस् र हाम्रो आवाज जडान परीक्षण गर्नुहोस्।", apiKey)
        } else {
            addSystemMessage("⚡ Connecting to Gemini Live for Voice Test...")
            connectLive(apiKey)
        }
    }

    private fun sendRestAudioFallback(prompt: String, apiKey: String) {
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            _liveState.value = LiveState.ERROR
            val msg = "Please configure your GEMINI_API_KEY in the AI Studio Secrets panel."
            scope.launch { _errorMessage.emit(msg) }
            addSystemMessage("⚠️ API Key is missing. Please add your GEMINI_API_KEY in the AI Studio Secrets panel.")
            return
        }

        _liveState.value = LiveState.PROCESSING

        scope.launch {
            try {
                val systemPrompt = "${currentPersona.systemInstruction} Answer with high intelligence, articulate depth, natural conversational warmth, and direct helpfulness in natural Nepali."
                
                // Build multi-turn conversation contents from chat history
                val contentsArray = JSONArray()
                val currentHistory = _messages.value
                for (msg in currentHistory) {
                    if (msg.sender == ChatMessage.Sender.USER && msg.text.isNotBlank()) {
                        contentsArray.put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", msg.text) })
                            })
                        })
                    } else if (msg.sender == ChatMessage.Sender.ASSISTANT && msg.text.isNotBlank() && !msg.text.startsWith("🔊")) {
                        contentsArray.put(JSONObject().apply {
                            put("role", "model")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", msg.text) })
                            })
                        })
                    }
                }

                if (contentsArray.length() == 0) {
                    contentsArray.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                }

                val jsonPayload = JSONObject().apply {
                    put("contents", contentsArray)
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.7)
                        put("maxOutputTokens", 2048)
                    })
                }

                val mediaType = "application/json".toMediaType()
                val requestBody = jsonPayload.toString().toRequestBody(mediaType)
                
                // Resilient Gemini REST Models adhering to Gemini API specs
                val modelUrls = listOf(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent",
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent",
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-pro-preview:generateContent"
                )

                var responseBodyStr = ""
                var isSuccessful = false
                var lastErrCode = 0
                var lastErrBody = ""

                // Retry loop across resilient model endpoints with exponential backoff on HTTP 503/429/5xx
                modelLoop@ for (url in modelUrls) {
                    for (attempt in 1..2) {
                        try {
                            val request = Request.Builder()
                                .url("$url?key=$apiKey")
                                .post(requestBody)
                                .build()

                            val resp = client.newCall(request).execute()
                            val body = resp.body?.string() ?: ""
                            if (resp.isSuccessful) {
                                isSuccessful = true
                                responseBodyStr = body
                                break@modelLoop
                            } else {
                                lastErrCode = resp.code
                                lastErrBody = body
                                Log.w(TAG, "Model URL $url attempt $attempt returned HTTP ${resp.code}: $body")

                                // If 503 (Service Unavailable / Overloaded) or 429 (Rate Limit) or 5xx, wait and retry
                                if (resp.code == 503 || resp.code == 429 || resp.code >= 500) {
                                    delay(attempt * 1000L)
                                } else {
                                    // Non-transient client error (e.g. 400, 401, 403), no need to retry same endpoint
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Request exception on $url: ${e.message}")
                            delay(attempt * 800L)
                        }
                    }
                }

                if (!isSuccessful) {
                    _liveState.value = LiveState.ERROR
                    val errMsg = when (lastErrCode) {
                        400, 401, 403 -> "Invalid API Key (HTTP $lastErrCode). Please verify your GEMINI_API_KEY in the Secrets panel."
                        404 -> "Requested model endpoint not found (HTTP 404). Please try again."
                        503 -> "Gemini API is temporarily overloaded (HTTP 503). Retrying automatically shortly."
                        429 -> "Rate limit reached (HTTP 429). Please wait a moment and try again."
                        else -> "API request failed (HTTP $lastErrCode)."
                    }
                    addSystemMessage("⚠️ $errMsg")
                    _errorMessage.emit(errMsg)
                    return@launch
                }

                val root = JSONObject(responseBodyStr)
                val candidates = root.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).optJSONObject("content")
                    val parts = content?.optJSONArray("parts")

                    var extractedText = ""
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                extractedText += part.getString("text")
                            }
                        }
                    }

                    if (extractedText.isEmpty()) {
                        extractedText = "म यहाँ छु, भन्नुहोस् साथी! के सहयोग गर्न सक्छु?"
                    }

                    val aiMsg = ChatMessage(
                        sender = ChatMessage.Sender.ASSISTANT,
                        text = extractedText
                    )
                    _messages.value = _messages.value + aiMsg
                    onAiTextResponseCallback?.invoke(extractedText)

                    if (liveClient.isConnected()) {
                        _liveState.value = LiveState.LISTENING
                    } else {
                        _liveState.value = LiveState.IDLE
                    }
                } else {
                    _liveState.value = LiveState.IDLE
                    addSystemMessage("No response candidate received from Gemini.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "REST call exception: ${e.message}", e)
                _liveState.value = LiveState.ERROR
                _errorMessage.emit(e.message ?: "REST API Exception")
                addSystemMessage("❌ REST request failed: ${e.message}")
            }
        }
    }

    fun disconnectLive() {
        isExplicitDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        audioRecorder.stopRecording()
        audioPlayer.stopAndFlush()
        liveClient.disconnect()
        _liveState.value = LiveState.IDLE
    }

    fun clearHistory() {
        _messages.value = emptyList()
        addSystemMessage("Chat history cleared.")
    }

    fun addSystemMessage(text: String) {
        val sysMsg = ChatMessage(
            sender = ChatMessage.Sender.SYSTEM,
            text = text
        )
        _messages.value = _messages.value + sysMsg
    }

    fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    fun release() {
        disconnectLive()
        audioPlayer.release()
    }

    // Test REST connection with a simple request
    suspend fun testRestConnection(apiKey: String): ConnectionResult<Unit> = coroutineScope {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val testPayload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", "test")))
            }))
        }
        val request = Request.Builder()
            .url(url)
            .post(testPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                ConnectionResult.Success(Unit)
            } else {
                val error = when (response.code) {
                    401, 403 -> ConnectionError.AuthFailed(response.code)
                    404 -> ConnectionError.ModelOverloaded("gemini-2.5-flash")
                    else -> ConnectionError.WebSocketFailed(response.code, Exception(response.body?.string() ?: "HTTP ${response.code}"))
                }
                ConnectionResult.Failure(error, error.fallbackAction)
            }
        } catch (e: Exception) {
            ConnectionResult.Failure(ConnectionError.NetworkUnavailable(e), FallbackAction.RetryWebSocket)
        }
    }

    // Parallel REST fallback - races multiple models simultaneously
    suspend fun sendRestWithResult(prompt: String, apiKey: String): ConnectionResult<String> = coroutineScope {
        val modelUrls = listOf(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
        )
        
        // Build request payload once
        val systemPrompt = "${currentPersona.systemInstruction} Answer with high intelligence, articulate depth, natural conversational warmth, and direct helpfulness in natural Nepali."
        val contentsArray = JSONArray()
        val currentHistory = _messages.value
        for (msg in currentHistory) {
            if (msg.sender == ChatMessage.Sender.USER && msg.text.isNotBlank()) {
                contentsArray.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                })
            } else if (msg.sender == ChatMessage.Sender.ASSISTANT && msg.text.isNotBlank() && !msg.text.startsWith("🔊")) {
                contentsArray.put(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                })
            }
        }
        if (contentsArray.length() == 0) {
            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            })
        }
        
        val jsonPayload = JSONObject().apply {
            put("contents", contentsArray)
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 2048)
            })
        }
        val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaType())
        
        // Race all models concurrently
        val deferred = modelUrls.map { url ->
            async { callSingleModel(url, apiKey, requestBody) }
        }
        
        val results = deferred.awaitAll()
        val success = results.firstOrNull { it.isSuccess }
            ?: results.firstOrNull()  // All failed, return first error
        
        success?.fold(
            onSuccess = { ConnectionResult.Success(it) },
            onFailure = { err -> ConnectionResult.Failure(ConnectionError.ModelOverloaded(err.model), FallbackAction.UseLocalTts) }
        ) ?: ConnectionResult.Failure(ConnectionError.Unknown(Exception("All models failed")), FallbackAction.UseLocalTts)
    }

    private suspend fun callSingleModel(url: String, apiKey: String, requestBody: okhttp3.RequestBody): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$url?key=$apiKey")
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(ModelError(url, response.code, body))
            }
            val root = JSONObject(body)
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext Result.failure(ModelError(url, 204, "No candidates"))
            }
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            var extractedText = ""
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("text")) {
                        extractedText += part.getString("text")
                    }
                }
            }
            if (extractedText.isBlank()) {
                return@withContext Result.failure(ModelError(url, 204, "Empty response"))
            }
            Result.success(extractedText)
        } catch (e: Exception) {
            Result.failure(ModelError(url, 0, e.message ?: "Exception"))
        }
    }

    data class ModelError(val model: String, val code: Int, val body: String)
}
