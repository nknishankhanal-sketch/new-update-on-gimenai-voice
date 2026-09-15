package com.example.gemini

import android.util.Base64
import android.util.Log
import com.example.data.PersonaPreset
import com.example.data.VoicePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages a WebSocket connection to the Gemini Multimodal Live API,
 * handling binary transmission of audio frames and receiving AI response packets.
 */
class GeminiLiveClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 0 for infinite WebSocket stream duration
        .writeTimeout(0, TimeUnit.MILLISECONDS) // 0 for infinite write timeout
        .pingInterval(0, TimeUnit.SECONDS) // Disabled: avoids closing WebSocket when Gemini endpoint omits RFC 6455 pong frames
        .retryOnConnectionFailure(true)
        .build()
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val WS_BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        const val LIVE_MODEL = "models/gemini-2.0-flash-exp"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val INACTIVITY_TIMEOUT_MS = 120_000L
        const val MAX_AUTO_RETRIES = 4
        const val BASE_RETRY_DELAY_MS = 1500L
        const val MAX_RETRY_DELAY_MS = 12000L
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String, isUnexpected: Boolean)
        fun onSetupCompleted()
        fun onAudioPacketReceived(pcmBase64: String)
        fun onTextPacketReceived(textChunk: String, isTurnComplete: Boolean)
        fun onTurnCompleted()
        fun onToolCall(callId: String, name: String, arguments: JSONObject)
        fun onInterrupted()
        fun onError(message: String, code: Int?)
    }

    private var webSocket: WebSocket? = null
    private var listener: Listener? = null
    private var isExplicitDisconnect = false
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private val lastActivityTimestamp = AtomicLong(0L)
    private val lastPingTimestamp = AtomicLong(0L)

    private var lastApiKey: String = ""
    private var lastVoice: VoicePreset = VoicePreset.PUCK
    private var lastPersona: PersonaPreset = PersonaPreset.ASSISTANT
    private var lastCustomSystemInstruction: String? = null
    private var autoRetryAttempt = 0
    private var autoRetryJob: Job? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun isConnected(): Boolean = webSocket != null

    fun getLastActivityTimestamp(): Long = lastActivityTimestamp.get()

    /**
     * Maps WebSocket closure codes to human-readable RFC 6455 descriptions
     */
    private fun getCloseCodeDescription(code: Int): String {
        return when (code) {
            1000 -> "1000 Normal Closure"
            1001 -> "1001 Going Away"
            1002 -> "1002 Protocol Error"
            1003 -> "1003 Unsupported Data"
            1005 -> "1005 No Status Received"
            1006 -> "1006 Abnormal Closure"
            1007 -> "1007 Invalid Frame Payload Data"
            1008 -> "1008 Policy Violation"
            1009 -> "1009 Message Too Big"
            1010 -> "1010 Mandatory Extension Missing"
            1011 -> "1011 Internal Server Error"
            1012 -> "1012 Service Restart"
            1013 -> "1013 Try Again Later"
            1014 -> "1014 Bad Gateway"
            1015 -> "1015 TLS Handshake Failure"
            else -> "$code Custom / Unknown Code"
        }
    }

    /**
     * Determines whether an error response or exception is eligible for automatic retry
     */
    private fun isRetryable(httpCode: Int?, t: Throwable): Boolean {
        if (isExplicitDisconnect) return false
        // HTTP 400 (Bad Request), 401/403 (Invalid credentials) should not retry
        if (httpCode != null) {
            return when (httpCode) {
                400, 401, 403 -> false
                404, 429, 500, 502, 503, 504 -> true
                else -> true
            }
        }
        return true
    }

    /**
     * Establishes a WebSocket connection to Gemini Multimodal Live API
     */
    fun connect(
        apiKey: String,
        voice: VoicePreset = VoicePreset.PUCK,
        persona: PersonaPreset = PersonaPreset.ASSISTANT,
        customSystemInstruction: String? = null
    ) {
        if (apiKey.isBlank()) {
            listener?.onError("API key cannot be empty.", null)
            return
        }

        lastApiKey = apiKey
        lastVoice = voice
        lastPersona = persona
        lastCustomSystemInstruction = customSystemInstruction
        autoRetryAttempt = 0
        autoRetryJob?.cancel()
        autoRetryJob = null

        connectInternal(apiKey, voice, persona, customSystemInstruction, isAutoRetry = false)
    }

    private fun connectInternal(
        apiKey: String,
        voice: VoicePreset,
        persona: PersonaPreset,
        customSystemInstruction: String?,
        isAutoRetry: Boolean
    ) {
        isExplicitDisconnect = false
        disconnectInternal(silent = true)

        val url = "$WS_BASE_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        Log.d(TAG, "Opening WebSocket to Gemini Live API (autoRetry=$isAutoRetry, attempt=$autoRetryAttempt)...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket != this@GeminiLiveClient.webSocket) return
                Log.d(TAG, "WebSocket Opened to Gemini Live API - Initializing ping/pong heartbeat (HTTP ${response.code})")
                autoRetryAttempt = 0
                autoRetryJob?.cancel()
                autoRetryJob = null
                lastActivityTimestamp.set(System.currentTimeMillis())
                startHeartbeat(webSocket)
                listener?.onConnected()
                sendSetup(voice, persona, customSystemInstruction)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket != this@GeminiLiveClient.webSocket) return
                lastActivityTimestamp.set(System.currentTimeMillis())
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket != this@GeminiLiveClient.webSocket) return
                lastActivityTimestamp.set(System.currentTimeMillis())
                // Binary frame received from server
                handleBinaryMessage(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != this@GeminiLiveClient.webSocket) return
                val desc = getCloseCodeDescription(code)
                Log.w(TAG, "WebSocket onClosing: code=$code ($desc), reason='$reason'")
                stopHeartbeat()
                if (code != 1000 && !isExplicitDisconnect) {
                    Log.w(TAG, "Server initiated abnormal close code=$code ($desc)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != this@GeminiLiveClient.webSocket) return
                stopHeartbeat()
                this@GeminiLiveClient.webSocket = null

                val httpCode = response?.code
                val httpMessage = response?.message ?: "None"
                val exceptionType = t.javaClass.simpleName
                val errMsg = t.message ?: "WebSocket connection failure ($exceptionType)"

                val isSocketClosed = t is java.net.SocketException || 
                        errMsg.contains("Socket is closed", ignoreCase = true) ||
                        errMsg.contains("Canceled", ignoreCase = true) ||
                        errMsg.contains("closed", ignoreCase = true)

                if (isExplicitDisconnect || isSocketClosed) {
                    Log.d(TAG, "WebSocket closed cleanly: $errMsg (explicit=$isExplicitDisconnect)")
                    if (!isExplicitDisconnect) {
                        listener?.onDisconnected(errMsg, isUnexpected = false)
                    }
                } else {
                    Log.e(
                        TAG,
                        "WebSocket onFailure: HTTP Error Code=${httpCode ?: "N/A"} ($httpMessage), " +
                                "Exception=$exceptionType: '$errMsg'",
                        t
                    )
                    listener?.onError("Connection failure: $errMsg (HTTP ${httpCode ?: "N/A"})", httpCode)
                    listener?.onDisconnected(errMsg, isUnexpected = true)

                    if (isRetryable(httpCode, t)) {
                        scheduleAutoRetry("Failure ($exceptionType, HTTP ${httpCode ?: "N/A"})")
                    } else {
                        Log.e(TAG, "Non-retryable failure encountered (HTTP $httpCode). Automatic retry aborted.")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != this@GeminiLiveClient.webSocket) return
                val desc = getCloseCodeDescription(code)
                Log.d(TAG, "WebSocket onClosed: code=$code ($desc), reason='$reason'")
                stopHeartbeat()
                this@GeminiLiveClient.webSocket = null
                if (!isExplicitDisconnect) {
                    val isUnexpected = code != 1000
                    listener?.onDisconnected(reason.ifBlank { desc }, isUnexpected = isUnexpected)
                    if (isUnexpected && code != 1008) {
                        scheduleAutoRetry("Unexpected Closed ($desc)")
                    }
                }
            }
        })
    }

    /**
     * Schedules an automatic retry sequence with exponential backoff for network resilience
     */
    private fun scheduleAutoRetry(triggerReason: String) {
        if (isExplicitDisconnect || lastApiKey.isBlank()) {
            Log.d(TAG, "Auto-retry skipped: isExplicitDisconnect=$isExplicitDisconnect")
            return
        }

        if (autoRetryAttempt >= MAX_AUTO_RETRIES) {
            Log.w(TAG, "Auto-retry limit reached ($MAX_AUTO_RETRIES attempts). Stopping automatic retry sequence.")
            return
        }

        autoRetryJob?.cancel()
        val attemptNumber = autoRetryAttempt + 1
        val delayMs = minOf(BASE_RETRY_DELAY_MS * (1L shl autoRetryAttempt), MAX_RETRY_DELAY_MS)
        Log.i(
            TAG,
            "Scheduling automatic retry sequence [Attempt $attemptNumber/$MAX_AUTO_RETRIES] in ${delayMs}ms due to: $triggerReason"
        )

        autoRetryJob = clientScope.launch {
            delay(delayMs)
            if (!isActive || isExplicitDisconnect || isConnected()) {
                Log.d(TAG, "Auto-retry cancelled before execution")
                return@launch
            }
            autoRetryAttempt++
            Log.i(TAG, "Executing automatic retry attempt $autoRetryAttempt/$MAX_AUTO_RETRIES to Gemini Live API...")
            connectInternal(
                apiKey = lastApiKey,
                voice = lastVoice,
                persona = lastPersona,
                customSystemInstruction = lastCustomSystemInstruction,
                isAutoRetry = true
            )
        }
    }

    /**
     * Starts the periodic heartbeat monitor to guarantee connection liveness, monitor ping/pong intervals,
     * and detect/recover from silent socket drops during idle periods.
     */
    private fun startHeartbeat(webSocket: WebSocket) {
        stopHeartbeat()
        val now = System.currentTimeMillis()
        lastActivityTimestamp.set(now)
        lastPingTimestamp.set(now)

        heartbeatJob = clientScope.launch {
            Log.d(TAG, "Starting WebSocket ping/pong heartbeat monitor (interval: ${HEARTBEAT_INTERVAL_MS}ms, inactivity timeout: ${INACTIVITY_TIMEOUT_MS}ms)")
            while (isActive && this@GeminiLiveClient.webSocket == webSocket) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isActive || this@GeminiLiveClient.webSocket != webSocket) break

                val currentTime = System.currentTimeMillis()
                val idleDuration = currentTime - lastActivityTimestamp.get()
                lastPingTimestamp.set(currentTime)

                Log.d(TAG, "WebSocket Heartbeat check - idleDuration: ${idleDuration}ms")

                // Detect silent connection drops when no frames/pongs have arrived past the inactivity threshold
                if (idleDuration > INACTIVITY_TIMEOUT_MS) {
                    Log.w(TAG, "WebSocket silent drop detected: no activity for ${idleDuration}ms (> ${INACTIVITY_TIMEOUT_MS}ms threshold). Terminating stale socket.")
                    stopHeartbeat()
                    this@GeminiLiveClient.webSocket = null
                    try {
                        webSocket.cancel()
                    } catch (e: Exception) {
                        Log.d(TAG, "Error cancelling stale socket: ${e.message}")
                    }
                    if (!isExplicitDisconnect) {
                        listener?.onError("WebSocket connection timed out (silent drop detected)", null)
                        listener?.onDisconnected("Silent connection drop (heartbeat timeout)", isUnexpected = true)
                        scheduleAutoRetry("Silent connection drop (heartbeat timeout)")
                    }
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Sends initial setup message configuring model, voice, system instructions, and dynamic tools
     */
    fun sendSetup(voice: VoicePreset, persona: PersonaPreset, customSystemInstruction: String? = null) {
        val ws = webSocket ?: return
        try {
            val setupJson = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", LIVE_MODEL)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply {
                            put("AUDIO")
                        })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", voice.voiceId)
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                val baseInstruction = customSystemInstruction?.takeIf { it.isNotBlank() } ?: persona.systemInstruction
                                put("text", "$baseInstruction\n\nCRITICAL CONVERSATIONAL RULES:\n- Respond strictly via direct audio voice.\n- Keep replies punchy, concise, conversational, and direct.\n- NEVER read markdown symbols, asterisks, bullet formatting, or code blocks aloud.")
                            })
                        })
                    })
                    put("tools", JSONArray().apply {
                        // Custom Kotlin Dynamic Device & Memory Tools
                        put(JSONObject().apply {
                            put("functionDeclarations", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("name", "get_current_time_and_date")
                                    put("description", "Returns the exact current local time, date, and day of the week.")
                                    put("parameters", JSONObject().apply {
                                        put("type", "OBJECT")
                                        put("properties", JSONObject().apply {
                                            put("timezone", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Optional timezone e.g. UTC, America/New_York, Asia/Kathmandu")
                                            })
                                        })
                                    })
                                })
                                put(JSONObject().apply {
                                    put("name", "get_weather_forecast")
                                    put("description", "Fetches the current weather, temperature, humidity, and condition for any city.")
                                    put("parameters", JSONObject().apply {
                                        put("type", "OBJECT")
                                        put("properties", JSONObject().apply {
                                            put("location", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "City name and optional country, e.g. San Francisco, Tokyo, London")
                                            })
                                        })
                                        put("required", JSONArray().apply { put("location") })
                                    })
                                })
                                put(JSONObject().apply {
                                    put("name", "save_user_memory")
                                    put("description", "Stores a persistent user fact, reminder, preference, or note into memory.")
                                    put("parameters", JSONObject().apply {
                                        put("type", "OBJECT")
                                        put("properties", JSONObject().apply {
                                            put("key", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Memory topic or key name")
                                            })
                                            put("value", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Information or note to store")
                                            })
                                        })
                                        put("required", JSONArray().apply { put("key"); put("value") })
                                    })
                                })
                                put(JSONObject().apply {
                                    put("name", "get_user_memory")
                                    put("description", "Retrieves stored facts, preferences, or notes from memory.")
                                    put("parameters", JSONObject().apply {
                                        put("type", "OBJECT")
                                        put("properties", JSONObject().apply {
                                            put("key", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Memory key to retrieve or search")
                                            })
                                        })
                                        put("required", JSONArray().apply { put("key") })
                                    })
                                })
                                put(JSONObject().apply {
                                    put("name", "calculate_expression")
                                    put("description", "Evaluates a mathematical calculation or formula with high precision.")
                                    put("parameters", JSONObject().apply {
                                        put("type", "OBJECT")
                                        put("properties", JSONObject().apply {
                                            put("expression", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Math expression e.g. 'sqrt(144) * 3.14159' or '25% of 850'")
                                            })
                                        })
                                        put("required", JSONArray().apply { put("expression") })
                                    })
                                })
                            })
                        })
                    })
                })
            }
            ws.send(setupJson.toString())
            lastActivityTimestamp.set(System.currentTimeMillis())
            Log.d(TAG, "Sent Setup configuration packet with Dynamic Tools to Gemini Live API")
        } catch (e: Exception) {
            Log.e(TAG, "Error framing setup packet: ${e.message}", e)
            listener?.onError("Failed to send setup frame: ${e.message}", null)
        }
    }

    /**
     * Transmits realtime JPEG image frames (1 FPS) for Multimodal Visual Understanding
     */
    fun sendImageFrameBase64(base64Jpeg: String) {
        val ws = webSocket ?: return
        try {
            val frame = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Jpeg)
                        })
                    })
                })
            }
            ws.send(frame.toString())
            lastActivityTimestamp.set(System.currentTimeMillis())
            Log.d(TAG, "Transmitted Multimodal JPEG frame (${base64Jpeg.length} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending realtime image frame: ${e.message}")
        }
    }

    /**
     * Sends structured tool execution responses back to the Gemini Live session
     */
    fun sendToolResponse(callId: String, name: String, responseData: JSONObject) {
        val ws = webSocket ?: return
        try {
            val frame = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", callId)
                            put("name", name)
                            put("response", JSONObject().apply {
                                put("output", responseData)
                            })
                        })
                    })
                })
            }
            ws.send(frame.toString())
            lastActivityTimestamp.set(System.currentTimeMillis())
            Log.d(TAG, "Transmitted toolResponse for '$name' [callId: $callId]")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending toolResponse: ${e.message}", e)
        }
    }

    /**
     * Transmits raw PCM binary audio frames directly over the WebSocket as a binary packet or base64 chunk
     */
    fun sendAudioFrame(pcmBytes: ByteArray, sampleRate: Int = 16000) {
        val ws = webSocket ?: return
        try {
            val base64Pcm = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            sendAudioFrameBase64(base64Pcm, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding binary audio frame: ${e.message}")
        }
    }

    /**
     * Transmits raw PCM binary frame using OkHttp ByteString
     */
    fun sendBinaryFrame(bytes: ByteArray) {
        val ws = webSocket ?: return
        try {
            val byteString = bytes.toByteString(0, bytes.size)
            ws.send(byteString)
            lastActivityTimestamp.set(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error transmitting binary frame: ${e.message}")
        }
    }

    /**
     * Sends base64-encoded audio chunks via mediaChunks frame
     */
    fun sendAudioFrameBase64(base64Pcm: String, sampleRate: Int = 16000) {
        val ws = webSocket ?: return
        try {
            val frame = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=$sampleRate")
                            put("data", base64Pcm)
                        })
                    })
                })
            }
            ws.send(frame.toString())
            lastActivityTimestamp.set(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending realtime audio frame: ${e.message}")
        }
    }

    /**
     * Sends an explicit turn complete signal to trigger immediate generation
     */
    fun sendClientTurnComplete() {
        val ws = webSocket ?: return
        try {
            val frame = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turnComplete", true)
                })
            }
            ws.send(frame.toString())
            lastActivityTimestamp.set(System.currentTimeMillis())
            Log.d(TAG, "Sent explicit client turnComplete frame")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending turnComplete: ${e.message}")
        }
    }

    /**
     * Sends an interruption cancellation signal over WebSocket to halt AI generation
     */
    fun sendInterruptionSignal() {
        val ws = webSocket ?: return
        try {
            // Sending an empty realtimeInput or clientContent turnComplete halts ongoing generation
            val frame = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turnComplete", true)
                })
            }
            ws.send(frame.toString())
            lastActivityTimestamp.set(System.currentTimeMillis())
            Log.d(TAG, "Sent interruption cancellation frame to halt AI generation")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending interruption cancellation frame: ${e.message}")
        }
    }

    /**
     * Sends a text query message packet
     */
    fun sendTextMessage(text: String) {
        val ws = webSocket ?: return
        try {
            val frame = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", text) })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            ws.send(frame.toString())
            lastActivityTimestamp.set(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text frame: ${e.message}")
            listener?.onError("Failed to send text: ${e.message}", null)
        }
    }

    /**
     * Disconnects the WebSocket session gracefully
     */
    fun disconnect() {
        isExplicitDisconnect = true
        autoRetryJob?.cancel()
        autoRetryJob = null
        autoRetryAttempt = 0
        disconnectInternal(silent = false)
    }

    private fun disconnectInternal(silent: Boolean) {
        stopHeartbeat()
        autoRetryJob?.cancel()
        autoRetryJob = null
        val ws = webSocket
        webSocket = null
        if (ws != null) {
            try {
                val closed = ws.close(1000, "Client disconnect")
                if (!closed) {
                    ws.cancel()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Websocket close exception: ${e.message}")
                try { ws.cancel() } catch (_: Exception) {}
            }
        }
        if (!silent) {
            listener?.onDisconnected("User disconnected", isUnexpected = false)
        }
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)

            if (root.has("error")) {
                val errObj = root.getJSONObject("error")
                val code = errObj.optInt("code", -1)
                val message = errObj.optString("message", "Gemini Live API Error")
                Log.e(TAG, "Gemini Live API Error ($code): $message")
                listener?.onError(message, code)
                return
            }

            if (root.has("setupComplete")) {
                Log.d(TAG, "Setup complete confirmed by server")
                listener?.onSetupCompleted()
                return
            }

            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                if (toolCall.has("functionCalls")) {
                    val fCalls = toolCall.getJSONArray("functionCalls")
                    for (i in 0 until fCalls.length()) {
                        val fCall = fCalls.getJSONObject(i)
                        val callId = fCall.optString("id", java.util.UUID.randomUUID().toString())
                        val name = fCall.optString("name", "")
                        val args = fCall.optJSONObject("args") ?: JSONObject()
                        Log.d(TAG, "Received toolCall: '$name' id=$callId args=$args")
                        listener?.onToolCall(callId, name, args)
                    }
                }
            }

            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                if (serverContent.optBoolean("interrupted", false)) {
                    listener?.onInterrupted()
                }

                if (serverContent.has("interrupted") && serverContent.getBoolean("interrupted")) {
                    listener?.onInterrupted()
                }

                val turnComplete = serverContent.optBoolean("turnComplete", false)

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Handle functionCall inside modelTurn part
                            if (part.has("functionCall")) {
                                val fCall = part.getJSONObject("functionCall")
                                val callId = fCall.optString("id", java.util.UUID.randomUUID().toString())
                                val name = fCall.optString("name", "")
                                val args = fCall.optJSONObject("args") ?: JSONObject()
                                Log.d(TAG, "Received functionCall part: '$name' id=$callId args=$args")
                                listener?.onToolCall(callId, name, args)
                            }

                            // Handle text packet
                            if (part.has("text")) {
                                val textChunk = part.getString("text")
                                listener?.onTextPacketReceived(textChunk, turnComplete)
                            }

                            // Handle inline audio packet
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "")
                                val pcmBase64 = inlineData.optString("data", "")
                                if (pcmBase64.isNotEmpty()) {
                                    listener?.onAudioPacketReceived(pcmBase64)
                                }
                            }
                        }
                    }
                }

                if (turnComplete) {
                    listener?.onTurnCompleted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message packet: ${e.message}", e)
        }
    }

    private fun handleBinaryMessage(bytes: ByteString) {
        val rawBytes = bytes.toByteArray()
        val base64Pcm = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        listener?.onAudioPacketReceived(base64Pcm)
    }

    // Result-returning suspend functions for ConnectionManager
    suspend fun connectWithResult(
        apiKey: String,
        voice: VoicePreset,
        persona: PersonaPreset,
        customSystemInstruction: String? = null
    ): ConnectionResult<Unit> = suspendCancellableCoroutine { cont ->
        val listener = object : Listener {
            override fun onConnected() {
                cont.resume(ConnectionResult.Success(Unit))
            }
            override fun onError(message: String, code: Int?) {
                val error = when (code) {
                    401, 403 -> ConnectionError.AuthFailed(code)
                    404 -> ConnectionError.ModelOverloaded(LIVE_MODEL)
                    null -> ConnectionError.NetworkUnavailable(Exception(message))
                    else -> ConnectionError.WebSocketFailed(code, Exception(message))
                }
                cont.resume(ConnectionResult.Failure(error, error.fallbackAction))
            }
            override fun onDisconnected(reason: String, isUnexpected: Boolean) {
                if (isUnexpected) {
                    val error = ConnectionError.WebSocketFailed(null, Exception(reason))
                    cont.resume(ConnectionResult.Failure(error, FallbackAction.RetryWebSocket))
                }
            }
            override fun onSetupCompleted() {}
            override fun onAudioPacketReceived(pcmBase64: String) {}
            override fun onTextPacketReceived(textChunk: String, isTurnComplete: Boolean) {}
            override fun onTurnCompleted() {}
            override fun onToolCall(callId: String, name: String, arguments: JSONObject) {}
            override fun onInterrupted() {}
        }
        setListener(listener)
        connect(apiKey, voice, persona, customSystemInstruction)
        cont.invokeOnCancellation { setListener(null); disconnect() }
    }

    suspend fun sendTextWithResult(text: String): ConnectionResult<String> = suspendCancellableCoroutine { cont ->
        val listener = object : Listener {
            private var accumulatedText = StringBuilder()
            override fun onTextPacketReceived(textChunk: String, isTurnComplete: Boolean) {
                accumulatedText.append(textChunk)
                if (isTurnComplete) {
                    cont.resume(ConnectionResult.Success(accumulatedText.toString()))
                }
            }
            override fun onError(message: String, code: Int?) {
                cont.resume(ConnectionResult.Failure(
                    ConnectionError.WebSocketFailed(code, Exception(message)),
                    FallbackAction.TryRestApi
                ))
            }
            override fun onConnected() {}
            override fun onDisconnected(reason: String, isUnexpected: Boolean) {}
            override fun onSetupCompleted() {}
            override fun onAudioPacketReceived(pcmBase64: String) {}
            override fun onTurnCompleted() {}
            override fun onToolCall(callId: String, name: String, arguments: JSONObject) {}
            override fun onInterrupted() {}
        }
        setListener(listener)
        sendTextMessage(text)
    }

    suspend fun requestTtsWithResult(text: String, voice: VoicePreset): ConnectionResult<Unit> = suspendCancellableCoroutine { cont ->
        val frame = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", "Speak this: $text")))
                }))
                put("turnComplete", true)
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice.voiceId))
                    })
                })
            })
        }
        webSocket?.send(frame.toString())
        
        val listener = object : Listener {
            override fun onAudioPacketReceived(pcmBase64: String) {
                // Audio will be played by the caller via callback
            }
            override fun onTurnCompleted() {
                cont.resume(ConnectionResult.Success(Unit))
            }
            override fun onError(message: String, code: Int?) {
                cont.resume(ConnectionResult.Failure(
                    ConnectionError.WebSocketFailed(code, Exception(message)),
                    FallbackAction.UseLocalTts
                ))
            }
            override fun onConnected() {}
            override fun onDisconnected(reason: String, isUnexpected: Boolean) {}
            override fun onSetupCompleted() {}
            override fun onTextPacketReceived(textChunk: String, isTurnComplete: Boolean) {}
            override fun onToolCall(callId: String, name: String, arguments: JSONObject) {}
            override fun onInterrupted() {}
        }
        setListener(listener)
    }
}
