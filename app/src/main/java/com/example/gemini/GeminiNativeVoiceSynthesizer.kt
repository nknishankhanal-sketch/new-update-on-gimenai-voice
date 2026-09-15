package com.example.gemini

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.audio.AudioPlayer
import com.example.data.VoicePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * GeminiNativeVoiceSynthesizer
 *
 * Generates speech directly from Gemini AI models (gemini-2.5-flash-preview-tts,
 * gemini-2.5-flash-native-audio-preview-12-2025, gemini-2.0-flash) using Gemini's native voice personas
 * (Puck, Aoede, Kore, Fenrir, Charon) with raw 24kHz PCM audio playback, with automatic system TTS fallback.
 */
class GeminiNativeVoiceSynthesizer(
    private val audioPlayer: AudioPlayer,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "GeminiVoiceSynthesizer"
    }

    private val synthScope = CoroutineScope(Dispatchers.IO)
    private var currentSynthesizeJob: Job? = null
    private var androidTts: TextToSpeech? = null
    private var isTtsInitialized = false

    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()

    fun initContext(context: Context) {
        if (androidTts == null) {
            try {
                androidTts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        isTtsInitialized = true
                        val nepaliLocale = Locale("ne", "NP")
                        val result = androidTts?.setLanguage(nepaliLocale)
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            androidTts?.language = Locale.getDefault()
                        }
                        androidTts?.setPitch(1.0f)
                        androidTts?.setSpeechRate(1.0f)
                        Log.d(TAG, "Android TextToSpeech initialized successfully")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize Android TextToSpeech: ${e.message}")
            }
        }
    }

    /**
     * Cleans markdown and formatting so Gemini reads pure conversational text
     */
    private fun sanitizeTextForSpeech(text: String): String {
        return text
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("\\*(.*?)\\*"), "$1")
            .replace(Regex("`{1,3}.*?`{1,3}"), "")
            .replace(Regex("#+\\s*"), "")
            .replace(Regex("^[-*•]\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .replace(Regex("[_~>]"), " ")
            .replace("🔊 [Speaking live voice response...]", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Synthesizes text directly using Gemini AI native voice and streams the PCM audio
     * directly into the AudioPlayer.
     */
    fun speakWithGeminiVoice(
        text: String,
        apiKey: String,
        voice: VoicePreset = VoicePreset.PUCK,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val cleanText = sanitizeTextForSpeech(text)
        if (cleanText.isBlank()) {
            onComplete?.invoke(false)
            return
        }

        currentSynthesizeJob?.cancel()
        currentSynthesizeJob = synthScope.launch {
            _isSynthesizing.value = true
            try {
                // Models supporting direct Gemini speech generation
                val ttsModels = listOf(
                    "gemini-2.5-flash-preview-tts",
                    "gemini-2.5-flash-native-audio-preview-12-2025",
                    "gemini-flash-latest",
                    "gemini-2.0-flash"
                )

                val promptText = "Read the following naturally, warmly, and expressively in native voice:\n$cleanText"

                val jsonPayload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", promptText) })
                            })
                        })
                    })
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
                }

                val mediaType = "application/json".toMediaType()
                val requestBody = jsonPayload.toString().toRequestBody(mediaType)

                var pcmBase64Data: String? = null

                if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
                    // Fire all TTS models concurrently, use first successful response
                    val deferred = ttsModels.map { modelName ->
                        synthScope.async { tryTtsModel(modelName, cleanText, apiKey, voice, requestBody) }
                    }
                    
                    pcmBase64Data = deferred.awaitAll().firstOrNull { it != null }
                }

                if (pcmBase64Data != null) {
                    audioPlayer.playPcmBase64(pcmBase64Data)
                    onComplete?.invoke(true)
                } else if (androidTts != null && isTtsInitialized) {
                    Log.d(TAG, "Falling back to Android TextToSpeech engine")
                    androidTts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "gemini_voice_tts")
                    onComplete?.invoke(true)
                } else {
                    Log.w(TAG, "Unable to generate audio from Gemini or system TTS")
                    onComplete?.invoke(false)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in Gemini voice synthesis: ${e.message}", e)
                if (androidTts != null && isTtsInitialized) {
                    androidTts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "gemini_voice_tts")
                }
                onComplete?.invoke(false)
            } finally {
                _isSynthesizing.value = false
            }
        }
    }

    private suspend fun tryTtsModel(
        modelName: String,
        text: String,
        apiKey: String,
        voice: VoicePreset,
        requestBody: okhttp3.RequestBody
    ): String? = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        try {
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) return@withContext null
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) return@withContext null
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts == null) return@withContext null
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("inlineData")) {
                    val inlineData = part.getJSONObject("inlineData")
                    val data = inlineData.optString("data", "")
                    if (data.isNotBlank()) {
                        Log.d(TAG, "Successfully generated direct Gemini voice audio with model: $modelName")
                        return@withContext data
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "TTS request failed for $modelName: ${e.message}")
            null
        }
    }

    // Suspend functions for ConnectionManager
    suspend fun speakWithResult(
        text: String,
        apiKey: String,
        voice: VoicePreset
    ): ConnectionResult<Unit> = coroutineScope {
        val cleanText = sanitizeTextForSpeech(text)
        if (cleanText.isBlank()) return@coroutineScope ConnectionResult.Success(Unit)
        
        val ttsModels = listOf(
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-flash-latest",
            "gemini-2.0-flash"
        )
        
        val promptText = "Read the following naturally, warmly, and expressively in native voice:\n$cleanText"
        val jsonPayload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", promptText) })
                    })
                })
            })
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
        }
        val mediaType = "application/json".toMediaType()
        val requestBody = jsonPayload.toString().toRequestBody(mediaType)
        
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            // Use local TTS directly
            speakLocalWithResult(cleanText)
        } else {
            // Fire all TTS models concurrently
            val deferred = ttsModels.map { modelName ->
                async { tryTtsModel(modelName, cleanText, apiKey, voice, requestBody) }
            }
            
            val pcmData = deferred.awaitAll().firstOrNull { it != null }
            
            if (pcmData != null) {
                audioPlayer.playPcmBase64(pcmData)
                ConnectionResult.Success(Unit)
            } else if (isTtsInitialized) {
                speakLocalWithResult(cleanText)
            } else {
                ConnectionResult.Failure(
                    ConnectionError.Timeout("TTS"), 
                    FallbackAction.ShowErrorDialog
                )
            }
        }
    }

    suspend fun speakLocalWithResult(text: String): ConnectionResult<Unit> {
        return try {
            if (androidTts != null && isTtsInitialized) {
                androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "local_tts")
                ConnectionResult.Success(Unit)
            } else {
                ConnectionResult.Failure(ConnectionError.Unknown(Exception("No TTS available")), FallbackAction.ShowErrorDialog)
            }
        } catch (e: Exception) {
            ConnectionResult.Failure(ConnectionError.Unknown(e), FallbackAction.ShowErrorDialog)
        }
    }

    fun stop() {
        currentSynthesizeJob?.cancel()
        currentSynthesizeJob = null
        _isSynthesizing.value = false
        try {
            androidTts?.stop()
        } catch (_: Exception) {}
        audioPlayer.stopAndFlush()
    }

    fun release() {
        stop()
        try {
            androidTts?.shutdown()
        } catch (_: Exception) {}
        androidTts = null
    }
}
