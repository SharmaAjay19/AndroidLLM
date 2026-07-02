package com.example.androidllm

import android.content.Context
import android.llama.cpp.LLamaAndroid
import com.example.androidllm.data.ChatDatabase
import com.example.androidllm.data.DocChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device retrieval-augmented generation over the workspace: manages the small embedding
 * model, indexes text files into a vector store, and answers similarity queries. Backs the
 * `search_documents` agent tool.
 */
class DocIndex private constructor(private val appContext: Context) {

    private val llama = LLamaAndroid.instance()
    private val dao = ChatDatabase.get(appContext).docDao()
    private val mutex = Mutex()

    private val embedderFile: File
        get() = File(appContext.filesDir, EMBED_MODEL_FILE)

    val isEmbedderDownloaded: Boolean get() = Downloader.isValidGguf(embedderFile)
    val isEmbedderLoaded: Boolean get() = llama.embedderLoaded

    data class IndexResult(val files: Int, val chunks: Int, val skipped: Int, val errors: Int)
    data class Snippet(val path: String, val ord: Int, val text: String, val score: Float)

    /** Load the embedder into native memory (must already be downloaded). */
    suspend fun ensureEmbedderLoaded(): Boolean {
        if (llama.embedderLoaded) return true
        if (!isEmbedderDownloaded) return false
        return try {
            llama.loadEmbedder(embedderFile.absolutePath)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * (Re)index the workspace: chunk each text file, embed the chunks, and upsert them.
     * Files whose modified time is unchanged since the last index are skipped.
     */
    suspend fun indexWorkspace(
        force: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): IndexResult = mutex.withLock {
        if (!ensureEmbedderLoaded()) return IndexResult(0, 0, 0, 1)

        val base = Workspace.dir(appContext)
        val files = withContext(Dispatchers.IO) {
            base.walkTopDown().filter { it.isFile && isTextFile(it) }.toList()
        }

        var indexed = 0
        var totalChunks = 0
        var skipped = 0
        var errors = 0

        for (f in files) {
            val rel = f.relativeTo(base).path
            try {
                val recorded = dao.indexedMtime(rel)
                if (!force && recorded != null && recorded == f.lastModified()) {
                    skipped++
                    continue
                }
                onProgress("Indexing $rel…")
                val text = withContext(Dispatchers.IO) { f.readText() }
                val chunks = Rag.chunk(text)
                dao.deleteByPath(rel)
                val rows = ArrayList<DocChunkEntity>(chunks.size)
                for (c in chunks) {
                    val vec = llama.embed(c.text) ?: continue
                    rows.add(
                        DocChunkEntity(
                            path = rel, ord = c.ord, text = c.text,
                            embedding = Rag.toBlob(vec), mtime = f.lastModified()
                        )
                    )
                }
                if (rows.isNotEmpty()) {
                    dao.insertAll(rows)
                    indexed++
                    totalChunks += rows.size
                }
            } catch (_: Exception) {
                errors++
            }
        }
        IndexResult(indexed, totalChunks, skipped, errors)
    }

    /** Embed [query] and return the top-[k] most similar indexed chunks. */
    suspend fun search(query: String, k: Int = 4): List<Snippet> {
        if (!ensureEmbedderLoaded()) return emptyList()
        val q = llama.embed(EMBED_QUERY_PREFIX + query) ?: return emptyList()
        val chunks = dao.allChunks()
        if (chunks.isEmpty()) return emptyList()
        return chunks
            .map { it to Rag.cosine(q, Rag.fromBlob(it.embedding)) }
            .sortedByDescending { it.second }
            .take(k)
            .map { (c, score) -> Snippet(c.path, c.ord, c.text, score) }
    }

    suspend fun clearIndex() = dao.clear()

    suspend fun fileCount(): Int = dao.observeFileCount().first()

    private fun isTextFile(f: File): Boolean {
        if (f.length() == 0L || f.length() > MAX_FILE_BYTES) return false
        val name = f.name.lowercase()
        if (name.endsWith(".gguf") || name.endsWith(".bin") || name.endsWith(".part")) return false
        val ext = name.substringAfterLast('.', "")
        if (ext in TEXT_EXTENSIONS) return true
        // Unknown extension: sniff the first bytes for NUL (a strong binary signal).
        return try {
            f.inputStream().use { input ->
                val buf = ByteArray(512)
                val n = input.read(buf)
                if (n <= 0) return false
                (0 until n).none { buf[it].toInt() == 0 }
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        // bge-small-en-v1.5 (384-dim) — small, fast, good retrieval; works with llama.cpp.
        const val EMBED_MODEL_URL =
            "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q8_0.gguf"
        const val EMBED_MODEL_FILE = "bge-small-en-v1.5-q8_0.gguf"

        // bge retrieval works best when the *query* carries this instruction (docs get none).
        const val EMBED_QUERY_PREFIX =
            "Represent this sentence for searching relevant passages: "

        private const val MAX_FILE_BYTES = 2L * 1024 * 1024 // 2 MB per file cap
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log",
            "html", "htm", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "cs", "go",
            "rs", "rb", "php", "sh", "sql", "ini", "toml", "cfg", "properties", "text"
        )

        @Volatile
        private var INSTANCE: DocIndex? = null

        fun get(context: Context): DocIndex =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DocIndex(context.applicationContext).also { INSTANCE = it }
            }
    }
}
