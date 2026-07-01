package com.example.androidllm

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * A headless browser built on Android's WebView — a real browser engine that runs JavaScript,
 * carries a normal user agent and cookies, and renders dynamic pages. We drive it and inject
 * JavaScript to extract content, conceptually like using the Chrome DevTools protocol's
 * `Runtime.evaluate`. This handles JS-heavy sites and no-JS bot walls that plain HTTP fetches
 * (which hit CAPTCHAs) cannot.
 *
 * The last fetched page is cached so the model can page through it cheaply with offsets.
 */
class Browser(private val appContext: Context) {

    private var webView: WebView? = null

    // Cache of the last fetched page so fetch_url(url, offset) doesn't reload for each chunk.
    private var cachedUrl: String? = null
    private var cachedText: String = ""

    private companion object {
        const val PAGE_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 1_200L
        // Desktop UA yields fuller pages/search results than the default WebView UA.
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(appContext)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = USER_AGENT
            loadsImagesAutomatically = false
            blockNetworkImage = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView = wv
        return wv
    }

    /** Load [url], let it render, then run [js] in the page and return the decoded result. */
    private suspend fun render(url: String, js: String): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val finished = CompletableDeferred<Unit>()
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, u: String?) {
                if (!finished.isCompleted) finished.complete(Unit)
            }
        }
        wv.stopLoading()
        wv.loadUrl(url)
        withTimeoutOrNull(PAGE_TIMEOUT_MS) { finished.await() }
        delay(SETTLE_MS) // give client-side JS a moment to populate the DOM

        val result = CompletableDeferred<String>()
        wv.evaluateJavascript(js) { value -> result.complete(value ?: "null") }
        val raw = withTimeoutOrNull(PAGE_TIMEOUT_MS) { result.await() } ?: "null"
        WebTools.decodeEvalResult(raw)
    }

    /** Run a web search and return a compact, model-friendly result list. */
    suspend fun search(query: String): ToolResult {
        if (query.isBlank()) return ToolResult(false, "Empty search query.")
        return try {
            val json = render(WebTools.searchUrl(query), WebTools.searchJs)
            val hits = WebTools.parseSearchResults(json).take(WebTools.MAX_RESULTS)
            ToolResult(true, WebTools.formatSearchResults(query, hits))
        } catch (e: Exception) {
            ToolResult(false, "web_search failed: ${e.message}")
        }
    }

    /** Fetch a page as clean readable text, paginated via [offset]. */
    suspend fun fetch(url: String, offset: Int): ToolResult {
        val normalized = WebTools.normalizeUrl(url)
            ?: return ToolResult(false, "Invalid URL '$url'. Use a full http(s) URL.")
        return try {
            if (normalized != cachedUrl || cachedText.isEmpty()) {
                val json = render(normalized, WebTools.readabilityJs)
                val obj = runCatching { JSONObject(json) }.getOrNull()
                val title = obj?.optString("title").orEmpty()
                val text = obj?.optString("text").orEmpty()
                cachedUrl = normalized
                cachedText = if (title.isNotBlank()) "# $title\n\n$text" else text
            }
            if (cachedText.isBlank()) {
                return ToolResult(true, "(the page had no readable text)")
            }
            ToolResult(true, WebTools.paginate(cachedText, offset))
        } catch (e: Exception) {
            ToolResult(false, "fetch_url failed: ${e.message}")
        }
    }

    /** Diagnostic hook: run [js] on [url] and return the decoded eval result. */
    suspend fun evalOn(url: String, js: String): String = render(url, js)

    fun destroy() {
        val wv = webView ?: return
        webView = null
        cachedUrl = null
        cachedText = ""
        // WebView methods must run on the main thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            wv.stopLoading()
            wv.destroy()
        }
    }
}
