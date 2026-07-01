package com.example.whisper

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * High-level offline speech-to-text using whisper.cpp.
 *
 * All native calls run on a single dedicated thread (a whisper_context is not thread-safe).
 * Feed 16 kHz mono float PCM (range -1..1) to [transcribe].
 */
class WhisperAsr {

    private var contextPtr: Long = 0L

    private val runLoop: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "Whisper-RunLoop") }
            .asCoroutineDispatcher()

    val isLoaded: Boolean get() = contextPtr != 0L

    /** Number of threads for decoding; defaults to a sensible value for the device. */
    var numThreads: Int =
        maxOf(1, minOf(8, Runtime.getRuntime().availableProcessors() - 2))

    suspend fun load(modelPath: String) {
        withContext(runLoop) {
            if (contextPtr != 0L) {
                WhisperLib.freeContext(contextPtr)
                contextPtr = 0L
            }
            val ptr = WhisperLib.initContext(modelPath)
            if (ptr == 0L) throw IllegalStateException("Failed to load whisper model at $modelPath")
            contextPtr = ptr
            Log.i("WhisperAsr", "Loaded whisper model. " + WhisperLib.getSystemInfo())
        }
    }

    /**
     * Transcribe [samples] (16 kHz mono float PCM). [language] is an ISO code like "en",
     * or null/"auto" to auto-detect. Returns the trimmed transcript.
     */
    suspend fun transcribe(samples: FloatArray, language: String? = "en"): String =
        withContext(runLoop) {
            check(contextPtr != 0L) { "Model not loaded" }
            WhisperLib.fullTranscribe(contextPtr, numThreads, samples, language).trim()
        }

    suspend fun unload() {
        withContext(runLoop) {
            if (contextPtr != 0L) {
                WhisperLib.freeContext(contextPtr)
                contextPtr = 0L
            }
        }
    }
}
