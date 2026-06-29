package com.example.androidllm

import android.app.Application
import android.llama.cpp.LLamaAndroid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File

/**
 * Default model: Qwen3-4B at Q4_K_M — the recommended sweet spot for the OnePlus 13
 * (Snapdragon 8 Elite). ~2.5 GB download, expected ~25-30 tok/s decode.
 */
private const val DEFAULT_MODEL_URL =
    "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"
private const val MODEL_FILE_NAME = "qwen3-4b-q4_k_m.gguf"

data class ChatMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

enum class Phase { NEEDS_MODEL, DOWNLOADING, LOADING, READY }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val llama = LLamaAndroid.instance()
    private val modelFile: File
        get() = File(getApplication<Application>().filesDir, MODEL_FILE_NAME)

    var modelUrl by mutableStateOf(DEFAULT_MODEL_URL)
    var phase by mutableStateOf(Phase.NEEDS_MODEL)
        private set
    var statusLine by mutableStateOf("")
        private set
    var downloadProgress by mutableStateOf(0f) // 0f..1f, -1f = indeterminate
        private set

    /** Disable Qwen3 "thinking" for snappy, low-latency chat replies. */
    var disableThinking by mutableStateOf(true)

    var isGenerating by mutableStateOf(false)
        private set
    var lastTps by mutableStateOf<Double?>(null)
        private set

    val messages = mutableStateListOf<ChatMessage>()

    init {
        // If a valid model is already on disk, skip straight to loading it.
        if (Downloader.isValidGguf(modelFile)) {
            loadModel()
        } else {
            statusLine = "Model not downloaded yet (~2.5 GB)."
        }
    }

    fun downloadModel() {
        if (phase == Phase.DOWNLOADING || phase == Phase.LOADING) return
        phase = Phase.DOWNLOADING
        downloadProgress = -1f
        statusLine = "Starting download…"
        viewModelScope.launch {
            Downloader.download(modelUrl, modelFile)
                .catch { e ->
                    phase = Phase.NEEDS_MODEL
                    statusLine = "Download error: ${e.message}"
                }
                .collect { p ->
                    when (p) {
                        is Downloader.Progress.Downloading -> {
                            downloadProgress =
                                if (p.totalBytes > 0) p.bytesSoFar.toFloat() / p.totalBytes else -1f
                            statusLine = "Downloading ${formatBytes(p.bytesSoFar)}" +
                                    if (p.totalBytes > 0) " / ${formatBytes(p.totalBytes)}" else ""
                        }
                        is Downloader.Progress.Done -> {
                            statusLine = "Download complete."
                            loadModel()
                        }
                        is Downloader.Progress.Failed -> {
                            phase = Phase.NEEDS_MODEL
                            statusLine = p.message
                        }
                    }
                }
        }
    }

    private fun loadModel() {
        phase = Phase.LOADING
        statusLine = "Loading model into memory…"
        viewModelScope.launch {
            try {
                llama.load(modelFile.absolutePath)
                phase = Phase.READY
                statusLine = "Ready. Qwen3-4B Q4_K_M loaded."
            } catch (e: Exception) {
                phase = Phase.NEEDS_MODEL
                statusLine = "Failed to load model: ${e.message}"
            }
        }
    }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || isGenerating || phase != Phase.READY) return

        messages.add(ChatMessage(ChatMessage.Role.USER, text))
        val assistantIndex = messages.size
        messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, ""))

        val prompt = buildChatMlPrompt()
        isGenerating = true
        lastTps = null

        viewModelScope.launch {
            val start = System.nanoTime()
            var tokenCount = 0
            val sb = StringBuilder()
            llama.send(prompt, formatChat = true)
                .catch { e ->
                    messages[assistantIndex] =
                        ChatMessage(ChatMessage.Role.ASSISTANT, "[error: ${e.message}]")
                }
                .collect { piece ->
                    tokenCount++
                    sb.append(piece)
                    messages[assistantIndex] =
                        ChatMessage(ChatMessage.Role.ASSISTANT, cleanResponse(sb.toString()))
                }
            val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
            if (elapsedSec > 0 && tokenCount > 0) {
                lastTps = tokenCount / elapsedSec
            }
            isGenerating = false
        }
    }

    fun clearChat() {
        if (isGenerating) return
        messages.clear()
        lastTps = null
    }

    /**
     * Qwen3 emits a (possibly empty) <think>…</think> block before the answer. Hide a
     * leading think block so the chat bubble shows only the user-facing reply. While the
     * block is still streaming (no closing tag yet) we show a placeholder.
     */
    private val thinkBlock = Regex("(?s)^\\s*<think>.*?</think>\\s*")

    private fun cleanResponse(raw: String): String {
        val trimmed = raw.trimStart()
        if (thinkBlock.containsMatchIn(trimmed)) {
            return thinkBlock.replace(trimmed, "").trimStart()
        }
        if (trimmed.startsWith("<think>")) {
            // Closing tag not streamed yet — don't reveal the raw thinking.
            return "…"
        }
        return trimmed
    }

    /**
     * Build a Qwen3 ChatML prompt from the running conversation. The trailing
     * assistant turn (currently being generated) is excluded from the history.
     */
    private fun buildChatMlPrompt(): String {
        val sb = StringBuilder()
        val system = "You are a helpful, concise assistant." +
                if (disableThinking) " /no_think" else ""
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")

        // All but the last (empty) assistant placeholder.
        for (i in 0 until messages.size - 1) {
            val m = messages[i]
            val role = if (m.role == ChatMessage.Role.USER) "user" else "assistant"
            sb.append("<|im_start|>").append(role).append("\n")
                .append(m.text).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { llama.unload() }
    }
}
