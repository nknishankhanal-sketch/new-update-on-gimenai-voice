package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * AudioPlayer
 *
 * Robust, low-latency streaming PCM audio player tailored for 24kHz 16-bit mono streams.
 * Prevents buffer starvation/underruns with adaptive jitter pre-buffering, resilient buffer sizing,
 * and comprehensive buffer health diagnostics.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        const val PLAYBACK_SAMPLE_RATE = 24000 // Standard 24kHz for Gemini AI audio models
        const val DEFAULT_SAMPLE_RATE = PLAYBACK_SAMPLE_RATE
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM

        // 24kHz * 2 bytes/sample * 1 channel = 48,000 bytes/sec
        // Jitter pre-buffering threshold (~60ms of audio = ~2880 bytes)
        private const val JITTER_PRE_BUFFER_BYTES = 2880
    }

    private var audioTrack: AudioTrack? = null
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _speakerVolume = MutableStateFlow(0f)
    val speakerVolume: StateFlow<Float> = _speakerVolume

    private val _isPlayingState = MutableStateFlow(false)
    val isPlayingState: StateFlow<Boolean> = _isPlayingState

    private var isMuted = false
    private var totalBytesStreamed: Long = 0L
    private var lastUnderrunCount: Int = 0

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) {
            audioTrack?.setVolume(0f)
        } else {
            audioTrack?.setVolume(1f)
        }
    }

    init {
        initAudioTrack(PLAYBACK_SAMPLE_RATE)
        startContinuousPlaybackLoop()
    }

    private fun initAudioTrack(sampleRate: Int) {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            // Allocate a robust buffer size (at least 500ms of audio = 24,000 bytes or 6x min buffer)
            val halfSecondBufferBytes = sampleRate * BYTES_PER_SAMPLE / 2
            val bufferSize = (minBufferSize * 6).coerceAtLeast(halfSecondBufferBytes)

            Log.d(TAG, "Initializing AudioTrack with sampleRate=$sampleRate Hz, minBufferSize=$minBufferSize bytes, targetBufferSize=$bufferSize bytes")

            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            audioTrack = trackBuilder.build()
            audioTrack?.setVolume(if (isMuted) 0f else 1f)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                lastUnderrunCount = audioTrack?.underrunCount ?: 0
            }

            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized and transitioned to streaming mode (playState=${audioTrack?.playState})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    fun playPcmBase64(base64Audio: String) {
        try {
            val rawBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            // Strip 44-byte WAV header if response is packaged in a WAV container format
            val pcmBytes = if (rawBytes.size > 44 &&
                rawBytes[0] == 'R'.code.toByte() &&
                rawBytes[1] == 'I'.code.toByte() &&
                rawBytes[2] == 'F'.code.toByte() &&
                rawBytes[3] == 'F'.code.toByte()) {
                rawBytes.copyOfRange(44, rawBytes.size)
            } else {
                rawBytes
            }
            enqueuePcmBytes(pcmBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64 audio: ${e.message}", e)
        }
    }

    fun enqueuePcmBytes(pcmBytes: ByteArray) {
        if (isMuted || pcmBytes.isEmpty()) return
        audioChannel.trySend(pcmBytes)
    }

    private fun startContinuousPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive) {
                try {
                    // Suspend waiting for next voice chunk
                    val firstChunk = audioChannel.receive()
                    _isPlayingState.value = true

                    val track = ensureAudioTrack()
                    if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track?.play()
                    }

                    // Jitter pre-buffering: accumulate initial chunks (~120ms) to ensure continuous streaming without underrun
                    var accumulatedBytes = firstChunk.size
                    val preBufferChunks = mutableListOf(firstChunk)
                    while (accumulatedBytes < JITTER_PRE_BUFFER_BYTES) {
                        val immediateNext = audioChannel.tryReceive().getOrNull()
                        if (immediateNext != null) {
                            preBufferChunks.add(immediateNext)
                            accumulatedBytes += immediateNext.size
                        } else {
                            break
                        }
                    }

                    for (chunk in preBufferChunks) {
                        processAndWrite(track, chunk)
                    }

                    // Process stream chunks continuously
                    while (isActive) {
                        val nextChunk = audioChannel.tryReceive().getOrNull()
                        if (nextChunk != null) {
                            processAndWrite(track, nextChunk)
                        } else {
                            // Debounce wait to smooth over network packet jitter (reduced from 120ms)
                            delay(30)
                            val jitterChunk = audioChannel.tryReceive().getOrNull()
                            if (jitterChunk != null) {
                                processAndWrite(track, jitterChunk)
                            } else {
                                // Buffer drained completely
                                logBufferHealth(track, isStreamComplete = true)
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.e(TAG, "Playback loop error: ${e.message}", e)
                    }
                } finally {
                    _speakerVolume.value = 0f
                    _isPlayingState.value = false
                }
            }
        }
    }

    private fun processAndWrite(track: AudioTrack?, chunk: ByteArray) {
        if (track == null || chunk.isEmpty()) return
        val rms = calculateRms(chunk)
        _speakerVolume.value = (rms / 32768f * 5f).coerceIn(0f, 1f)

        val bytesWritten = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
        } else {
            track.write(chunk, 0, chunk.size)
        }

        if (bytesWritten > 0) {
            totalBytesStreamed += bytesWritten
        } else {
            Log.w(TAG, "AudioTrack write returned non-positive status code: $bytesWritten")
        }

        // Monitor underruns on supported API levels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && totalBytesStreamed % (PLAYBACK_SAMPLE_RATE * BYTES_PER_SAMPLE) < chunk.size) {
            logBufferHealth(track, isStreamComplete = false)
        }
    }

    private fun logBufferHealth(track: AudioTrack?, isStreamComplete: Boolean) {
        if (track == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val currentUnderruns = track.underrunCount
            val deltaUnderruns = currentUnderruns - lastUnderrunCount
            lastUnderrunCount = currentUnderruns

            if (deltaUnderruns > 0) {
                Log.w(TAG, "⚠️ Buffer Warning: $deltaUnderruns audio underruns detected! Total underruns=$currentUnderruns, totalBytesStreamed=$totalBytesStreamed")
            } else if (isStreamComplete) {
                Log.d(TAG, "✅ Buffer Health Optimal: Stream finished with 0 new underruns (totalStreamed=$totalBytesStreamed bytes, totalUnderruns=$currentUnderruns)")
            }
        } else if (isStreamComplete) {
            Log.d(TAG, "Stream finished (totalStreamed=$totalBytesStreamed bytes)")
        }
    }

    private fun ensureAudioTrack(): AudioTrack? {
        if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            initAudioTrack(DEFAULT_SAMPLE_RATE)
        }
        return audioTrack
    }

    fun stopAndFlush() {
        // Drain any pending items in channel
        while (audioChannel.tryReceive().isSuccess) { }

        _isPlayingState.value = false
        _speakerVolume.value = 0f
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            Log.d(TAG, "AudioTrack flushed and ready for streaming")
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack: ${e.message}", e)
        }
    }

    fun release() {
        stopAndFlush()
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.release()
            Log.d(TAG, "AudioTrack released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack: ${e.message}", e)
        } finally {
            audioTrack = null
        }
    }

    private fun calculateRms(buffer: ByteArray): Float {
        var sum = 0.0
        val shorts = buffer.size / 2
        for (i in 0 until shorts) {
            val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sum += shortSample * shortSample
        }
        return if (shorts > 0) sqrt(sum / shorts).toFloat() else 0f
    }
}

