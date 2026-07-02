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

    // ---- Hybrid retrieval (lexical + vector fusion) ----

    private val tokenSplit = Regex("[^\\p{L}\\p{Nd}]+")

    // A small English stopword set so common words don't inflate lexical overlap scores.
    private val stopwords = setOf(
        "a", "an", "the", "for", "of", "to", "in", "on", "and", "or", "with", "is", "are",
        "was", "were", "be", "been", "this", "that", "it", "as", "at", "by", "from", "but",
        "if", "then", "so", "than", "into", "about", "over", "you", "your", "my", "me", "we"
    )

    /** Lowercase content-word tokens of [text] (letters/digits, stopwords removed). */
    fun tokenize(text: String): List<String> =
        text.lowercase().split(tokenSplit).filter { it.length > 1 && it !in stopwords }

    /**
     * A simple, dependency-free lexical relevance score: the fraction of distinct query terms that
     * appear in the candidate, lightly weighted by how often they occur. Returns 0..1-ish. This is
     * intentionally cheap (no corpus IDF needed) and complements vector similarity.
     */
    fun lexicalScore(queryTokens: List<String>, candidateText: String): Float {
        if (queryTokens.isEmpty()) return 0f
        val cand = tokenize(candidateText)
        if (cand.isEmpty()) return 0f
        val counts = HashMap<String, Int>()
        for (t in cand) counts[t] = (counts[t] ?: 0) + 1
        var hits = 0
        var weighted = 0.0
        for (q in queryTokens.distinct()) {
            val c = counts[q] ?: 0
            if (c > 0) {
                hits++
                weighted += 1.0 + kotlin.math.ln(c.toDouble()) // diminishing returns per term
            }
        }
        val coverage = hits.toFloat() / queryTokens.distinct().size
        // Blend coverage (how many query terms matched) with a small frequency bonus.
        return (0.7 * coverage + 0.3 * (weighted / (weighted + queryTokens.distinct().size))).toFloat()
    }

    /**
     * Reciprocal-rank fusion of a vector ranking and a lexical ranking over the same [n] items.
     * Each input maps item index -> rank position (0 = best). Missing items get a large rank.
     * Returns item indices sorted by fused score (best first).
     */
    fun reciprocalRankFusion(
        n: Int,
        vectorRank: Map<Int, Int>,
        lexicalRank: Map<Int, Int>,
        k: Int = 60,
    ): List<Int> {
        fun rrf(rank: Int?) = if (rank == null) 0.0 else 1.0 / (k + rank + 1)
        return (0 until n)
            .map { i -> i to (rrf(vectorRank[i]) + rrf(lexicalRank[i])) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** Build a rank map (item index -> 0-based position) from scores, highest score first. */
    fun ranksFromScores(scores: List<Float>): Map<Int, Int> {
        val order = scores.indices.sortedByDescending { scores[it] }
        val map = HashMap<Int, Int>(order.size)
        order.forEachIndexed { pos, idx -> map[idx] = pos }
        return map
    }
}
