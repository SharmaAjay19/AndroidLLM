package com.example.androidllm

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device OCR via ML Kit Text Recognition (bundled Latin model, no network). Used to turn
 * shared screenshots/images into indexable text for Ambient Memory.
 */
object Ocr {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Extract text from the image at [uri]; returns "" on failure or no text. */
    suspend fun extract(context: Context, uri: Uri): String =
        suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromFilePath(context, uri)
                recognizer.process(image)
                    .addOnSuccessListener { result -> cont.resume(result.text.trim()) }
                    .addOnFailureListener { cont.resume("") }
            } catch (_: Exception) {
                cont.resume("")
            }
        }
}
