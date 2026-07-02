package android.llama.cpp

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Thin Kotlin bridge over the llama.cpp JNI layer (see src/main/cpp/llama-android.cpp).
 *
 * Adapted from the official llama.cpp Android example (tag b5600). Changes vs. upstream:
 *  - the prompt batch is sized to the full context (so multi-turn chat prompts fit), and
 *  - generation length (nLen) is configurable per send() instead of a hard-coded 64.
 *
 * All native calls are marshalled onto a single dedicated thread, because the underlying
 * llama_context is not thread-safe.
 */
class LLamaAndroid {
    private val tag: String? = this::class.simpleName

    private val threadLocalState: ThreadLocal<State> = ThreadLocal.withInitial { State.Idle }

    // Process-wide load flags. The native state lives on the runLoop thread (threadLocalState),
    // but `loaded`/`embedderLoaded` are read from other threads (ChatEngine on worker/VM
    // coroutines), so they must not depend on a ThreadLocal. These volatiles are the source of
    // truth for "is a model in memory?" and are updated on the runLoop after (un)load.
    @Volatile
    private var modelLoadedFlag: Boolean = false

    @Volatile
    private var embedderLoadedFlag: Boolean = false

    // Tokens currently in the KV cache for the active conversation. Only touched on runLoop.
    private var nPast: Int = 0

    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            Log.d(tag, "Dedicated thread for native code: ${Thread.currentThread().name}")

            // No-op if called more than once.
            System.loadLibrary("llama-android")

            // Set llama log handler to Android
            log_to_android()
            backend_init(false)

            Log.d(tag, system_info())

            it.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, exception: Throwable ->
                Log.e(tag, "Unhandled exception", exception)
            }
        }
    }.asCoroutineDispatcher()

    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long): Long
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean)
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(): Long
    private external fun free_sampler(sampler: Long)
    private external fun bench_model(
        context: Long,
        model: Long,
        batch: Long,
        pp: Int,
        tg: Int,
        pl: Int,
        nr: Int
    ): String

    private external fun system_info(): String

    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        formatChat: Boolean,
        nPast: Int
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: IntVar
    ): String?

    private external fun kv_cache_clear(context: Long)

    private external fun new_embedding_context(model: Long): Long
    private external fun embed(context: Long, text: String): FloatArray?

    // Embedder (separate small model) state, only touched on runLoop.
    private var embModel: Long = 0
    private var embContext: Long = 0

    val embedderLoaded: Boolean get() = embedderLoadedFlag

    /** Load a small embedding model (GGUF) into a dedicated context for RAG. */
    suspend fun loadEmbedder(pathToModel: String) {
        withContext(runLoop) {
            if (embContext != 0L) return@withContext
            val model = load_model(pathToModel)
            if (model == 0L) throw IllegalStateException("load_model() failed for embedder")
            val ctx = new_embedding_context(model)
            if (ctx == 0L) {
                free_model(model)
                throw IllegalStateException("new_embedding_context() failed")
            }
            embModel = model
            embContext = ctx
            embedderLoadedFlag = true
            Log.i(tag, "Loaded embedder $pathToModel")
        }
    }

    /** Embed [text] into an L2-normalized vector, or null if no embedder is loaded. */
    suspend fun embed(text: String): FloatArray? = withContext(runLoop) {
        if (embContext == 0L) null else embed(embContext, text)
    }

    suspend fun unloadEmbedder() {
        withContext(runLoop) {
            if (embContext != 0L) { free_context(embContext); embContext = 0 }
            if (embModel != 0L) { free_model(embModel); embModel = 0 }
            embedderLoadedFlag = false
        }
    }

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    Log.d(tag, "bench(): $state")
                    bench_model(state.context, state.model, state.batch, pp, tg, pl, nr)
                }

                else -> throw IllegalStateException("No model loaded")
            }
        }
    }

    suspend fun load(pathToModel: String) {
        withContext(runLoop) {
            when (threadLocalState.get()) {
                is State.Idle -> {
                    val model = load_model(pathToModel)
                    if (model == 0L)  throw IllegalStateException("load_model() failed")

                    val context = new_context(model)
                    if (context == 0L) throw IllegalStateException("new_context() failed")

                    // Batch must be able to hold the whole prompt in one decode call.
                    // Match the context size (2048) configured natively in new_context().
                    val batch = new_batch(2048, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    val sampler = new_sampler()
                    if (sampler == 0L) throw IllegalStateException("new_sampler() failed")

                    Log.i(tag, "Loaded model $pathToModel")
                    threadLocalState.set(State.Loaded(model, context, batch, sampler))
                    modelLoadedFlag = true
                }
                else -> throw IllegalStateException("Model already loaded")
            }
        }
    }

    val loaded: Boolean
        get() = modelLoadedFlag

    /**
     * Number of tokens currently held in the KV cache for the active conversation.
     * Read on the native run loop so it is consistent with generation.
     */
    suspend fun contextTokens(): Int = withContext(runLoop) { nPast }

    /**
     * Clears the KV cache and resets the conversation position. Call this when starting
     * a new chat or switching to a different chat (the cache holds one conversation).
     */
    suspend fun resetSession() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    kv_cache_clear(state.context)
                    nPast = 0
                }
                else -> {}
            }
        }
    }

    /**
     * Decodes [text] continuing from the current KV cache position and streams the reply.
     *
     * The KV cache is **kept** afterwards so the next turn only needs to decode its new
     * tokens instead of re-processing the whole conversation. Pass only the incremental
     * ChatML for follow-up turns; pass the full prompt (after [resetSession]) for the
     * first turn of a conversation.
     *
     * @param text          ChatML to decode (full prompt or incremental delta).
     * @param formatChat    parse special tokens (e.g. <|im_start|>) in [text].
     * @param maxNewTokens  cap on tokens generated this turn (also bounded by context size).
     */
    fun generate(text: String, formatChat: Boolean = true, maxNewTokens: Int = 512): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                val startPos = completion_init(state.context, state.batch, text, formatChat, nPast)
                val nLenAbs = minOf(N_CTX, startPos + maxNewTokens)
                val ncur = IntVar(startPos)
                while (ncur.value < nLenAbs) {
                    val str = completion_loop(state.context, state.batch, state.sampler, nLenAbs, ncur)
                        ?: break
                    emit(str)
                }
                // Keep the KV cache; remember where the conversation now stands.
                nPast = ncur.value
            }
            else -> {}
        }
    }.flowOn(runLoop)

    /** Maximum usable context, mirrors n_ctx configured natively in new_context(). */
    val maxContext: Int get() = N_CTX

    /**
     * Unloads the model and frees resources.
     *
     * This is a no-op if there's no model loaded.
     */
    suspend fun unload() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    free_context(state.context)
                    free_model(state.model)
                    free_batch(state.batch)
                    free_sampler(state.sampler)

                    threadLocalState.set(State.Idle)
                    modelLoadedFlag = false
                }
                else -> {}
            }
        }
    }

    companion object {
        private class IntVar(value: Int) {
            @Volatile
            var value: Int = value
                private set

            fun inc() {
                synchronized(this) {
                    value += 1
                }
            }
        }

        private sealed interface State {
            data object Idle: State
            data class Loaded(val model: Long, val context: Long, val batch: Long, val sampler: Long): State
        }

        // Enforce only one instance of Llm.
        private val _instance: LLamaAndroid = LLamaAndroid()

        fun instance(): LLamaAndroid = _instance

        // KV cache / context size. Must match n_ctx set natively in new_context().
        private const val N_CTX: Int = 2048
    }
}
