package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.sqrt

class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // 100ms at 16kHz, 16-bit mono = 1600 samples = 3200 bytes
        const val TARGET_CHUNK_SIZE = 3200
        private const val NOISE_GATE_THRESHOLD = 0.003f

        // VAD Constants (Responsive speech detection)
        private const val VAD_SPEECH_ONSET_THRESHOLD = 0.012f
        private const val VAD_SPEECH_CONTINUE_THRESHOLD = 0.006f
        private const val VAD_BARGE_IN_THRESHOLD = 0.08f
        private const val MIN_SPEECH_DURATION_MS = 200L
        private const val SILENCE_TIMEOUT_MS = 800L
        private const val PRE_ROLL_FRAME_COUNT = 4 // 400ms pre-roll buffer
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: android.media.audiofx.AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _micVolume = MutableStateFlow(0f)
    val micVolume: StateFlow<Float> = _micVolume

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isUserSpeaking = MutableStateFlow(false)
    val isUserSpeaking: StateFlow<Boolean> = _isUserSpeaking

    // When AI is speaking, we apply suppression to prevent echo while detecting intentional barge-in
    private var isAiSpeakingSuppressed = false
    private var onBargeInCallback: (() -> Unit)? = null

    // VAD State Machine
    private var hasActiveSpeech = false
    private var speechCandidateStartTimeMs = 0L
    private var isConfirmedSpeech = false
    private var silenceStartTimeMs = 0L
    private val preRollBuffer = ArrayDeque<ByteArray>()
    private val turnSpeechPcmAccumulator = ByteArrayOutputStream()

    fun setAiSpeakingSuppression(suppressed: Boolean, onBargeIn: (() -> Unit)? = null) {
        isAiSpeakingSuppressed = suppressed
        onBargeInCallback = onBargeIn
        if (suppressed) {
            resetVadState()
        }
    }

    fun resetVadState() {
        hasActiveSpeech = false
        speechCandidateStartTimeMs = 0L
        isConfirmedSpeech = false
        silenceStartTimeMs = 0L
        _isUserSpeaking.value = false
        synchronized(preRollBuffer) {
            preRollBuffer.clear()
        }
        synchronized(turnSpeechPcmAccumulator) {
            turnSpeechPcmAccumulator.reset()
        }
    }

    fun getAndResetTurnSpeechWavBase64(): String? {
        val pcmBytes: ByteArray
        synchronized(turnSpeechPcmAccumulator) {
            if (turnSpeechPcmAccumulator.size() < 3200) { // less than 100ms
                return null
            }
            pcmBytes = turnSpeechPcmAccumulator.toByteArray()
            turnSpeechPcmAccumulator.reset()
        }
        val wavBytes = createWav(pcmBytes, SAMPLE_RATE)
        return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        onAudioChunk: (String) -> Unit,
        onSilenceAfterSpeech: ((wavBase64: String?) -> Unit)? = null,
        onBargeInDetected: (() -> Unit)? = null
    ) {
        if (_isRecording.value) return
        onBargeInCallback = onBargeInDetected

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val bufferSize = if (minBufferSize > 0) {
            (minBufferSize * 2).coerceAtLeast(4096)
        } else {
            6400
        }

        try {
            var rec: AudioRecord? = null
            // Primary audio sources to attempt
            val sources = intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )

            for (src in sources) {
                try {
                    val candidate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        try {
                            AudioRecord.Builder()
                                .setAudioSource(src)
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setEncoding(AUDIO_FORMAT)
                                        .setSampleRate(SAMPLE_RATE)
                                        .setChannelMask(CHANNEL_CONFIG)
                                        .build()
                                )
                                .setBufferSizeInBytes(bufferSize)
                                .build()
                        } catch (_: Throwable) {
                            AudioRecord(src, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                        }
                    } else {
                        AudioRecord(src, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                    }

                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        rec = candidate
                        Log.d(TAG, "Successfully initialized AudioRecord with source: $src, session: ${candidate.audioSessionId}")
                        break
                    } else {
                        candidate.release()
                    }
                } catch (_: Throwable) {
                    // AudioRecord instantiation failed on this source
                }
            }

            if (rec == null) {
                Log.w(TAG, "Physical AudioRecord unavailable (virtual/emulator environment). Running simulated audio fallback.")
                _isRecording.value = true
                resetVadState()

                recordingJob = scope.launch {
                    val simulatedChunk = ByteArray(TARGET_CHUNK_SIZE)
                    while (isActive && _isRecording.value) {
                        kotlinx.coroutines.delay(100)
                        if (!isAiSpeakingSuppressed) {
                            val base64Data = Base64.encodeToString(simulatedChunk, Base64.NO_WRAP)
                            onAudioChunk(base64Data)
                        }
                    }
                }
                return
            }

            // Enable Hardware APUs (AcousticEchoCanceler, NoiseSuppressor, AutomaticGainControl)
            val sessionId = rec.audioSessionId
            if (sessionId != 0) {
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                        Log.d(TAG, "Hardware AcousticEchoCanceler enabled")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not enable AcousticEchoCanceler: ${e.message}")
                }

                try {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                        Log.d(TAG, "Hardware NoiseSuppressor enabled")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not enable NoiseSuppressor: ${e.message}")
                }

                try {
                    if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                        gainControl = android.media.audiofx.AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                        Log.d(TAG, "Hardware AutomaticGainControl enabled")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not enable AutomaticGainControl: ${e.message}")
                }
            }

            audioRecord = rec
            try {
                if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.startRecording()
                    Log.d(TAG, "AudioRecord started recording successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioRecord: ${e.message}")
            }
            _isRecording.value = true
            resetVadState()

            recordingJob = scope.launch {
                val readBuffer = ByteArray(1600)
                val frameAccumulator = ByteArrayOutputStream(TARGET_CHUNK_SIZE)

                while (isActive && _isRecording.value) {
                    val bytesRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                    if (bytesRead > 0) {
                        // Calculate RMS for visualizer feedback & VAD
                        val rms = calculateRms(readBuffer, bytesRead)
                        val rawNormalized = (rms / 32768f * 6f)
                        val gatedVolume = if (rawNormalized < NOISE_GATE_THRESHOLD) 0f else rawNormalized.coerceIn(0f, 1f)
                        _micVolume.value = gatedVolume

                        // Check Echo Suppression & Barge-in VAD: If AI is actively speaking, detect user voice energy to trigger instant interruption
                        if (isAiSpeakingSuppressed) {
                            if (gatedVolume < VAD_BARGE_IN_THRESHOLD) {
                                frameAccumulator.reset()
                                resetVadState()
                                continue
                            } else {
                                Log.d(TAG, "VAD Barge-in detected! Energy: $gatedVolume")
                                onBargeInCallback?.invoke()
                                isAiSpeakingSuppressed = false
                            }
                        }

                        frameAccumulator.write(readBuffer, 0, bytesRead)

                        // When we have accumulated at least TARGET_CHUNK_SIZE (100ms of audio), process VAD & transmit frame
                        if (frameAccumulator.size() >= TARGET_CHUNK_SIZE) {
                            val chunkBytes = frameAccumulator.toByteArray()
                            frameAccumulator.reset()

                            val frameRms = calculateRms(chunkBytes, chunkBytes.size)
                            val frameVolume = (frameRms / 32768f * 6f).coerceIn(0f, 1f)

                            // Process VAD State Machine
                            processVadFrame(
                                frameBytes = chunkBytes,
                                frameVolume = frameVolume,
                                onAudioChunk = onAudioChunk,
                                onSilenceAfterSpeech = onSilenceAfterSpeech
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}", e)
            stopRecording()
        }
    }

    private fun processVadFrame(
        frameBytes: ByteArray,
        frameVolume: Float,
        onAudioChunk: (String) -> Unit,
        onSilenceAfterSpeech: ((wavBase64: String?) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        val isVoiceActive = if (isConfirmedSpeech) {
            frameVolume >= VAD_SPEECH_CONTINUE_THRESHOLD
        } else {
            frameVolume >= VAD_SPEECH_ONSET_THRESHOLD
        }

        if (isVoiceActive) {
            silenceStartTimeMs = 0L // Reset silence timer

            if (!hasActiveSpeech) {
                // Speech onset candidate
                hasActiveSpeech = true
                speechCandidateStartTimeMs = now
            } else if (!isConfirmedSpeech && (now - speechCandidateStartTimeMs >= MIN_SPEECH_DURATION_MS)) {
                // Confirmed human speech (persisted > MIN_SPEECH_DURATION_MS)
                isConfirmedSpeech = true
                _isUserSpeaking.value = true
                Log.d(TAG, "VAD: User speech confirmed")

                // Flush pre-roll buffer so beginning of first syllable is preserved
                synchronized(preRollBuffer) {
                    while (!preRollBuffer.isEmpty()) {
                        val preRollFrame = preRollBuffer.poll()
                        if (preRollFrame != null) {
                            synchronized(turnSpeechPcmAccumulator) {
                                turnSpeechPcmAccumulator.write(preRollFrame)
                            }
                            val base64PreRoll = Base64.encodeToString(preRollFrame, Base64.NO_WRAP)
                            onAudioChunk(base64PreRoll)
                        }
                    }
                }
            }

            if (isConfirmedSpeech) {
                synchronized(turnSpeechPcmAccumulator) {
                    turnSpeechPcmAccumulator.write(frameBytes)
                }
            }

            // Always forward the current audio frame
            val base64Data = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
            onAudioChunk(base64Data)

        } else {
            // Silence / Background Noise frame
            if (isConfirmedSpeech) {
                // Forward the silence frame to Gemini so it hears the pause naturally
                synchronized(turnSpeechPcmAccumulator) {
                    turnSpeechPcmAccumulator.write(frameBytes)
                }
                val base64Data = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
                onAudioChunk(base64Data)

                if (silenceStartTimeMs == 0L) {
                    silenceStartTimeMs = now
                } else if (now - silenceStartTimeMs >= SILENCE_TIMEOUT_MS) {
                    // SILENCE DETECTED AFTER SPEECH -> Signal turn completion with recorded audio
                    val totalSpeechDuration = now - speechCandidateStartTimeMs
                    Log.d(TAG, "VAD: Silence detected after ${totalSpeechDuration}ms speech. Signaling turn completion.")
                    
                    val wavBase64 = getAndResetTurnSpeechWavBase64()
                    resetVadState()
                    onSilenceAfterSpeech?.invoke(wavBase64)
                }
            } else {
                // Not in active speech - maintain rolling pre-roll buffer (300ms)
                synchronized(preRollBuffer) {
                    if (preRollBuffer.size >= PRE_ROLL_FRAME_COUNT) {
                        preRollBuffer.poll()
                    }
                    preRollBuffer.offer(frameBytes)
                }

                // If candidate speech was too short (click/noise), reset it
                if (hasActiveSpeech && (now - speechCandidateStartTimeMs < MIN_SPEECH_DURATION_MS)) {
                    hasActiveSpeech = false
                    speechCandidateStartTimeMs = 0L
                }

                // Also forward ambient frame so Gemini has continuous connection stream
                val base64Data = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
                onAudioChunk(base64Data)
            }
        }
    }

    private fun createWav(pcmBytes: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0 // PCM
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmBytes.size and 0xff).toByte()
        header[41] = ((pcmBytes.size shr 8) and 0xff).toByte()
        header[42] = ((pcmBytes.size shr 16) and 0xff).toByte()
        header[43] = ((pcmBytes.size shr 24) and 0xff).toByte()

        return header + pcmBytes
    }

    fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        resetVadState()

        try {
            echoCanceler?.release()
        } catch (_: Exception) {}
        echoCanceler = null

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null

        try {
            gainControl?.release()
        } catch (_: Exception) {}
        gainControl = null

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audioRecord: ${e.message}")
        } finally {
            audioRecord = null
            _micVolume.value = 0f
        }
    }

    private fun calculateRms(buffer: ByteArray, readSize: Int): Float {
        var sum = 0.0
        val shorts = readSize / 2
        for (i in 0 until shorts) {
            val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sum += shortSample * shortSample
        }
        return if (shorts > 0) sqrt(sum / shorts).toFloat() else 0f
    }
}
