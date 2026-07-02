package com.example.androidllm

import android.content.Context
import android.llama.cpp.LLamaAndroid
import com.example.androidllm.data.ChatDatabase
import com.example.androidllm.data.ChatEntity
import com.example.androidllm.data.ChatDao
import com.example.androidllm.data.MessageEntity
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cap on tokens generated per turn. */
private const val MAX_NEW_TOKENS = 512

/** Re-prefill (instead of incremental) when the cache is within this many tokens of the limit. */
private const val CONTEXT_HEADROOM = MAX_NEW_TOKENS + 64

/** When re-prefilling a conversation, only replay this many of the most recent messages. */
private const val MAX_PREFILL_MESSAGES = 20

/** Max tool calls the model may make while answering a single user message. */
private const val MAX_TOOL_CALLS = 5

/**
 * Headless chat/agent engine shared by the interactive UI ([MainViewModel]) and background
 * workers (scheduled briefings). Owns the process-wide native model session: model loading,
 * KV-cache reuse, prompt building, the tool-calling agent loop, and message persistence.
 *
 * A single [Mutex] serializes generation so the UI and a background worker never run against
 * the one native context at the same time.
 */
class ChatEngine private constructor(private val dao: ChatDao) {

    private val llama = LLamaAndroid.instance()
    private val mutex = Mutex()

    /** Which chat's turns currently live in the native KV cache (null = none). */
    @Volatile
    private var loadedChatId: Long? = null

    val isModelLoaded: Boolean get() = llama.loaded

    /** Config for a single generation. */
    data class Config(
        val toolsEnabled: Boolean,
        val disableThinking: Boolean,
        val ragEnabled: Boolean = false,
        val memoryEnabled: Boolean = false,
    )

    /** Result of a generation turn. */
    data class Result(val finalText: String, val tps: Double?)

    /** Streaming callbacks so the UI can mirror generation; all no-ops for headless runs. */
    interface StreamSink {
        fun onAssistantStart(messageId: Long)
        fun onDelta(text: String)
        fun onAssistantEnd()

        companion object {
            val NONE = object : StreamSink {
                override fun onAssistantStart(messageId: Long) {}
                override fun onDelta(text: String) {}
                override fun onAssistantEnd() {}
            }
        }
    }

    /** Ensure a model is loaded into the native runtime. Returns true if a model is ready. */
    suspend fun ensureModelLoaded(context: Context): Boolean = mutex.withLock {
        if (llama.loaded) return@withLock true
        val file = ModelStorage.existingModel(context) ?: return@withLock false
        return@withLock try {
            llama.load(file.absolutePath)
            loadedChatId = null
            true
        } catch (e: Exception) {
            // A concurrent path may have loaded it first ("Model already loaded"); accept that.
            llama.loaded
        }
    }

    /**
     * Run one user turn through the agent loop against [chatId]: persists the assistant/tool
     * messages, dispatches tool calls via [dispatch], and streams to [sink]. Returns the final
     * assistant text and decode throughput. Serialized via [mutex].
     */
    suspend fun run(
        chatId: Long,
        userContent: String,
        config: Config,
        dispatch: suspend (ToolCall) -> ToolResult,
        sink: StreamSink = StreamSink.NONE,
    ): Result = mutex.withLock {
        // Decide whether we can reuse the KV cache (incremental) or must re-prefill.
        val ctxTokens = llama.contextTokens()
        val nearLimit = ctxTokens > llama.maxContext - CONTEXT_HEADROOM
        val incremental = loadedChatId == chatId && ctxTokens > 0 && !nearLimit

        var feed: String = if (incremental) {
            buildDeltaPrompt(userContent, config)
        } else {
            llama.resetSession()
            val history = dao.messagesOnce(chatId).takeLast(MAX_PREFILL_MESSAGES)
            buildFullPrompt(history, config)
        }
        loadedChatId = chatId

        val start = System.nanoTime()
        var tokenCount = 0
        var finalText = ""
        var toolRounds = 0
        var assistantId = -1L
        val sb = StringBuilder()

        try {
            while (true) {
                assistantId = dao.insertMessage(
                    MessageEntity(
                        chatId = chatId, role = "assistant", content = "",
                        createdAt = System.currentTimeMillis()
                    )
                )
                sink.onAssistantStart(assistantId)

                sb.setLength(0)
                llama.generate(feed, formatChat = true, maxNewTokens = MAX_NEW_TOKENS)
                    .catch { e -> sink.onDelta("[error: ${e.message}]") }
                    .collect { piece ->
                        tokenCount++
                        sb.append(piece)
                        sink.onDelta(cleanResponse(sb.toString()))
                    }

                val output = sb.toString()
                val cleaned = cleanResponse(output)
                val call = if (config.toolsEnabled) Tools.parseToolCall(cleaned) else null

                if (call != null && toolRounds < MAX_TOOL_CALLS) {
                    toolRounds++
                    // Persist the raw tool-call output (UI renders it as a tool step).
                    dao.updateMessageContent(assistantId, output.trim())
                    sink.onAssistantEnd()

                    val result = dispatch(call)
                    val resultText = result.output
                    dao.insertMessage(
                        MessageEntity(
                            chatId = chatId, role = "tool", content = resultText,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    dao.touchChat(chatId, System.currentTimeMillis())

                    feed = buildToolResultPrompt(resultText, config)
                    continue
                }

                // Plain answer (or tool budget exhausted): finalize and stop.
                finalText = cleaned.ifBlank { "(no response)" }
                dao.updateMessageContent(assistantId, finalText)
                dao.touchChat(chatId, System.currentTimeMillis())
                sink.onAssistantEnd()
                break
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // The user tapped Stop. Persist whatever streamed so the turn isn't lost, mark it
            // interrupted, and drop the KV-cache association so the next turn re-prefills cleanly
            // (the native cache position may not match our incremental assumptions after abort).
            withContext(kotlinx.coroutines.NonCancellable) {
                val partial = cleanResponse(sb.toString()).trim()
                val text = if (partial.isEmpty()) "(interrupted)" else "$partial\n\n_(interrupted)_"
                if (assistantId > 0) dao.updateMessageContent(assistantId, text)
                dao.touchChat(chatId, System.currentTimeMillis())
            }
            loadedChatId = null
            sink.onAssistantEnd()
            throw c
        }

        val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
        val tps = if (elapsedSec > 0 && tokenCount > 0) tokenCount / elapsedSec else null
        Result(finalText, tps)
    }

    /** Invalidate the KV-cache association (e.g. when the user switches/starts a chat). */
    fun forgetSession() {
        loadedChatId = null
    }

    // ---- Prompt building (ChatML for Qwen3) ----

    private fun systemPrompt(config: Config): String {
        val base = "You are a helpful, concise assistant."
        if (!config.toolsEnabled) return base
        val sb = StringBuilder(base)
        sb.append("\n\n").append(Tools.systemInstructions)
        sb.append("\n\n").append(PhoneTools.systemInstructions)
        if (config.ragEnabled) sb.append("\n\n").append(RagTools.systemInstructions)
        if (config.memoryEnabled) sb.append("\n\n").append(MemoryTools.systemInstructions)
        sb.append("\n\n").append(PhoneTools.nowLine())
        return sb.toString()
    }

    private fun assistantHeader(config: Config): String =
        if (config.disableThinking) "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        else "<|im_start|>assistant\n"

    private fun buildDeltaPrompt(userText: String, config: Config): String =
        "<|im_end|>\n" +
            "<|im_start|>user\n" + userText + "<|im_end|>\n" +
            assistantHeader(config)

    private fun buildToolResultPrompt(result: String, config: Config): String =
        "<|im_end|>\n" +
            "<|im_start|>user\nTOOL RESULT:\n" + result + "<|im_end|>\n" +
            assistantHeader(config)

    private fun buildFullPrompt(history: List<MessageEntity>, config: Config): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(systemPrompt(config)).append("<|im_end|>\n")
        for (m in history) {
            if (m.role == "assistant" && m.content.isBlank()) continue
            when (m.role) {
                "tool" -> sb.append("<|im_start|>user\nTOOL RESULT:\n")
                    .append(m.content).append("<|im_end|>\n")
                else -> sb.append("<|im_start|>").append(m.role).append("\n")
                    .append(m.content).append("<|im_end|>\n")
            }
        }
        sb.append(assistantHeader(config))
        return sb.toString()
    }

    private val thinkBlock = Regex("(?s)^\\s*<think>.*?</think>\\s*")

    fun cleanResponse(raw: String): String {
        val trimmed = raw.trimStart()
        if (thinkBlock.containsMatchIn(trimmed)) {
            return thinkBlock.replace(trimmed, "").trimStart()
        }
        if (trimmed.startsWith("<think>")) return "…"
        return trimmed
    }

    companion object {
        const val MAX_TOOL_CALLS_PER_TURN = MAX_TOOL_CALLS

        @Volatile
        private var INSTANCE: ChatEngine? = null

        /** Process-wide singleton (both UI and workers share the one native session). */
        fun get(context: Context): ChatEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatEngine(ChatDatabase.get(context).chatDao()).also { INSTANCE = it }
            }

        /** Short chat title derived from the first line of [text]. */
        fun titleFrom(text: String): String {
            val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
            return if (firstLine.length <= 40) firstLine else firstLine.take(40).trimEnd() + "…"
        }
    }
}
