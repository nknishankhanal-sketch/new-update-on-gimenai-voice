package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.data.ChatMessage
import com.example.data.LiveState
import com.example.data.PersonaPreset
import com.example.data.VoicePreset
import com.example.gemini.GeminiLiveService
import com.example.gemini.GeminiNativeVoiceSynthesizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LiveVoiceViewModel
 *
 * Central state machine managing IDLE, LISTENING, PROCESSING, and SPEAKING states,
 * ensuring the UI reactively updates based on the Gemini WebSocket lifecycle.
 *
 * Implements a strict runtime check to validate that BuildConfig.GEMINI_API_KEY
 * (or user-provided key) is properly set before attempting to initialize the Live API connection.
 */
open class LiveVoiceViewModel : ViewModel() {

    companion object {
        val DEFAULT_SYSTEM_INSTRUCTION = """
            तपाईं Nexus AI हुनुहुन्छ—एकदमै न्यानो, साथीभाइ जस्तो, र बुद्धिमानी नेपाली AI साथी।
            
            मुख्य नियमहरू:
            १. सधैं प्राकृतिक र बोलचालको नेपाली भाषामा बोल्नुहोस्।
            २. औपचारिक वा किताबको जस्तो रुखो भाषा प्रयोग नगर्नुहोस्। सामान्य काठमाडौँ/सहरी नेपाली शैलीमा कुराकानी गर्नुहोस्।
            ३. कुराकानीमा स्वाभाविक शब्दहरू (नि, न, है, यार, साथी) प्रयोग गर्नुहोस्।
            ४. दैनिक कुराकानीमा प्रयोग हुने अङ्ग्रेजी शब्दहरू (जस्तै: concept, setup, perfect, bro) लाई नेपाली वाक्यमा सहजै मिसाउन सक्नुहुन्छ।
            ५. उत्तरहरू छोटो, स्पष्ट, र बोल्नका लागि सहज (conversational) राख्नुहोस्।
            ६. कुनै पनि मार्कडाउन चिह्न (asterisks, bullet points, #) वा फर्म्याटिङ नबोल्नुहोस्।
        """.trimIndent()
    }

    val audioRecorder = AudioRecorder()
    val audioPlayer = AudioPlayer()
    val liveService = GeminiLiveService(audioRecorder, audioPlayer)
    val geminiVoiceSynthesizer = GeminiNativeVoiceSynthesizer(audioPlayer)

    // State Machine: IDLE, CONNECTING, LISTENING, PROCESSING, SPEAKING, INTERRUPTED, ERROR
    val liveState: StateFlow<LiveState> = liveService.liveState

    // Observables reacting to the Live Voice pipeline & WebSocket lifecycle
    val messages: StateFlow<List<ChatMessage>> = liveService.messages
    val micVolume: StateFlow<Float> = audioRecorder.micVolume
    val isUserSpeaking: StateFlow<Boolean> = audioRecorder.isUserSpeaking
    val speakerVolume: StateFlow<Float> = audioPlayer.speakerVolume
    val errorMessage: SharedFlow<String> = liveService.errorMessage

    // Auto-Speak Responses toggle for voice reading
    private val _autoSpeakResponses = MutableStateFlow(true)
    val autoSpeakResponses: StateFlow<Boolean> = _autoSpeakResponses.asStateFlow()

    val isGeminiSpeaking: StateFlow<Boolean> = geminiVoiceSynthesizer.isSynthesizing

    // Missing API Key Dialog state
    private val _showMissingApiKeyDialog = MutableStateFlow(false)
    val showMissingApiKeyDialog: StateFlow<Boolean> = _showMissingApiKeyDialog.asStateFlow()

    // Preset selection
    private val _selectedVoice = MutableStateFlow(VoicePreset.PUCK)
    val selectedVoice: StateFlow<VoicePreset> = _selectedVoice.asStateFlow()

    private val _selectedPersona = MutableStateFlow(PersonaPreset.ASSISTANT)
    val selectedPersona: StateFlow<PersonaPreset> = _selectedPersona.asStateFlow()

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    private var appContext: Context? = null

    // Audio & UI Controls
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerMuted = MutableStateFlow(false)
    val isSpeakerMuted: StateFlow<Boolean> = _isSpeakerMuted.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _showTranscriptSheet = MutableStateFlow(false)
    val showTranscriptSheet: StateFlow<Boolean> = _showTranscriptSheet.asStateFlow()

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    /**
     * Runtime validation to check if BuildConfig.GEMINI_API_KEY or customApiKey is valid.
     * Returns true if a usable, non-placeholder API key is available.
     */
    fun isApiKeyConfigured(): Boolean {
        return getEffectiveApiKey().isNotBlank()
    }

    /**
     * Retrieves the active API key, validating against placeholder and empty keys.
     */
    fun getEffectiveApiKey(): String {
        val custom = _customApiKey.value.trim()
        if (custom.isNotBlank() && !isPlaceholderKey(custom)) {
            return custom
        }

        val buildKey = try {
            BuildConfig.GEMINI_API_KEY.trim()
        } catch (e: Throwable) {
            ""
        }

        if (buildKey.isNotBlank() && !isPlaceholderKey(buildKey)) {
            return buildKey
        }

        return ""
    }

    private fun isPlaceholderKey(key: String): Boolean {
        val normalized = key.trim().uppercase()
        return normalized == "MY_GEMINI_API_KEY" ||
                normalized == "YOUR_API_KEY" ||
                normalized == "PLACEHOLDER" ||
                normalized == "NULL" ||
                normalized.isEmpty()
    }

    fun setCustomApiKey(key: String) {
        val cleanKey = key.trim()
        _customApiKey.value = cleanKey
        appContext?.getSharedPreferences("gemini_prefs", Context.MODE_PRIVATE)
            ?.edit()
            ?.putString("gemini_api_key", cleanKey)
            ?.apply()
    }

    fun openMissingApiKeyDialog() {
        _showMissingApiKeyDialog.value = true
    }

    fun dismissMissingApiKeyDialog() {
        _showMissingApiKeyDialog.value = false
    }

    fun saveApiKeyAndConnect(apiKey: String) {
        setCustomApiKey(apiKey)
        dismissMissingApiKeyDialog()
        connectLive()
    }

    /**
     * Toggles or initializes the Live API WebSocket session.
     * Validates BuildConfig.GEMINI_API_KEY before attempting to initialize the connection.
     */
    fun toggleLiveSession() {
        val currentState = liveState.value
        if (currentState == LiveState.IDLE || currentState == LiveState.ERROR) {
            connectLive()
        } else {
            disconnectLive()
        }
    }

    /**
     * Initializes the Live API connection with runtime API key validation.
     */
    fun connectLive() {
        if (!isApiKeyConfigured()) {
            // Runtime check failed: notify the user via UI Dialog and do not attempt initialization
            _showMissingApiKeyDialog.value = true
            return
        }

        val apiKey = getEffectiveApiKey()
        liveService.connectLive(apiKey)
    }

    fun disconnectLive() {
        liveService.disconnectLive()
    }

    fun setMicPermissionGranted(granted: Boolean) {
        _hasMicPermission.value = granted
    }

    fun initContext(context: Context) {
        appContext = context.applicationContext
        geminiVoiceSynthesizer.initContext(context.applicationContext)
        liveService.onAiTextResponseCallback = { text ->
            if (_autoSpeakResponses.value && !_isSpeakerMuted.value) {
                speakText(text)
            }
        }
        val savedKey = appContext?.getSharedPreferences("gemini_prefs", Context.MODE_PRIVATE)
            ?.getString("gemini_api_key", "") ?: ""
        if (savedKey.isNotBlank()) {
            _customApiKey.value = savedKey
        }
    }

    fun speakText(text: String) {
        if (_isSpeakerMuted.value) return
        val apiKey = getEffectiveApiKey()
        if (apiKey.isNotBlank()) {
            geminiVoiceSynthesizer.speakWithGeminiVoice(
                text = text,
                apiKey = apiKey,
                voice = _selectedVoice.value
            )
        }
    }

    fun stopSpeaking() {
        geminiVoiceSynthesizer.stop()
        audioPlayer.stopAndFlush()
    }

    fun toggleAutoSpeak() {
        _autoSpeakResponses.value = !_autoSpeakResponses.value
        if (!_autoSpeakResponses.value) {
            geminiVoiceSynthesizer.stop()
        }
    }

    fun sendTextMessage(text: String) {
        if (!isApiKeyConfigured()) {
            _showMissingApiKeyDialog.value = true
            return
        }
        val apiKey = getEffectiveApiKey()
        liveService.sendTextMessage(text, apiKey)
    }

    fun toggleMuteMic() {
        _isMuted.value = !_isMuted.value
        if (_isMuted.value) {
            audioRecorder.stopRecording()
        } else if (liveState.value == LiveState.LISTENING) {
            audioRecorder.startRecording(
                onAudioChunk = { pcmBase64 ->
                    liveService.sendRealtimeAudioChunk(pcmBase64)
                },
                onSilenceAfterSpeech = { wavBase64 ->
                    liveService.finishUserTurn(wavBase64)
                }
            )
        }
    }

    fun toggleMuteSpeaker() {
        val newMuted = !_isSpeakerMuted.value
        _isSpeakerMuted.value = newMuted
        audioPlayer.setMuted(newMuted)
        if (newMuted) {
            geminiVoiceSynthesizer.stop()
        }
    }

    fun selectVoice(voice: VoicePreset) {
        _selectedVoice.value = voice
        liveService.updateSettings(voice, _selectedPersona.value, getEffectiveApiKey())
    }

    fun selectPersona(persona: PersonaPreset) {
        _selectedPersona.value = persona
        liveService.updateSettings(_selectedVoice.value, persona, getEffectiveApiKey())
    }

    fun openSettingsDialog() {
        _showSettingsDialog.value = true
    }

    fun closeSettingsDialog() {
        _showSettingsDialog.value = false
    }

    fun toggleTranscriptSheet() {
        _showTranscriptSheet.value = !_showTranscriptSheet.value
    }

    fun finishUserTurn() {
        liveService.finishUserTurn()
    }

    fun playAudioMessage(pcmBase64: String) {
        audioPlayer.playPcmBase64(pcmBase64)
    }

    fun testVoiceAudio() {
        // Immediate local voice test: play chime + speak Nepali greeting using Gemini AI voice
        liveService.playTestTone()
        val apiKey = getEffectiveApiKey()
        if (apiKey.isNotBlank()) {
            geminiVoiceSynthesizer.speakWithGeminiVoice(
                text = "नमस्ते साथी! म नेक्सस एआई हुँ। तपाईंको आवाज प्रणाली एकदमै राम्रोसँग काम गरिरहेको छ नि!",
                apiKey = apiKey,
                voice = _selectedVoice.value
            )
            liveService.testVoiceAudio(apiKey)
        }
    }

    fun clearHistory() {
        liveService.clearHistory()
    }

    override fun onCleared() {
        super.onCleared()
        geminiVoiceSynthesizer.stop()
        liveService.release()
    }
}
