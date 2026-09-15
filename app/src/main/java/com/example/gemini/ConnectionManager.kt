package com.example.gemini

import android.util.Log
import com.example.data.LiveState
import com.example.data.VoicePreset
import com.example.data.PersonaPreset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface ConnectionResult<out T> {
    data class Success<out T>(val data: T) : ConnectionResult<T>
    data class Failure(val error: ConnectionError, val fallback: FallbackAction?) : ConnectionResult<Nothing>
}

sealed class ConnectionError(
    val code: Int?,
    val message: String,
    val isRetryable: Boolean,
    val userMessage: String,
    val fallbackAction: FallbackAction?
) {
    data class WebSocketFailed(override val code: Int?, cause: Throwable) : ConnectionError(
        code, "WebSocket: ${cause.message}", true, "Connection lost. Reconnecting...", FallbackAction.RetryWebSocket
    )
    data class AuthFailed(override val code: Int?) : ConnectionError(
        code, "Invalid API key", false, "Invalid API key. Check Settings.", FallbackAction.ShowErrorDialog
    )
    data class NetworkUnavailable(cause: Throwable) : ConnectionError(
        null, "Network: ${cause.message}", true, "No internet. Retrying...", FallbackAction.RetryWebSocket
    )
    data class ModelOverloaded(model: String) : ConnectionError(
        503, "$model overloaded", true, "AI busy. Trying backup model...", FallbackAction.TryNextModel
    )
    data class Timeout(op: String) : ConnectionError(
        null, "$op timeout", true, "Request timed out. Retrying...", FallbackAction.RetryWebSocket
    )
    data class Unknown(cause: Throwable) : ConnectionError(
        null, "${cause.message}", true, "Unexpected error. Retrying...", FallbackAction.RetryWebSocket
    )
}

sealed class FallbackAction {
    object RetryWebSocket : FallbackAction()
    object TryRestApi : FallbackAction()
    object TryNextModel : FallbackAction()
    object UseLocalTts : FallbackAction()
    object ShowErrorDialog : FallbackAction()
}

extension fun <T> ConnectionResult<T>.onSuccess(action: (T) -> Unit): ConnectionResult<T> {
    if (this is ConnectionResult.Success) action(data)
    return this
}

extension fun <T> ConnectionResult<T>.onFailure(action: (ConnectionError) -> Unit): ConnectionResult<T> {
    if (this is ConnectionResult.Failure) action(error)
    return this
}

extension fun <T> ConnectionResult<T>.fold(
    onSuccess: (T) -> ConnectionResult<T>,
    onFailure: (ConnectionError) -> ConnectionResult<T>
): ConnectionResult<T> {
    return when (this) {
        is ConnectionResult.Success -> onSuccess(data)
        is ConnectionResult.Failure -> onFailure(error)
    }
}

data class ModelError(val model: String, val code: Int, val body: String)

class ConnectionManager(
    private val liveClient: GeminiLiveClient,
    private val liveService: GeminiLiveService,
    private val ttsSynthesizer: GeminiNativeVoiceSynthesizer,
    private val audioPlayer: AudioPlayer,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private var currentStrategy = ConnectionStrategy.LiveApi
    private var retryCount = 0
    private val maxRetries = 3
    private var currentApiKey = ""
    private var currentVoice = VoicePreset.PUCK
    private var currentPersona = PersonaPreset.ASSISTANT

    enum class ConnectionStrategy {
        LiveApi,      // WebSocket streaming (best quality)
        RestApi,      // REST generateContent + TTS
        LocalTts      // Android TTS only (offline)
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        data class Connecting(val strategy: ConnectionStrategy) : ConnectionState()
        data class Connected(val strategy: ConnectionStrategy) : ConnectionState()
        data class Degraded(val strategy: ConnectionStrategy, val reason: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()

        val shouldShowBanner: Boolean
            get() = when (this) {
                is Disconnected -> false
                is Connected -> false
                else -> true
            }
    }

    fun connect(apiKey: String, voice: VoicePreset, persona: PersonaPreset) {
        currentApiKey = apiKey
        currentVoice = voice
        currentPersona = persona
        retryCount = 0
        scope.launch {
            _state.value = ConnectionState.Connecting(ConnectionStrategy.LiveApi)
            attemptLiveApi()
        }
    }

    private fun attemptLiveApi() {
        val result = liveClient.connectWithResult(currentApiKey, currentVoice, currentPersona)
        
        result.onSuccess {
            _state.value = ConnectionState.Connected(ConnectionStrategy.LiveApi)
            retryCount = 0
            currentStrategy = ConnectionStrategy.LiveApi
        }.onFailure { error ->
            handleLiveApiFailure(error)
        }
    }

    private fun handleLiveApiFailure(error: ConnectionError) {
        when (error.fallback) {
            FallbackAction.RetryWebSocket -> {
                if (retryCount < maxRetries) {
                    retryCount++
                    val delay = minOf(1000L * (1 shl (retryCount - 1)), 8000L)
                    _state.value = ConnectionState.Degraded(
                        ConnectionStrategy.LiveApi, 
                        "Reconnecting in ${delay/1000}s (attempt $retryCount/$maxRetries)"
                    )
                    scope.launch { delay(delay); attemptLiveApi() }
                } else {
                    fallbackToRestApi()
                }
            }
            FallbackAction.TryRestApi -> fallbackToRestApi()
            FallbackAction.ShowErrorDialog -> _state.value = ConnectionState.Error(error.userMessage)
            null -> _state.value = ConnectionState.Error(error.userMessage)
        }
    }

    private fun fallbackToRestApi() {
        _state.value = ConnectionState.Connecting(ConnectionStrategy.RestApi)
        _state.value = ConnectionState.Degraded(ConnectionStrategy.RestApi, "Using REST API fallback")
        
        scope.launch {
            val testResult = liveService.testRestConnection(currentApiKey)
            testResult.onSuccess {
                currentStrategy = ConnectionStrategy.RestApi
                _state.value = ConnectionState.Connected(ConnectionStrategy.RestApi)
            }.onFailure { error ->
                fallbackToLocalTts(error)
            }
        }
    }

    private fun fallbackToLocalTts(lastError: ConnectionError) {
        _state.value = ConnectionState.Degraded(ConnectionStrategy.LocalTts, "Offline mode (system TTS)")
        currentStrategy = ConnectionStrategy.LocalTts
        _state.value = ConnectionState.Connected(ConnectionStrategy.LocalTts)
    }

    fun sendUserMessage(text: String): ConnectionResult<String> {
        return when (currentStrategy) {
            ConnectionStrategy.LiveApi -> liveClient.sendTextWithResult(text)
            ConnectionStrategy.RestApi -> liveService.sendRestWithResult(text, currentApiKey)
            ConnectionStrategy.LocalTts -> ttsSynthesizer.speakWithResult(text, currentApiKey, currentVoice)
        }
    }

    fun speakText(text: String, voice: VoicePreset): ConnectionResult<Unit> {
        return when (currentStrategy) {
            ConnectionStrategy.LiveApi -> liveClient.requestTtsWithResult(text, voice)
            ConnectionStrategy.RestApi -> ttsSynthesizer.speakWithResult(text, currentApiKey, voice)
            ConnectionStrategy.LocalTts -> ttsSynthesizer.speakLocalWithResult(text)
        }
    }

    fun disconnect() {
        liveClient.disconnect()
        _state.value = ConnectionState.Disconnected
        currentStrategy = ConnectionStrategy.LiveApi
        retryCount = 0
    }

    fun reconnect() {
        if (currentApiKey.isNotBlank()) {
            connect(currentApiKey, currentVoice, currentPersona)
        }
    }
}