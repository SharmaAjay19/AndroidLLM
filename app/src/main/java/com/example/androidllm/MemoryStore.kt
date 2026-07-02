package com.example.androidllm

import android.content.Context
import android.llama.cpp.LLamaAndroid
import android.net.Uri
import com.example.androidllm.data.ChatDatabase
import com.example.androidllm.data.MemoryChunkEntity
import com.example.androidllm.data.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The "second brain": captures things the user shares/dictates/clips, embeds them, and answers
 * natural-language recall over them. Reuses the RAG embedder ([DocIndex]/[LLamaAndroid.embed]) and
 * the same little-endian vector storage as documents, but in dedicated `memories` tables.
 *
 * Recall is **hybrid**: vector similarity fused with a lexical term-overlap score (reciprocal-rank
 * fusion), which is more robust than either alone for short, keyword-y queries.
 */
class MemoryStore private constructor(private val appContext: Context) {

    private val llama = LLamaAndroid.instance()
    private val docIndex = DocIndex.get(appContext)
    private val dao = ChatDatabase.get(appContext).memoryDao()
    private val mutex = Mutex()

    data class Hit(val memoryId: Long, val title: String, val text: String, val uri: String?, val score: Float)

    /** Whether the embedder needed for capture/recall is available (downloaded). */
    val isReady: Boolean get() = docIndex.isEmbedderDownloaded

    /** Capture free text (or a URL/voice transcript) as a memory. Returns the new id, or -1. */
    suspend fun captureText(
        text: String,
        type: String = "text",
        title: String? = null,
        uri: String? = null,
        sourceApp: String? = null,
    ): Long {
        val clean = text.trim()
        if (clean.isEmpty()) return -1
        if (!docIndex.ensureEmbedderLoaded()) return -1
        return mutex.withLock { store(type, sourceApp, uri, title ?: deriveTitle(clean), clean) }
    }

    /** Capture an image by OCR-ing it into text, then embedding. Returns the new id, or -1. */
    suspend fun captureImage(imageUri: Uri, sourceApp: String? = null): Long {
        if (!docIndex.ensureEmbedderLoaded()) return -1
        val text = Ocr.extract(appContext, imageUri)
        if (text.isBlank()) return -1
        return mutex.withLock {
            store("image", sourceApp, imageUri.toString(), deriveTitle(text), text)
        }
    }

    private suspend fun store(
        type: String, sourceApp: String?, uri: String?, title: String, text: String,
    ): Long {
        val now = System.currentTimeMillis()
        val chunks = Rag.chunk(text)
        val embedded = ArrayList<Pair<Int, ByteArray>>(chunks.size)
        for (c in chunks) {
            val vec = llama.embed(c.text) ?: continue
            embedded.add(c.ord to Rag.toBlob(vec))
        }
        if (embedded.isEmpty()) return -1
        return dao.insertMemoryWithChunks(
            MemoryEntity(type = type, sourceApp = sourceApp, uri = uri, title = title, text = text, createdAt = now)
        ) { memId ->
            chunks.mapNotNull { c ->
                val blob = embedded.firstOrNull { it.first == c.ord }?.second ?: return@mapNotNull null
                MemoryChunkEntity(memoryId = memId, ord = c.ord, text = c.text, embedding = blob)
            }
        }
    }

    /** Hybrid recall: return the top-[k] most relevant memories for [query]. */
    suspend fun recall(query: String, k: Int = 4): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (!docIndex.ensureEmbedderLoaded()) return emptyList()
        val qVec = llama.embed(DocIndex.EMBED_QUERY_PREFIX + q) ?: return emptyList()
        val chunks = withContext(Dispatchers.IO) { dao.allChunks() }
        if (chunks.isEmpty()) return emptyList()

        val qTokens = Rag.tokenize(q)
        val vecScores = chunks.map { Rag.cosine(qVec, Rag.fromBlob(it.embedding)) }
        val lexScores = chunks.map { Rag.lexicalScore(qTokens, it.text) }

        val fused = Rag.reciprocalRankFusion(
            chunks.size, Rag.ranksFromScores(vecScores), Rag.ranksFromScores(lexScores)
        )

        // Take best chunk per memory, preserving fused order, up to k memories.
        val seen = HashSet<Long>()
        val hits = ArrayList<Hit>(k)
        for (idx in fused) {
            val c = chunks[idx]
            if (!seen.add(c.memoryId)) continue
            val mem = dao.getMemory(c.memoryId) ?: continue
            hits.add(Hit(mem.id, mem.title, c.text, mem.uri, vecScores[idx]))
            if (hits.size >= k) break
        }
        return hits
    }

    suspend fun count(): Int = dao.allMemoriesOnce().size
    suspend fun delete(id: Long) = dao.deleteMemory(id)
    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)
    suspend fun clear() = dao.clearAll()

    /** Export all memories as Markdown for the "you own it" promise. */
    suspend fun exportMarkdown(): String {
        val mems = dao.allMemoriesOnce()
        val sb = StringBuilder("# AndroidLLM memories (${mems.size})\n\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        for (m in mems) {
            sb.append("## ").append(m.title).append('\n')
            sb.append("- ").append(m.type).append(" · ").append(fmt.format(java.util.Date(m.createdAt)))
            if (m.pinned) sb.append(" · pinned")
            m.uri?.let { sb.append(" · ").append(it) }
            sb.append("\n\n").append(m.text).append("\n\n---\n\n")
        }
        return sb.toString()
    }

    private fun deriveTitle(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length <= 60) firstLine.ifEmpty { "Memory" }
        else firstLine.take(60).trimEnd() + "…"
    }

    companion object {
        @Volatile
        private var INSTANCE: MemoryStore? = null

        fun get(context: Context): MemoryStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
