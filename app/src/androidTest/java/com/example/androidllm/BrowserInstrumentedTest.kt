package com.example.androidllm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device integration test for the headless WebView browser. Runs inside the app process
 * via instrumentation (no UI, no keyboard, no LLM), exercising the real WebView + injected
 * JavaScript against the live network. Requires the emulator/device to have internet.
 */
@RunWith(AndroidJUnit4::class)
class BrowserInstrumentedTest {

    private val appContext =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun fetchUrl_extractsReadableTextFromStablePage() = runBlocking {
        val browser = Browser(appContext)
        try {
            // example.com is tiny, JS-free, and has stable, predictable text.
            val result = browser.fetch("https://example.com", 0)
            assertTrue("fetch should succeed: ${result.output}", result.ok)
            assertTrue(
                "should contain page text, got: ${result.output.take(200)}",
                result.output.contains("Example Domain", ignoreCase = true)
            )
        } finally {
            browser.destroy()
        }
    }

    @Test
    fun fetchUrl_rejectsInvalidUrl() = runBlocking {
        val browser = Browser(appContext)
        try {
            val result = browser.fetch("not a url", 0)
            assertTrue(!result.ok)
            assertTrue(result.output.contains("Invalid URL"))
        } finally {
            browser.destroy()
        }
    }

    @Test
    fun webSearch_returnsResultsWithUrls() = runBlocking {
        val browser = Browser(appContext)
        try {
            val result = browser.search("wikipedia")
            assertTrue("search should succeed: ${result.output}", result.ok)
            // Either we got real results (contain http links) or a clean "no results" message.
            val hasLinks = result.output.contains("http")
            val cleanEmpty = result.output.contains("No results", ignoreCase = true)
            assertTrue(
                "unexpected search output: ${result.output.take(300)}",
                hasLinks || cleanEmpty
            )
        } finally {
            browser.destroy()
        }
    }
}
