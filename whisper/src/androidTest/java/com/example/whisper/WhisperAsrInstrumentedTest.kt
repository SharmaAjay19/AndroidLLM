package com.example.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

/**
 * On-device integration test for whisper.cpp speech-to-text. Downloads a tiny English model,
 * feeds the public-domain JFK sample WAV (bundled in test assets), and asserts the transcript
 * contains an expected phrase. Deterministic — no microphone needed. Requires network.
 */
@RunWith(AndroidJUnit4::class)
class WhisperAsrInstrumentedTest {

    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"

        private lateinit var modelFile: File

        @BeforeClass
        @JvmStatic
        fun downloadModel() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            modelFile = File(ctx.filesDir, "ggml-tiny.en.bin")
            if (modelFile.exists() && modelFile.length() > 10_000_000L) return
            URL(MODEL_URL).openStream().use { input ->
                modelFile.outputStream().use { input.copyTo(it) }
            }
        }
    }

    @Test
    fun transcribesJfkSample() = runBlocking {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val samples = testCtx.assets.open("jfk.wav").use { readWavAsFloat(it.readBytes()) }
        assertTrue("audio should be non-trivial", samples.size > 16_000)

        val asr = WhisperAsr()
        try {
            asr.load(modelFile.absolutePath)
            val text = asr.transcribe(samples, language = "en").lowercase()
            // JFK: "...ask not what your country can do for you..."
            assertTrue("transcript should mention 'country', got: $text", text.contains("country"))
        } finally {
            asr.unload()
        }
    }

    /** Parse a 16 kHz mono 16-bit PCM WAV into normalized floats (finds the data chunk). */
    private fun readWavAsFloat(bytes: ByteArray): FloatArray {
        // Locate the "data" chunk rather than assuming a fixed 44-byte header.
        var i = 12
        var dataOffset = 44
        var dataLen = bytes.size - 44
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = (bytes[i + 4].toInt() and 0xFF) or
                    ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                dataOffset = i + 8
                dataLen = size
                break
            }
            i += 8 + size + (size and 1)
        }
        val n = (dataLen / 2).coerceAtMost((bytes.size - dataOffset) / 2)
        val out = FloatArray(n)
        var j = dataOffset
        for (k in 0 until n) {
            val lo = bytes[j].toInt() and 0xFF
            val hi = bytes[j + 1].toInt()
            out[k] = (((hi shl 8) or lo) / 32768.0f)
            j += 2
        }
        return out
    }
}
