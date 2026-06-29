package com.example.androidllm

import android.app.Application
import android.llama.cpp.LLamaAndroid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidllm.data.ChatDatabase
import com.example.androidllm.data.ChatEntity
import com.example.androidllm.data.ChatListItem
import com.example.androidllm.data.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Default model: Qwen3-4B at Q4_K_M — the recommended sweet spot for the OnePlus 13
 * (Snapdragon 8 Elite). ~2.5 GB download, expected ~25-30 tok/s decode.
 */
private const val DEFAULT_MODEL_URL =
    "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"
private const val MODEL_FILE_NAME = "qwen3-4b-q4_k_m.gguf"

/** Cap on tokens generated per turn. */
private const val MAX_NEW_TOKENS = 512

/** Re-prefill (instead of incremental) when the cache is within this many tokens of the limit. */
private const val CONTEXT_HEADROOM = MAX_NEW_TOKENS + 64

/** When re-prefilling a conversation, only replay this many of the most recent messages. */
private const val MAX_PREFILL_MESSAGES = 20

enum class Phase { NEEDS_MODEL, DOWNLOADING, LOADING, READY }

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val llama = LLamaAndroid.instance()
    private val dao = ChatDatabase.get(app).chatDao()

    private val modelFile: File
        get() = File(getApplication<Application>().filesDir, MODEL_FILE_NAME)

    // ---- Model lifecycle state ----
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

    // ---- Chat list + search ----
    private val searchQuery = MutableStateFlow("")
    var searchText by mutableStateOf("")
        private set

    val chats: StateFlow<List<ChatListItem>> =
        searchQuery
            .flatMapLatest { dao.observeChatList(it.trim()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Current chat ----
    private val currentChatId = MutableStateFlow<Long?>(null)
    val activeChatId: StateFlow<Long?> = currentChatId

    var currentTitle by mutableStateOf("New chat")
        private set

    /** Persisted message rows for the active chat. */
    val messages: StateFlow<List<MessageEntity>> =
        currentChatId
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeMessages(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Streaming overlay (Compose state so the UI recomposes as tokens arrive).
    var streamingId by mutableStateOf<Long?>(null)
        private set
    var streamingText by mutableStateOf("")
        private set

    // Which chat's turns currently live in the native KV cache (-1 / null = none).
    private var loadedChatId: Long? = null

    init {
        if (Downloader.isValidGguf(modelFile)) {
            loadModel()
        } else {
            statusLine = "Model not downloaded yet (~2.5 GB)."
        }
    }

    // ---- Chat management ----
    fun onSearchChange(text: String) {
        searchText = text
        searchQuery.value = text
    }

    fun newChat() {
        if (isGenerating) return
        currentChatId.value = null
        currentTitle = "New chat"
    }

    fun openChat(id: Long) {
        if (isGenerating) return
        currentChatId.value = id
        viewModelScope.launch {
            currentTitle = dao.getChat(id)?.title ?: "Chat"
        }
    }

    fun deleteChat(id: Long) {
        viewModelScope.launch {
            dao.deleteChat(id)
            if (currentChatId.value == id) newChat()
        }
    }

    // ---- Model download / load ----
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

    // ---- Sending / generation ----
    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || isGenerating || phase != Phase.READY) return

        isGenerating = true
        lastTps = null

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val chatId = currentChatId.value ?: run {
                val id = dao.insertChat(
                    ChatEntity(title = titleFrom(text), createdAt = now, updatedAt = now)
                )
                currentChatId.value = id
                currentTitle = titleFrom(text)
                id
            }

            dao.insertMessage(
                MessageEntity(chatId = chatId, role = "user", content = text, createdAt = now)
            )
            dao.touchChat(chatId, now)

            // Decide whether we can reuse the KV cache (incremental) or must re-prefill.
            // Re-prefill when: a different chat is loaded, the cache is empty, or we are
            // close enough to the context limit that this turn might overflow.
            val ctxTokens = llama.contextTokens()
            val nearLimit = ctxTokens > llama.maxContext - CONTEXT_HEADROOM
            val incremental = loadedChatId == chatId && ctxTokens > 0 && !nearLimit

            val prompt: String
            if (incremental) {
                // Only the new user turn needs decoding; prior turns stay in the cache.
                prompt = buildDeltaPrompt(text)
            } else {
                // Fresh start for this chat: clear the cache and prefill the recent history.
                llama.resetSession()
                val history = dao.messagesOnce(chatId).takeLast(MAX_PREFILL_MESSAGES)
                prompt = buildFullPrompt(history)
            }
            loadedChatId = chatId

            // Insert empty assistant row that we stream into.
            val assistantId = dao.insertMessage(
                MessageEntity(chatId = chatId, role = "assistant", content = "", createdAt = now + 1)
            )
            streamingId = assistantId
            streamingText = ""

            val start = System.nanoTime()
            var tokenCount = 0
            val sb = StringBuilder()
            llama.generate(prompt, formatChat = true, maxNewTokens = MAX_NEW_TOKENS)
                .catch { e -> streamingText = "[error: ${e.message}]" }
                .collect { piece ->
                    tokenCount++
                    sb.append(piece)
                    streamingText = cleanResponse(sb.toString())
                }

            val finalText = cleanResponse(sb.toString()).ifBlank { "(no response)" }
            dao.updateMessageContent(assistantId, finalText)
            dao.touchChat(chatId, System.currentTimeMillis())

            val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
            if (elapsedSec > 0 && tokenCount > 0) lastTps = tokenCount / elapsedSec

            streamingId = null
            streamingText = ""
            isGenerating = false
        }
    }

    private fun titleFrom(text: String): String {
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length <= 40) firstLine else firstLine.take(40).trimEnd() + "…"
    }

    /**
     * Incremental ChatML for a follow-up turn. The cache already holds the prior turns up
     * to the previous assistant reply (without its closing tag), so we close it with
     * `<|im_end|>` and add only the new user turn plus the assistant header.
     */
    private fun buildDeltaPrompt(userText: String): String =
        "<|im_end|>\n" +
            "<|im_start|>user\n" + userText + "<|im_end|>\n" +
            "<|im_start|>assistant\n"

    /** Build a full Qwen3 ChatML prompt from [history] (persisted user/assistant turns). */
    private fun buildFullPrompt(history: List<MessageEntity>): String {
        val sb = StringBuilder()
        val system = "You are a helpful, concise assistant." +
                if (disableThinking) " /no_think" else ""
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for (m in history) {
            if (m.role == "assistant" && m.content.isBlank()) continue
            sb.append("<|im_start|>").append(m.role).append("\n")
                .append(m.content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /**
     * Qwen3 emits a (possibly empty) <think>…</think> block before the answer. Hide a
     * leading think block so the chat bubble shows only the user-facing reply.
     */
    private val thinkBlock = Regex("(?s)^\\s*<think>.*?</think>\\s*")

    private fun cleanResponse(raw: String): String {
        val trimmed = raw.trimStart()
        if (thinkBlock.containsMatchIn(trimmed)) {
            return thinkBlock.replace(trimmed, "").trimStart()
        }
        if (trimmed.startsWith("<think>")) return "…"
        return trimmed
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
