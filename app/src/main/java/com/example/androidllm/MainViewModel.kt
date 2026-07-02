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
import com.example.androidllm.data.ScheduleEntity
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

/** Whisper (voice input) model — base multilingual, ~142 MB. */
private const val WHISPER_MODEL_URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
private const val WHISPER_MODEL_FILE = "ggml-base.bin"

enum class Phase { NEEDS_MODEL, DOWNLOADING, LOADING, READY }

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val llama = LLamaAndroid.instance()
    private val engine = ChatEngine.get(app)
    private val docIndex = DocIndex.get(app)
    private val memory = MemoryStore.get(app)
    private val dao = ChatDatabase.get(app).chatDao()
    private val scheduleDao = ChatDatabase.get(app).scheduleDao()
    private val docDao = ChatDatabase.get(app).docDao()
    private val memoryDao = ChatDatabase.get(app).memoryDao()
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
    /** "Chat options" sheet holding the per-session Fast/Tools engine toggles. */
    var showChatOptions by mutableStateOf(false)
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

    // ---- Scheduled prompts ----
    var showSchedules by mutableStateOf(false)

    val schedules: StateFlow<List<ScheduleEntity>> =
        scheduleDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openSchedules() { showSchedules = true }
    fun closeSchedules() { showSchedules = false }

    /** Create or update a schedule, then (re)arm its alarm. */
    fun saveSchedule(
        existing: ScheduleEntity?,
        name: String,
        prompt: String,
        hour: Int,
        minute: Int,
        daysMask: Int,
        toolsEnabled: Boolean,
    ) {
        val cleanName = name.trim().ifEmpty { "Briefing" }
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return
        viewModelScope.launch {
            val base = (existing ?: ScheduleEntity(name = cleanName, prompt = cleanPrompt, hour = hour, minute = minute))
                .copy(
                    name = cleanName, prompt = cleanPrompt, hour = hour, minute = minute,
                    daysMask = daysMask, toolsEnabled = toolsEnabled, enabled = true
                )
            val id = if (existing == null) scheduleDao.insert(base) else { scheduleDao.update(base); base.id }
            val saved = scheduleDao.getById(id) ?: base.copy(id = id)
            val next = ScheduleAlarms.arm(getApplication(), saved)
            scheduleDao.updateRunTimes(id, saved.lastRunAt, next)
        }
    }

    fun toggleSchedule(schedule: ScheduleEntity, enabled: Boolean) {
        viewModelScope.launch {
            scheduleDao.setEnabled(schedule.id, enabled)
            if (enabled) {
                val next = ScheduleAlarms.arm(getApplication(), schedule.copy(enabled = true))
                scheduleDao.updateRunTimes(schedule.id, schedule.lastRunAt, next)
            } else {
                ScheduleAlarms.cancel(getApplication(), schedule.id)
                scheduleDao.updateRunTimes(schedule.id, schedule.lastRunAt, null)
            }
        }
    }

    fun deleteSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            ScheduleAlarms.cancel(getApplication(), schedule.id)
            scheduleDao.delete(schedule)
        }
    }

    /** Open a chat by id (used by the briefing notification deep link). */
    fun openChatById(id: Long) {
        currentChatId.value = id
        viewModelScope.launch { currentTitle = dao.getChat(id)?.title ?: "Chat" }
    }

    // ---- Documents / on-device RAG ----
    enum class DocPhase { IDLE, DOWNLOADING, LOADING, INDEXING, READY }

    var showDocuments by mutableStateOf(false)
    var docPhase by mutableStateOf(if (docIndex.isEmbedderDownloaded) DocPhase.READY else DocPhase.IDLE)
        private set
    var docStatus by mutableStateOf("")
        private set

    val docFileCount: StateFlow<Int> =
        docDao.observeFileCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val docChunkCount: StateFlow<Int> =
        docDao.observeChunkCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The indexed documents, for the Documents screen's file list. */
    val docFiles: StateFlow<List<com.example.androidllm.data.DocFileInfo>> =
        docDao.observeFiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Remove a single document from the index (its workspace file is left untouched). */
    fun removeDocument(path: String) {
        viewModelScope.launch {
            docDao.deleteByPath(path)
            if (docChunkCount.value <= 0) ragEnabled = false
            docStatus = "Removed \"$path\" from the library."
        }
    }
    fun openDocuments() { showDocuments = true }
    fun closeDocuments() { showDocuments = false }

    /** Download (if needed) and load the embedding model, then reflect readiness. */
    fun ensureEmbedder(then: (() -> Unit)? = null) {
        if (docPhase == DocPhase.DOWNLOADING || docPhase == DocPhase.LOADING || docPhase == DocPhase.INDEXING) return
        if (docIndex.isEmbedderDownloaded) {
            docPhase = DocPhase.LOADING
            docStatus = "Loading embedder…"
            viewModelScope.launch {
                val ok = docIndex.ensureEmbedderLoaded()
                docPhase = if (ok) DocPhase.READY else DocPhase.IDLE
                docStatus = if (ok) "" else "Failed to load embedder."
                if (ok) then?.invoke()
            }
            return
        }
        docPhase = DocPhase.DOWNLOADING
        docStatus = "Downloading embedder…"
        viewModelScope.launch {
            Downloader.download(
                DocIndex.EMBED_MODEL_URL,
                File(getApplication<Application>().filesDir, DocIndex.EMBED_MODEL_FILE)
            ).catch { e ->
                docPhase = DocPhase.IDLE
                docStatus = "Embedder download error: ${e.message}"
            }.collect { p ->
                when (p) {
                    is Downloader.Progress.Downloading ->
                        docStatus = "Downloading embedder… " +
                            if (p.totalBytes > 0) "${p.bytesSoFar * 100 / p.totalBytes}%" else ""
                    is Downloader.Progress.Done -> {
                        docPhase = DocPhase.LOADING
                        docStatus = "Loading embedder…"
                        val ok = docIndex.ensureEmbedderLoaded()
                        docPhase = if (ok) DocPhase.READY else DocPhase.IDLE
                        docStatus = if (ok) "" else "Failed to load embedder."
                        if (ok) then?.invoke()
                    }
                    is Downloader.Progress.Failed -> {
                        docPhase = DocPhase.IDLE
                        docStatus = p.message
                    }
                }
            }
        }
    }

    /** Index (or reindex) the workspace documents. Downloads/loads the embedder first if needed. */
    fun indexDocuments(force: Boolean = false) {
        if (docPhase == DocPhase.INDEXING) return
        if (!docIndex.isEmbedderDownloaded) {
            ensureEmbedder { indexDocuments(force) }
            return
        }
        docPhase = DocPhase.INDEXING
        docStatus = "Indexing…"
        viewModelScope.launch {
            val r = docIndex.indexWorkspace(force = force) { docStatus = it }
            docPhase = DocPhase.READY
            docStatus = "Indexed ${r.files} file(s), ${r.chunks} chunk(s)" +
                (if (r.skipped > 0) ", ${r.skipped} unchanged" else "") +
                (if (r.errors > 0) ", ${r.errors} error(s)" else "") + "."
            // Auto-enable RAG once there's something to search.
            if (r.chunks > 0 || docChunkCount.value > 0) ragEnabled = true
        }
    }

    fun clearDocuments() {
        viewModelScope.launch {
            docIndex.clearIndex()
            ragEnabled = false
            docStatus = "Cleared the document index."
        }
    }

    // Coalesces auto-index passes so several files shared in quick succession do one pass.
    private var autoIndexJob: kotlinx.coroutines.Job? = null

    /**
     * Opportunistically index the workspace after a file lands in it (share/save), so it's
     * searchable via the document-RAG path without a manual "Index / update documents" tap.
     * No-op (deferred) when the embedder isn't downloaded — never triggers a surprise download.
     * Incremental (mtime-skip) so it's cheap; debounced and never blocks chat/generation.
     */
    fun autoIndexWorkspace() {
        if (!docIndex.isEmbedderDownloaded) return
        if (docPhase == DocPhase.INDEXING) return
        autoIndexJob?.cancel()
        autoIndexJob = viewModelScope.launch {
            kotlinx.coroutines.delay(600) // debounce bursts of shares
            docPhase = DocPhase.INDEXING
            docStatus = "Indexing shared file…"
            val r = docIndex.indexWorkspace(force = false) { docStatus = it }
            docPhase = DocPhase.READY
            if (r.chunks > 0) {
                docStatus = "Indexed ${r.files} shared file(s), ${r.chunks} snippet(s)."
                ragEnabled = true
            } else if (docChunkCount.value > 0) {
                ragEnabled = true
            }
        }
    }

    // ---- Ambient Memory ("second brain") ----
    var showMemories by mutableStateOf(false)
    var memorySearch by mutableStateOf("")
    var memoryStatus by mutableStateOf("")
        private set

    private val memorySearchQuery = MutableStateFlow("")

    val memoryCount: StateFlow<Int> =
        memoryDao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val memories: StateFlow<List<com.example.androidllm.data.MemoryListItem>> =
        memorySearchQuery
            .flatMapLatest { q -> memoryDao.observeMemories(q) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openMemories() { showMemories = true }
    fun closeMemories() { showMemories = false }

    fun onMemorySearchChange(text: String) {
        memorySearch = text
        memorySearchQuery.value = text.trim()
    }

    /** Save arbitrary text (or a URL/voice transcript) to memory. */
    fun saveMemory(text: String, type: String = "text", uri: String? = null, sourceApp: String? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        memoryStatus = "Saving…"
        viewModelScope.launch {
            val id = memory.captureText(clean, type = type, uri = uri, sourceApp = sourceApp)
            memoryStatus = if (id > 0) "Saved to memory." else
                "Couldn't save — the embedder isn't ready. Open Documents to download it."
        }
    }

    /** OCR an image and save the extracted text to memory. */
    fun saveImageMemory(uri: android.net.Uri, sourceApp: String? = null) {
        memoryStatus = "Reading image…"
        viewModelScope.launch {
            val id = memory.captureImage(uri, sourceApp)
            memoryStatus = if (id > 0) "Saved screenshot text to memory." else
                "Couldn't read text from that image."
        }
    }

    /** Save the current clipboard contents to memory (foreground clipboard read). */
    fun saveClipboardToMemory() {
        val cm = getApplication<Application>()
            .getSystemService(Application.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(getApplication()).toString() else ""
        if (text.isBlank()) { memoryStatus = "Clipboard is empty."; return }
        saveMemory(text, type = "text", sourceApp = "clipboard")
    }

    fun deleteMemory(id: Long) { viewModelScope.launch { memory.delete(id) } }
    fun togglePinMemory(id: Long, pinned: Boolean) { viewModelScope.launch { memory.setPinned(id, pinned) } }
    fun clearMemories() {
        viewModelScope.launch { memory.clear(); memoryStatus = "Cleared all memories." }
    }

    /** Export all memories as Markdown into the workspace; returns nothing (status updated). */
    fun exportMemories() {
        viewModelScope.launch {
            val md = memory.exportMarkdown()
            val ok = withContext(Dispatchers.IO) {
                try {
                    val f = File(Workspace.dir(getApplication()), "memories-export.md")
                    f.writeText(md)
                    f.absolutePath
                } catch (_: Exception) { null }
            }
            memoryStatus = if (ok != null) "Exported to $ok" else "Export failed."
        }
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

    /** When on (and documents are indexed), the model can call search_documents. */
    var ragEnabled by mutableStateOf(false)

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

    /** Handle to the in-flight generation coroutine, so it can be cancelled (Stop button). */
    private var generationJob: kotlinx.coroutines.Job? = null

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

    // Snapshot of the most recently deleted chat, for one-tap Undo from a snackbar.
    private var lastDeletedChat: Pair<ChatEntity, List<MessageEntity>>? = null

    /** Delete a chat but snapshot it first so the UI can offer Undo. */
    fun deleteChatWithUndo(id: Long) {
        viewModelScope.launch {
            val chat = dao.getChat(id) ?: return@launch
            val msgs = dao.messagesOnce(id)
            lastDeletedChat = chat to msgs
            dao.deleteChat(id)
            if (currentChatId.value == id) newChat()
        }
    }

    /** Restore the chat removed by the last [deleteChatWithUndo]. */
    fun undoDeleteChat() {
        val (chat, msgs) = lastDeletedChat ?: return
        lastDeletedChat = null
        viewModelScope.launch {
            val newId = dao.insertChat(chat.copy(id = 0))
            msgs.forEach { dao.insertMessage(it.copy(id = 0, chatId = newId)) }
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

        generationJob = viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val chatId = currentChatId.value ?: run {
                    val id = dao.insertChat(
                        ChatEntity(title = ChatEngine.titleFrom(text), createdAt = now, updatedAt = now)
                    )
                    currentChatId.value = id
                    currentTitle = ChatEngine.titleFrom(text)
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

                val sink = object : ChatEngine.StreamSink {
                    override fun onAssistantStart(messageId: Long) {
                        streamingId = messageId
                        streamingText = ""
                    }
                    override fun onDelta(t: String) { streamingText = t }
                    override fun onAssistantEnd() {
                        streamingId = null
                        streamingText = ""
                    }
                }

                val result = engine.run(
                    chatId = chatId,
                    userContent = userContent,
                    config = ChatEngine.Config(
                        toolsEnabled = toolsEnabled,
                        disableThinking = disableThinking,
                        ragEnabled = ragEnabled && docIndex.isEmbedderDownloaded,
                        memoryEnabled = memoryCount.value > 0 && docIndex.isEmbedderDownloaded,
                    ),
                    dispatch = ::dispatchTool,
                    sink = sink,
                )
                result.tps?.let { lastTps = it }
            } finally {
                // Runs on normal completion AND on cancellation (Stop). Reset UI to idle.
                isGenerating = false
                streamingId = null
                streamingText = ""
            }
        }
    }

    /** Stop the currently running agent turn (Stop button). Partial output is preserved. */
    fun stopGenerating() {
        generationJob?.cancel()
    }

    /** Route a tool call from the interactive UI (web async, phone gated, file on IO). */
    private suspend fun dispatchTool(call: ToolCall): ToolResult = when (call.name) {
        "web_search" -> browser.search(call.args.optString("query"))
        "fetch_url" -> browser.fetch(call.args.optString("url"), call.args.optInt("offset", 0))
        in PhoneTools.names -> runPhoneTool(call)
        in RagTools.names -> runSearchDocuments(call)
        in MemoryTools.names -> runSearchMemory(call)
        else -> withContext(Dispatchers.IO) { Tools.execute(getApplication(), call) }
    }

    /** Execute search_memory: hybrid recall over saved memories, formatted with sources. */
    private suspend fun runSearchMemory(call: ToolCall): ToolResult {
        val query = call.args.optString("query").trim()
        if (query.isEmpty()) return ToolResult(false, "Provide a query to search memories.")
        val k = call.args.optInt("k", 4).coerceIn(1, 8)
        val hits = memory.recall(query, k)
        if (hits.isEmpty()) return ToolResult(true, "No saved memories matched that query.")
        val body = hits.joinToString("\n\n") { h ->
            "[${h.title}] (score ${"%.2f".format(h.score)})\n${h.text}"
        }
        return ToolResult(true, "Top ${hits.size} memories:\n\n$body")
    }

    /** Execute search_documents: embed the query, rank indexed chunks, format snippets. */
    private suspend fun runSearchDocuments(call: ToolCall): ToolResult {
        val query = call.args.optString("query").trim()
        if (query.isEmpty()) return ToolResult(false, "Provide a query to search documents.")
        val k = call.args.optInt("k", 4).coerceIn(1, 8)
        val hits = docIndex.search(query, k)
        if (hits.isEmpty()) {
            return ToolResult(
                true,
                "No indexed documents matched. The user may need to index documents first " +
                    "(Documents screen)."
            )
        }
        val body = hits.joinToString("\n\n") { s ->
            "[${s.path} #${s.ord}] (score ${"%.2f".format(s.score)})\n${s.text}"
        }
        return ToolResult(true, "Top ${hits.size} passages:\n\n$body")
    }

    /** Save a picked file into the workspace so the agent can read it; remember its name. */
    fun attachFile(uri: android.net.Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { copyIntoWorkspace(uri) }
            pendingUpload = name
            if (name != null) autoIndexWorkspace()
        }
    }

    /** Copy a content URI into the workspace, returning the saved file name (or null). */
    private fun copyIntoWorkspace(uri: android.net.Uri): String? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val display = queryDisplayName(uri) ?: "upload_${System.currentTimeMillis()}.txt"
            val dest = Workspace.resolve(getApplication(), display)
                ?: File(Workspace.dir(getApplication()), "upload.txt")

            // If the shared file already IS the destination (the user shared a file that lives
            // in the workspace), a naive copy would open the same file for writing — truncating
            // it to 0 bytes before it's read — and destroy the user's data. Skip the copy.
            val srcPath = runCatching { sourceFilePath(uri) }.getOrNull()
            if (srcPath != null && srcPath == dest.canonicalPath) {
                return dest.name
            }

            // Write to a temporary sibling first, then move into place. This reads the source
            // fully before replacing the destination, so it's safe even when a content provider
            // backs the same underlying file, and never leaves a partially-written dest.
            val tmp = File(dest.parentFile, dest.name + ".part-" + System.currentTimeMillis())
            val copied = resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (copied == null) { tmp.delete(); return null }

            // Never replace a non-empty existing file with an empty copy (defensive).
            if (tmp.length() == 0L && dest.exists() && dest.length() > 0L) {
                tmp.delete()
                return dest.name
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest.name
        } catch (_: Exception) {
            null
        }
    }

    /** The real filesystem path a [uri] points at, when resolvable (else null). */
    private fun sourceFilePath(uri: android.net.Uri): String? {
        if (uri.scheme == "file") return uri.path?.let { File(it).canonicalPath }
        // MediaStore / documents providers may expose a _data column with the real path.
        return try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { File(it).canonicalPath } else null
                }
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
                // A file just landed in the workspace — make it searchable via document RAG
                // without requiring a manual "Index / update documents" tap.
                if (name != null) autoIndexWorkspace()
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

        if (action == ShareRouting.Action.SAVE_MEMORY) {
            // Save the shared item to the second brain instead of chatting about it.
            pendingShare = null
            when (content.kind) {
                ShareRouting.Kind.FILE -> {
                    // Shared files are already copied into the workspace; save their text.
                    val file = File(Workspace.dir(getApplication()), content.payload)
                    viewModelScope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching { file.readText() }.getOrDefault("")
                        }
                        if (text.isBlank()) memoryStatus = "Couldn't read the shared file."
                        else saveMemory(text, type = "text", uri = content.payload, sourceApp = "share")
                    }
                }
                ShareRouting.Kind.URL ->
                    saveMemory(content.payload, type = "url", uri = content.payload, sourceApp = "share")
                ShareRouting.Kind.TEXT ->
                    saveMemory(content.payload, type = "text", sourceApp = "share")
            }
            return
        }

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
