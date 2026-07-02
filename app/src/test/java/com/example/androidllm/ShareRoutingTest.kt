package com.example.androidllm

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareRoutingTest {

    @Test
    fun classifiesBareHttpsUrl() {
        val c = ShareRouting.classifyText("  https://example.com/path?q=1  ")
        assertEquals(ShareRouting.Kind.URL, c.kind)
        assertEquals("https://example.com/path?q=1", c.payload)
    }

    @Test
    fun classifiesHttpUrl() {
        val c = ShareRouting.classifyText("http://foo.bar")
        assertEquals(ShareRouting.Kind.URL, c.kind)
    }

    @Test
    fun urlWithSurroundingTextIsPlainText() {
        val c = ShareRouting.classifyText("look at https://example.com now")
        assertEquals(ShareRouting.Kind.TEXT, c.kind)
    }

    @Test
    fun plainTextIsText() {
        val c = ShareRouting.classifyText("some shared note")
        assertEquals(ShareRouting.Kind.TEXT, c.kind)
        assertEquals("some shared note", c.payload)
    }

    @Test
    fun summarizeTextPrompt() {
        val c = ShareRouting.Content(ShareRouting.Kind.TEXT, "hello", "hello")
        val p = ShareRouting.buildPrompt(ShareRouting.Action.SUMMARIZE, c)
        assertEquals("Summarize the following text:\n\nhello", p)
    }

    @Test
    fun translateUrlPrompt() {
        val c = ShareRouting.Content(ShareRouting.Kind.URL, "https://x.io", "https://x.io")
        val p = ShareRouting.buildPrompt(ShareRouting.Action.TRANSLATE, c)
        assertEquals("Translate into English this web page: https://x.io", p)
    }

    @Test
    fun keyPointsFilePrompt() {
        val c = ShareRouting.Content(ShareRouting.Kind.FILE, "notes.txt", "notes.txt")
        val p = ShareRouting.buildPrompt(ShareRouting.Action.KEY_POINTS, c)
        assertEquals("List the key points from the attached file (\"notes.txt\").", p)
    }

    @Test
    fun replyDraftTextPrompt() {
        val c = ShareRouting.Content(ShareRouting.Kind.TEXT, "hi there", "hi there")
        val p = ShareRouting.buildPrompt(ShareRouting.Action.REPLY, c)
        assertEquals("Draft a short reply to the following text:\n\nhi there", p)
    }
}
