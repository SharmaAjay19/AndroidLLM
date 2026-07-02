package com.example.androidllm

import android.app.Application
import android.content.pm.PackageManager
import android.llama.cpp.LLamaAndroid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidllm.data.ChatDatabase
import com.example.androidllm.data.ChatEntity
import com.example.androidllm.data.ChatListItem
import com.example.androidllm.data.MessageEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Default model: Qwen3-4B at Q4_K_M — the recommended sweet spot for the OnePlus 13
 * (Snapdragon 8 Elite). ~2.5 GB download, expected ~25-30 tok/s decode.
 */
private const val DEFAULT_MODEL_URL =
    "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"

/** Cap on tokens generated per turn. */
private const val MAX_NEW_TOKENS = 512

/** Re-prefill (instead of incremental) when the cache is within this many tokens of the limit. */
private const val CONTEXT_HEADROOM = MAX_NEW_TOKENS + 64

/** When re-prefilling a conversation, only replay this many of the most recent messages. */
private const val MAX_PREFILL_MESSAGES = 20

/** Max tool calls the model may make while answering a single user message. */
private const val MAX_TOOL_CALLS = 5

/** Whisper (voice input) model — base multilingual, ~142 MB. */
private const val WHISPER_MODEL_URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
private const val WHISPER_MODEL_FILE = "ggml-base.bin"

enum class Phase { NEEDS_MODEL, DOWNLOADING, LOADING, READY }

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val llama = LLamaAndroid.instance()
    private val dao = ChatDatabase.get(app).chatDao()
    private val browser = Browser(app)
    private val asr = com.example.whisper.WhisperAsr()
    private val recorder = AudioRecorder()

    // ---- Model lifecycle state ----
    var modelUrl by mutableStateOf(DEFAULT_MODEL_URL)
    var phase by mutableStateOf(Phase.NEEDS_MODEL)
        private set
    var statusLine by mutableStateOf("")
        private set
    var downloadProgress by mutableStateOf(0f) // 0f..1f, -1f = indeterminate
        private set

    /** Whether we can store the model in a location that survives app uninstall. */
    var hasPersistentStorage by mutableStateOf(ModelStorage.hasAllFilesAccess())
        private set

    // ---- Voice input (speech-to-text via whisper.cpp) ----
    enum class VoicePhase { IDLE, DOWNLOADING, LOADING, READY }

    var voicePhase by mutableStateOf(VoicePhase.IDLE)
        private set
    var isRecording by mutableStateOf(false)
        private set
    var isTranscribing by mutableStateOf(false)
        private set
    var voiceStatus by mutableStateOf("")
        private set

    private val whisperModelFile: File
        get() = File(getApplication<Application>().filesDir, WHISPER_MODEL_FILE)

    init {
        // If the whisper model is already downloaded, load it in the background.
        if (Downloader.isValidWhisper(whisperModelFile)) loadVoiceModel()
    }

    private fun loadVoiceModel() {
        if (voicePhase == VoicePhase.LOADING || voicePhase == VoicePhase.READY) return
        voicePhase = VoicePhase.LOADING
        voiceStatus = "Loading voice model…"
        viewModelScope.launch {
            try {
                asr.load(whisperModelFile.absolutePath)
                voicePhase = VoicePhase.READY
                voiceStatus = ""
            } catch (e: Exception) {
                voicePhase = VoicePhase.IDLE
                voiceStatus = "Voice model failed to load: ${e.message}"
            }
        }
    }

    /** Ensure the whisper model is downloaded + loaded. Safe to call repeatedly. */
    fun ensureVoiceModel() {
        if (voicePhase == VoicePhase.READY || voicePhase == VoicePhase.DOWNLOADING ||
            voicePhase == VoicePhase.LOADING
        ) return
        if (Downloader.isValidWhisper(whisperModelFile)) {
            loadVoiceModel()
            return
        }
        voicePhase = VoicePhase.DOWNLOADING
        voiceStatus = "Downloading voice model…"
        viewModelScope.launch {
            Downloader.download(WHISPER_MODEL_URL, whisperModelFile, Downloader::isValidWhisper)
                .catch { e ->
                    voicePhase = VoicePhase.IDLE
                    voiceStatus = "Voice model download error: ${e.message}"
                }
                .collect { p ->
                    when (p) {
                        is Downloader.Progress.Downloading ->
                            voiceStatus = "Downloading voice model… " +
                                if (p.totalBytes > 0)
                                    "${p.bytesSoFar * 100 / p.totalBytes}%" else ""
                        is Downloader.Progress.Done -> loadVoiceModel()
                        is Downloader.Progress.Failed -> {
                            voicePhase = VoicePhase.IDLE
                            voiceStatus = p.message
                        }
                    }
                }
        }
    }

    /** Begin capturing microphone audio (caller must already hold RECORD_AUDIO). */
    fun startRecording() {
        if (isRecording || isTranscribing) return
        if (voicePhase != VoicePhase.READY) {
            ensureVoiceModel()
            voiceStatus = "Preparing voice model — try again in a moment."
            return
        }
        recorder.start()
        isRecording = true
        voiceStatus = "Listening…"
    }

    /**
     * Stop recording, transcribe, and deliver the text via [onText] (on the main thread).
     */
    fun stopRecordingAndTranscribe(onText: (String) -> Unit) {
        if (!isRecording) return
        isRecording = false
        isTranscribing = true
        voiceStatus = "Transcribing…"
        viewModelScope.launch {
            try {
                val samples = recorder.stop()
                if (samples.size < AudioRecorder.SAMPLE_RATE / 2) {
                    voiceStatus = "Too short — hold the mic and speak."
                } else {
                    val text = asr.transcribe(samples, language = voiceLanguage)
                    if (text.isNotBlank()) onText(text)
                    voiceStatus = ""
                }
            } catch (e: Exception) {
                voiceStatus = "Transcription failed: ${e.message}"
            } finally {
                isTranscribing = false
            }
        }
    }

    fun cancelRecording() {
        if (!isRecording) return
        recorder.cancel()
        isRecording = false
        voiceStatus = ""
    }

    /** ISO language code for transcription; "auto" detects automatically. */
    var voiceLanguage: String = "en"

    // ---- Settings / workspace ----
    var showSettings by mutableStateOf(false)
    var workspacePathInput by mutableStateOf("")

    /** Absolute path of the folder where the agent reads/writes files by default. */
    val workspaceDir: String
        get() = Workspace.dir(getApplication()).absolutePath

    /** Suggested folders for the settings picker (only usable with All-files access). */
    val workspacePresets: List<Pair<String, String>>
        get() {
            val root = android.os.Environment.getExternalStorageDirectory()
            return listOf(
                "AndroidLLM" to java.io.File(root, "AndroidLLM/files").absolutePath,
                "Download" to java.io.File(root, "Download/AndroidLLM").absolutePath,
                "Documents" to java.io.File(root, "Documents/AndroidLLM").absolutePath,
                "App storage (private)" to java.io.File(
                    getApplication<Application>().filesDir, "workspace"
                ).absolutePath,
            )
        }

    fun openSettings() {
        workspacePathInput = Settings.getWorkspacePath(getApplication()) ?: workspaceDir
        showSettings = true
    }

    fun closeSettings() {
        showSettings = false
    }

    /** Save the workspace folder. Blank/default resets to the built-in default. */
    fun applyWorkspacePath(path: String) {
        val trimmed = path.trim()
        val default = Workspace.defaultDir(getApplication()).absolutePath
        Settings.setWorkspacePath(
            getApplication(),
            if (trimmed.isEmpty() || trimmed == default) null else trimmed
        )
        // Create it now so it's visible immediately.
        runCatching { Workspace.dir(getApplication()) }
        workspacePathInput = Settings.getWorkspacePath(getApplication()) ?: workspaceDir
    }

    /** Disable Qwen3 "thinking" for snappy, low-latency chat replies. */
    var disableThinking by mutableStateOf(true)

    /** When on, the model can call file tools (read/write/list) — agent mode. */
    var toolsEnabled by mutableStateOf(true)

    /** A file the user picked to send with the next message (saved into the workspace). */
    var pendingUpload by mutableStateOf<String?>(null)
        private set

    // ---- Share-to-assistant ----
    /** Content received from the system share sheet / "Process text", awaiting an action. */
    var pendingShare by mutableStateOf<ShareRouting.Content?>(null)
        private set

    /** One-shot prefill for the chat input box (consumed by the "Ask…" share action). */
    var draftInput by mutableStateOf<String?>(null)

    // ---- Phone-native tools: permission + confirmation gates ----
    data class PermissionRequest(
        val permissions: List<String>,
        val deferred: CompletableDeferred<Boolean>,
    )

    data class ConfirmRequest(
        val title: String,
        val detail: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    /** Set when a phone tool needs permissions; the UI launches the request and reports back. */
    var permissionRequest by mutableStateOf<PermissionRequest?>(null)
        private set

    /** Set when a state-changing phone tool needs the user to confirm before running. */
    var confirmRequest by mutableStateOf<ConfirmRequest?>(null)
        private set

    fun onPermissionResult(granted: Boolean) {
        permissionRequest?.deferred?.complete(granted)
        permissionRequest = null
    }

    fun resolveConfirm(approved: Boolean) {
        confirmRequest?.deferred?.complete(approved)
        confirmRequest = null
    }

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

    // Guards against concurrent/duplicate model-load attempts (init + onResume).
    private var initializing = false

    init {
        tryLoadExistingOrPrompt()
    }

    private fun tryLoadExistingOrPrompt() {
        if (phase == Phase.LOADING || phase == Phase.READY || phase == Phase.DOWNLOADING) return
        // The native model is a process-wide singleton. If a previous activity/VM instance
        // already loaded it (e.g. the app was open when a share intent spawned a new
        // instance), just reflect that as READY instead of showing the download screen.
        if (llama.loaded) {
            phase = Phase.READY
            statusLine = "Ready."
            return
        }
        if (initializing) return
        initializing = true
        viewModelScope.launch {
            try {
                // Migrate any internal-only copy to external if we just gained access.
                withContext(Dispatchers.IO) { ModelStorage.migrateToExternalIfPossible(getApplication()) }
                val existing = ModelStorage.existingModel(getApplication())
                if (existing != null) {
                    loadModel(existing)
                } else {
                    phase = Phase.NEEDS_MODEL
                    statusLine = "Model not downloaded yet (~2.5 GB)."
                }
            } finally {
                initializing = false
            }
        }
    }

    /** Re-check storage permission (e.g. after returning from the settings screen). */
    fun refreshStorage() {
        hasPersistentStorage = ModelStorage.hasAllFilesAccess()
        if (phase == Phase.NEEDS_MODEL) tryLoadExistingOrPrompt()
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
        val target = ModelStorage.downloadTarget(getApplication())
        viewModelScope.launch {
            Downloader.download(modelUrl, target)
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
                            loadModel(p.file)
                        }
                        is Downloader.Progress.Failed -> {
                            phase = Phase.NEEDS_MODEL
                            statusLine = p.message
                        }
                    }
                }
        }
    }

    private fun loadModel(file: File) {
        if (phase == Phase.LOADING || phase == Phase.READY) return
        phase = Phase.LOADING
        statusLine = "Loading model into memory…"
        viewModelScope.launch {
            try {
                llama.load(file.absolutePath)
                phase = Phase.READY
                val where = if (file.absolutePath.contains("/Android/") ||
                    file.absolutePath.startsWith(getApplication<Application>().filesDir.absolutePath)
                ) "internal storage" else "/sdcard/AndroidLLM"
                statusLine = "Ready. Model loaded from $where."
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

            // Fold a pending uploaded file into the user message so the model knows about it.
            val upload = pendingUpload
            pendingUpload = null
            val userContent = if (upload != null) {
                "[The user uploaded a file saved in your workspace as \"$upload\". " +
                    "Use read_file with path \"$upload\" to read it if relevant.]\n\n$text"
            } else text

            dao.insertMessage(
                MessageEntity(chatId = chatId, role = "user", content = userContent, createdAt = now)
            )
            dao.touchChat(chatId, now)

            // Decide whether we can reuse the KV cache (incremental) or must re-prefill.
            val ctxTokens = llama.contextTokens()
            val nearLimit = ctxTokens > llama.maxContext - CONTEXT_HEADROOM
            val incremental = loadedChatId == chatId && ctxTokens > 0 && !nearLimit

            var feed: String = if (incremental) {
                buildDeltaPrompt(userContent)
            } else {
                llama.resetSession()
                val history = dao.messagesOnce(chatId).takeLast(MAX_PREFILL_MESSAGES)
                buildFullPrompt(history)
            }
            loadedChatId = chatId

            val start = System.nanoTime()
            var tokenCount = 0

            // Agent loop: generate, and if the model emits a tool call, run it and feed
            // the result back, continuing from the KV cache, until it answers in plain text.
            var toolRounds = 0
            while (true) {
                val assistantId = dao.insertMessage(
                    MessageEntity(
                        chatId = chatId, role = "assistant", content = "",
                        createdAt = System.currentTimeMillis()
                    )
                )
                streamingId = assistantId
                streamingText = ""

                val sb = StringBuilder()
                llama.generate(feed, formatChat = true, maxNewTokens = MAX_NEW_TOKENS)
                    .catch { e -> streamingText = "[error: ${e.message}]" }
                    .collect { piece ->
                        tokenCount++
                        sb.append(piece)
                        streamingText = cleanResponse(sb.toString())
                    }

                val output = sb.toString()
                val cleaned = cleanResponse(output)
                val call = if (toolsEnabled) Tools.parseToolCall(cleaned) else null

                if (call != null && toolRounds < MAX_TOOL_CALLS) {
                    toolRounds++
                    // Persist the raw tool-call output (UI renders it as a tool step).
                    dao.updateMessageContent(assistantId, output.trim())
                    streamingId = null
                    streamingText = ""

                    val result = when (call.name) {
                        "web_search" -> browser.search(call.args.optString("query"))
                        "fetch_url" -> browser.fetch(
                            call.args.optString("url"),
                            call.args.optInt("offset", 0)
                        )
                        in PhoneTools.names -> runPhoneTool(call)
                        else -> withContext(Dispatchers.IO) {
                            Tools.execute(getApplication(), call)
                        }
                    }
                    val resultText = result.output
                    dao.insertMessage(
                        MessageEntity(
                            chatId = chatId, role = "tool", content = resultText,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    dao.touchChat(chatId, System.currentTimeMillis())

                    feed = buildToolResultPrompt(resultText)
                    continue
                }

                // Plain answer (or tool budget exhausted): finalize and stop.
                dao.updateMessageContent(assistantId, cleaned.ifBlank { "(no response)" })
                dao.touchChat(chatId, System.currentTimeMillis())
                streamingId = null
                streamingText = ""
                break
            }

            val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
            if (elapsedSec > 0 && tokenCount > 0) lastTps = tokenCount / elapsedSec
            isGenerating = false
        }
    }

    /** Save a picked file into the workspace so the agent can read it; remember its name. */
    fun attachFile(uri: android.net.Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { copyIntoWorkspace(uri) }
            pendingUpload = name
        }
    }

    /** Copy a content URI into the workspace, returning the saved file name (or null). */
    private fun copyIntoWorkspace(uri: android.net.Uri): String? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val display = queryDisplayName(uri) ?: "upload_${System.currentTimeMillis()}.txt"
            val dest = Workspace.resolve(getApplication(), display)
                ?: File(Workspace.dir(getApplication()), "upload.txt")
            resolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest.name
        } catch (_: Exception) {
            null
        }
    }

    fun clearUpload() {
        pendingUpload = null
    }

    // ---- Share-to-assistant handling ----

    /** Handle an incoming share/process-text intent: stash content for a quick action. */
    fun handleShare(text: String?, uri: android.net.Uri?) {
        if (uri != null) {
            viewModelScope.launch {
                val name = withContext(Dispatchers.IO) { copyIntoWorkspace(uri) }
                pendingShare = when {
                    name != null -> ShareRouting.Content(ShareRouting.Kind.FILE, name, name)
                    !text.isNullOrBlank() -> ShareRouting.classifyText(text)
                    else -> null
                }
            }
        } else if (!text.isNullOrBlank()) {
            pendingShare = ShareRouting.classifyText(text)
        }
    }

    fun dismissShare() {
        pendingShare = null
    }

    /** Run a quick action on the pending shared content in a fresh chat. */
    fun runSharedAction(action: ShareRouting.Action) {
        val content = pendingShare ?: return
        if (phase != Phase.READY || isGenerating) return
        pendingShare = null
        newChat()
        if (content.kind == ShareRouting.Kind.FILE) pendingUpload = content.payload

        if (action == ShareRouting.Action.ASK) {
            // Seed the input box with context and let the user type their question.
            draftInput = when (content.kind) {
                ShareRouting.Kind.TEXT, ShareRouting.Kind.URL -> content.payload + "\n\n"
                ShareRouting.Kind.FILE -> ""
            }
            return
        }
        send(ShareRouting.buildPrompt(action, content))
    }

    // ---- Phone-native tool execution (permission + confirmation gated) ----

    private suspend fun runPhoneTool(call: ToolCall): ToolResult {
        val perms = PhoneTools.requiredPermissions(call.name)
        if (perms.isNotEmpty() && !ensurePermissions(perms)) {
            return ToolResult(
                false,
                "Permission not granted for ${call.name}. Tell the user which permission is " +
                    "needed and that they can grant it and try again."
            )
        }
        if (PhoneTools.isWrite(call.name)) {
            val (title, detail) = PhoneTools.confirmText(call)
            if (!confirm(title, detail)) {
                return ToolResult(false, "The user declined. Do not retry unless asked.")
            }
        }
        // Clipboard read and draft launches touch the UI; run those on the main thread.
        val onMain = call.name == "read_clipboard" || call.name == "compose_message"
        val dispatcher = if (onMain) Dispatchers.Main else Dispatchers.IO
        return withContext(dispatcher) { PhoneTools.execute(getApplication(), call) }
    }

    /** Suspend until the required [permissions] are granted (or denied) by the user. */
    private suspend fun ensurePermissions(permissions: List<String>): Boolean {
        val ctx = getApplication<Application>()
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        val deferred = CompletableDeferred<Boolean>()
        permissionRequest = PermissionRequest(missing, deferred)
        return deferred.await()
    }

    /** Suspend until the user approves or declines a state-changing action. */
    private suspend fun confirm(title: String, detail: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmRequest = ConfirmRequest(title, detail, deferred)
        return deferred.await()
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }

    private fun titleFrom(text: String): String {
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length <= 40) firstLine else firstLine.take(40).trimEnd() + "…"
    }

    /**
     * The assistant turn header. When "fast mode" is on we use the official Qwen3
     * non-thinking form: pre-seed an empty <think></think> block so the model starts
     * generating the answer directly instead of having to emit the block itself (which
     * small models often get wrong on later turns, producing empty replies).
     */
    private fun assistantHeader(): String =
        if (disableThinking) "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        else "<|im_start|>assistant\n"

    /**
     * Incremental ChatML for a follow-up turn. The cache already holds the prior turns up
     * to the previous assistant reply (without its closing tag), so we close it with
     * `<|im_end|>` and add only the new user turn plus the assistant header.
     */
    private fun buildDeltaPrompt(userText: String): String =
        "<|im_end|>\n" +
            "<|im_start|>user\n" + userText + "<|im_end|>\n" +
            assistantHeader()

    /**
     * Feed a tool's result back into the conversation as a user turn, continuing from the
     * KV cache (the assistant's tool-call output is already cached, unterminated).
     */
    private fun buildToolResultPrompt(result: String): String =
        "<|im_end|>\n" +
            "<|im_start|>user\nTOOL RESULT:\n" + result + "<|im_end|>\n" +
            assistantHeader()

    private fun systemPrompt(): String {
        val base = "You are a helpful, concise assistant."
        return if (toolsEnabled) {
            base + "\n\n" + Tools.systemInstructions +
                "\n\n" + PhoneTools.systemInstructions +
                "\n\n" + PhoneTools.nowLine()
        } else base
    }

    /** Build a full Qwen3 ChatML prompt from [history] (persisted user/assistant/tool turns). */
    private fun buildFullPrompt(history: List<MessageEntity>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(systemPrompt()).append("<|im_end|>\n")
        for (m in history) {
            if (m.role == "assistant" && m.content.isBlank()) continue
            when (m.role) {
                // Tool results are replayed as user turns so the model sees them as context.
                "tool" -> sb.append("<|im_start|>user\nTOOL RESULT:\n")
                    .append(m.content).append("<|im_end|>\n")
                else -> sb.append("<|im_start|>").append(m.role).append("\n")
                    .append(m.content).append("<|im_end|>\n")
            }
        }
        sb.append(assistantHeader())
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
        browser.destroy()
        recorder.cancel()
        viewModelScope.launch {
            llama.unload()
            asr.unload()
        }
    }
}
