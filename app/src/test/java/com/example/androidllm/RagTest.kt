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

    @Test
    fun lexicalScoreRewardsTermCoverage() {
        val q = Rag.tokenize("visa requirements for japan")
        val hit = Rag.lexicalScore(q, "The visa requirements for Japan include a passport.")
        val miss = Rag.lexicalScore(q, "A recipe for chicken curry with rice.")
        assertTrue("relevant text should outscore irrelevant", hit > miss)
        assertTrue(hit > 0.5f)
        assertEquals(0f, miss, 1e-6f)
    }

    @Test
    fun lexicalScoreEmptyQueryIsZero() {
        assertEquals(0f, Rag.lexicalScore(emptyList(), "anything"), 1e-6f)
    }

    @Test
    fun rrfFusesVectorAndLexicalRankings() {
        // Item 2 is best lexically, item 0 best by vector; fusion should surface both near top.
        val vectorRank = Rag.ranksFromScores(listOf(0.9f, 0.2f, 0.5f))   // order: 0,2,1
        val lexicalRank = Rag.ranksFromScores(listOf(0.1f, 0.3f, 0.95f)) // order: 2,1,0
        val fused = Rag.reciprocalRankFusion(3, vectorRank, lexicalRank)
        assertEquals(3, fused.size)
        // Items 0 and 2 each rank #1 in one list, so they should be the top two.
        assertTrue(fused.take(2).containsAll(listOf(0, 2)))
        assertEquals(1, fused[2]) // item 1 is never #1 in either → last
    }

    @Test
    fun ranksFromScoresOrdersDescending() {
        val ranks = Rag.ranksFromScores(listOf(0.2f, 0.9f, 0.5f))
        assertEquals(0, ranks[1]) // highest score → rank 0
        assertEquals(1, ranks[2])
        assertEquals(2, ranks[0])
    }
}
