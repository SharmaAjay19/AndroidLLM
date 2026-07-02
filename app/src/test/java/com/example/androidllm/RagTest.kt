package com.example.androidllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagTest {

    @Test
    fun shortTextIsSingleChunk() {
        val chunks = Rag.chunk("hello world")
        assertEquals(1, chunks.size)
        assertEquals("hello world", chunks[0].text)
    }

    @Test
    fun longTextSplitsIntoOrderedChunks() {
        val text = (1..200).joinToString("\n") { "This is line number $it in the document." }
        val chunks = Rag.chunk(text, size = 500, overlap = 100)
        assertTrue("expected multiple chunks", chunks.size > 1)
        // Ordinals are sequential starting at 0.
        chunks.forEachIndexed { i, c -> assertEquals(i, c.ord) }
        // Each chunk respects the size bound (allowing for boundary snapping).
        assertTrue(chunks.all { it.text.length <= 500 })
    }

    @Test
    fun chunksCoverAllContent() {
        val text = (1..50).joinToString(" ") { "word$it" }
        val chunks = Rag.chunk(text, size = 60, overlap = 10)
        val joined = chunks.joinToString(" ") { it.text }
        // First and last tokens must appear somewhere in the chunks.
        assertTrue(joined.contains("word1"))
        assertTrue(joined.contains("word50"))
    }

    @Test
    fun cosineIdenticalVectorsIsOne() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, Rag.cosine(v, v), 1e-5f)
    }

    @Test
    fun cosineOrthogonalIsZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, Rag.cosine(a, b), 1e-6f)
    }

    @Test
    fun topKRanksMostSimilarFirst() {
        val query = floatArrayOf(1f, 0f, 0f)
        val candidates = listOf(
            floatArrayOf(0f, 1f, 0f),   // idx 0: orthogonal
            floatArrayOf(0.9f, 0.1f, 0f), // idx 1: close
            floatArrayOf(-1f, 0f, 0f),  // idx 2: opposite
        )
        val ranked = Rag.topK(query, candidates, 2)
        assertEquals(2, ranked.size)
        assertEquals(1, ranked[0].first) // closest first
    }

    @Test
    fun blobRoundTripPreservesVector() {
        val v = floatArrayOf(0.1f, -2.5f, 3.14159f, 0f, 100f)
        val back = Rag.fromBlob(Rag.toBlob(v))
        assertEquals(v.size, back.size)
        for (i in v.indices) assertEquals(v[i], back[i], 1e-6f)
    }
}
