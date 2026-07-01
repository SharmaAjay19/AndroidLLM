package com.example.androidllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-side tests for the pure web-tool logic (parsing, formatting, pagination, URL rules). */
class WebToolsTest {

    @Test
    fun searchUrl_encodesQuery() {
        val u = WebTools.searchUrl("hello world & cats")
        assertTrue(u.startsWith("https://www.bing.com/search?q="))
        assertTrue(u.contains("hello+world") || u.contains("hello%20world"))
        assertTrue(u.contains("%26")) // & encoded
    }

    @Test
    fun normalizeUrl_addsHttpsToBareDomain() {
        assertEquals("https://example.com", WebTools.normalizeUrl("example.com"))
    }

    @Test
    fun normalizeUrl_keepsFullUrl() {
        assertEquals("http://foo.com/x", WebTools.normalizeUrl("http://foo.com/x"))
        assertEquals("https://foo.com/x", WebTools.normalizeUrl("https://foo.com/x"))
    }

    @Test
    fun normalizeUrl_rejectsGarbage() {
        assertNull(WebTools.normalizeUrl(""))
        assertNull(WebTools.normalizeUrl("not a url"))
        assertNull(WebTools.normalizeUrl("justtext"))
    }

    @Test
    fun decodeEvalResult_unwrapsDoubleEncodedString() {
        // WebView.evaluateJavascript returns a JSON-encoded string value.
        val raw = "\"[{\\\"title\\\":\\\"A\\\"}]\""
        assertEquals("[{\"title\":\"A\"}]", WebTools.decodeEvalResult(raw))
    }

    @Test
    fun decodeEvalResult_handlesNull() {
        assertEquals("", WebTools.decodeEvalResult("null"))
        assertEquals("", WebTools.decodeEvalResult(null))
    }

    @Test
    fun parseSearchResults_parsesArray() {
        val json = """[{"title":"T1","url":"https://a.com","snippet":"s1"},
                       {"title":"T2","url":"https://b.com","snippet":""}]"""
        val hits = WebTools.parseSearchResults(json)
        assertEquals(2, hits.size)
        assertEquals("T1", hits[0].title)
        assertEquals("https://b.com", hits[1].url)
    }

    @Test
    fun parseSearchResults_dropsIncomplete() {
        val json = """[{"title":"","url":"https://a.com"},{"title":"O","url":""}]"""
        assertTrue(WebTools.parseSearchResults(json).isEmpty())
    }

    @Test
    fun parseSearchResults_handlesBadJson() {
        assertTrue(WebTools.parseSearchResults("not json").isEmpty())
        assertTrue(WebTools.parseSearchResults("").isEmpty())
    }

    @Test
    fun formatSearchResults_numbersAndListsUrls() {
        val hits = listOf(
            WebTools.SearchHit("Title A", "https://a.com", "snippet a"),
            WebTools.SearchHit("Title B", "https://b.com", "")
        )
        val out = WebTools.formatSearchResults("q", hits)
        assertTrue(out.contains("1. Title A"))
        assertTrue(out.contains("https://a.com"))
        assertTrue(out.contains("2. Title B"))
        assertTrue(out.contains("fetch_url"))
    }

    @Test
    fun formatSearchResults_emptyMessage() {
        val out = WebTools.formatSearchResults("nothing", emptyList())
        assertTrue(out.contains("No results"))
    }

    @Test
    fun paginate_firstChunkAdvertisesNextOffset() {
        val text = "a".repeat(7000)
        val out = WebTools.paginate(text, 0, budget = 3000)
        assertTrue(out.startsWith("a".repeat(3000)))
        assertTrue(out.contains("offset=3000"))
    }

    @Test
    fun paginate_lastChunkSaysEnd() {
        val text = "a".repeat(2000)
        val out = WebTools.paginate(text, 0, budget = 3000)
        assertTrue(out.contains("End of page"))
    }

    @Test
    fun paginate_offsetBeyondEndIsSafe() {
        val out = WebTools.paginate("short", 999, budget = 100)
        assertTrue(out.contains("End of page"))
    }
}
