package com.example.androidllm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal dependency-free GGUF downloader.
 *
 * Supports HTTP range-resume so an interrupted multi-GB download can continue, and
 * emits coarse progress updates that the UI can render.
 */
object Downloader {

    sealed interface Progress {
        data class Downloading(val bytesSoFar: Long, val totalBytes: Long) : Progress
        data class Done(val file: File) : Progress
        data class Failed(val message: String) : Progress
    }

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian
    private const val GGML_MAGIC = 0x67676d6c // "ggml" little-endian (whisper.cpp models)

    fun isValidGguf(file: File): Boolean = hasMagic(file, GGUF_MAGIC)

    /** whisper.cpp ggml models start with "ggml"; newer ones may be GGUF. */
    fun isValidWhisper(file: File): Boolean =
        hasMagic(file, GGML_MAGIC) || hasMagic(file, GGUF_MAGIC)

    private fun hasMagic(file: File, magic: Int): Boolean {
        if (!file.exists() || file.length() < 8) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val b = ByteArray(4)
                raf.readFully(b)
                val m = (b[0].toInt() and 0xFF) or
                        ((b[1].toInt() and 0xFF) shl 8) or
                        ((b[2].toInt() and 0xFF) shl 16) or
                        ((b[3].toInt() and 0xFF) shl 24)
                m == magic
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Download [url] to [dest], resuming if a partial file already exists.
     * The download is written to a ".part" sibling and atomically renamed on success.
     * [validate] checks the finished file's format (defaults to GGUF).
     */
    fun download(
        url: String,
        dest: File,
        validate: (File) -> Boolean = ::isValidGguf
    ): Flow<Progress> = flow {
        val part = File(dest.parentFile, dest.name + ".part")
        var existing = if (part.exists()) part.length() else 0L

        var connection = openConnection(url, existing)

        // If the server ignored our Range request, restart from scratch.
        if (existing > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            part.delete()
            existing = 0
            connection.disconnect()
            connection = openConnection(url, 0)
        }

        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            emit(Progress.Failed("HTTP $code while fetching model"))
            connection.disconnect()
            return@flow
        }

        val contentLength = connection.contentLengthLong.let { if (it < 0) 0 else it }
        val total = if (existing > 0) existing + contentLength else contentLength

        connection.inputStream.use { input ->
            RandomAccessFile(part, "rw").use { out ->
                out.seek(existing)
                val buffer = ByteArray(1 shl 16) // 64 KiB
                var downloaded = existing
                var lastEmit = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    downloaded += read
                    // Throttle UI updates to ~ every 2 MiB.
                    if (downloaded - lastEmit >= (2L shl 20)) {
                        lastEmit = downloaded
                        emit(Progress.Downloading(downloaded, total))
                    }
                }
                emit(Progress.Downloading(downloaded, total))
            }
        }
        connection.disconnect()

        if (!validate(part)) {
            part.delete()
            emit(Progress.Failed("Downloaded file failed validation"))
            return@flow
        }

        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            emit(Progress.Failed("Could not finalize downloaded file"))
            return@flow
        }
        emit(Progress.Done(dest))
    }.flowOn(Dispatchers.IO)

    private fun openConnection(url: String, resumeFrom: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        connection.connect()
        return connection
    }
}
