package com.example.androidllm

import kotlin.math.sqrt

/**
 * Pure logic for on-device retrieval-augmented generation (no Android dependencies, so it is
 * host-unit-testable): splitting documents into overlapping chunks and cosine ranking.
 */
object Rag {

    const val CHUNK_CHARS = 1200
    const val CHUNK_OVERLAP = 200

    /** A chunk of a document: its ordinal index and text. */
    data class Chunk(val ord: Int, val text: String)

    /**
     * Split [text] into overlapping chunks of roughly [size] characters, preferring to break on
     * paragraph/line/sentence boundaries near the limit so chunks stay coherent. Overlap keeps
     * context across boundaries for better retrieval.
     */
    fun chunk(text: String, size: Int = CHUNK_CHARS, overlap: Int = CHUNK_OVERLAP): List<Chunk> {
        val clean = text.replace("\r\n", "\n").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= size) return listOf(Chunk(0, clean))

        val chunks = ArrayList<Chunk>()
        var start = 0
        var ord = 0
        val step = (size - overlap).coerceAtLeast(1)
        while (start < clean.length) {
            var end = (start + size).coerceAtMost(clean.length)
            if (end < clean.length) {
                // Prefer a natural boundary in the last third of the window.
                val window = clean.substring(start, end)
                val minBreak = (size * 2) / 3
                val br = listOf("\n\n", "\n", ". ", ".", " ")
                    .firstNotNullOfOrNull { sep ->
                        val idx = window.lastIndexOf(sep)
                        if (idx >= minBreak) idx + sep.length else null
                    }
                if (br != null) end = start + br
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) chunks.add(Chunk(ord++, piece))
            if (end >= clean.length) break
            start = (end - overlap).coerceAtLeast(start + step - overlap).coerceAtLeast(start + 1)
        }
        return chunks
    }

    /** Cosine similarity between two vectors (0 if either is empty or degenerate). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** Rank [candidates] by cosine similarity to [query], returning the top [k] (index, score). */
    fun topK(query: FloatArray, candidates: List<FloatArray>, k: Int): List<Pair<Int, Float>> =
        candidates.mapIndexed { i, v -> i to cosine(query, v) }
            .sortedByDescending { it.second }
            .take(k)

    /** Serialize a float vector to a byte blob (little-endian) for SQLite storage. */
    fun toBlob(vec: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(vec.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (f in vec) bb.putFloat(f)
        return bb.array()
    }

    /** Deserialize a byte blob back into a float vector. */
    fun fromBlob(blob: ByteArray): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(blob.size / 4)
        for (i in out.indices) out[i] = bb.float
        return out
    }
}
