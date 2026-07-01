package com.example.androidllm

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Captures microphone audio as 16 kHz mono PCM and returns it as normalized float samples,
 * which is exactly what whisper.cpp expects. Tap-to-talk: [start] then [stop].
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16_000
        // Cap a single utterance so we never allocate unbounded memory (~2 min).
        private const val MAX_SECONDS = 120
    }

    @Volatile private var recording = false
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    fun start() {
        if (recording) return
        recording = true
        pcm.reset()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        worker = thread(name = "AudioRecorder") {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            val maxBytes = MAX_SECONDS * SAMPLE_RATE * 2
            try {
                record.startRecording()
                val buf = ByteArray(minBuf)
                while (recording && pcm.size() < maxBytes) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) pcm.write(buf, 0, n)
                }
            } catch (_: Exception) {
                // swallow; stop() returns whatever was captured
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }
    }

    /** Stop recording and return the captured audio as 16 kHz mono float samples. */
    suspend fun stop(): FloatArray = withContext(Dispatchers.IO) {
        recording = false
        worker?.join(2000)
        worker = null
        pcm16ToFloat(pcm.toByteArray())
    }

    fun cancel() {
        recording = false
        worker?.join(1000)
        worker = null
        pcm.reset()
    }

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val n = bytes.size / 2
        val out = FloatArray(n)
        var j = 0
        for (i in 0 until n) {
            val lo = bytes[j].toInt() and 0xFF
            val hi = bytes[j + 1].toInt() // signed high byte
            val sample = (hi shl 8) or lo
            out[i] = (sample / 32768.0f).coerceIn(-1f, 1f)
            j += 2
        }
        return out
    }
}
