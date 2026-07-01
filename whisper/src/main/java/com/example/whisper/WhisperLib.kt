package com.example.whisper

import android.util.Log

/** Low-level JNI bindings to whisper.cpp (see src/main/cpp/whisper-jni.c). */
internal class WhisperLib {
    companion object {
        init {
            System.loadLibrary("whisper-android")
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            language: String?
        ): String

        external fun getSystemInfo(): String
    }
}

private const val TAG = "WhisperAsr"
