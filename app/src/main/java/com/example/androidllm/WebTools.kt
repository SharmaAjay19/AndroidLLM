package com.example.androidllm

import org.json.JSONArray
import org.json.JSONTokener
import java.net.URLEncoder

/**
 * Pure (no-Android) helpers for the web browser tools, so the parsing/formatting/pagination
 * logic is unit-testable on the host. The actual page rendering is done by [Browser] using a
 * headless WebView (a real browser engine) with these JavaScript snippets injected — similar
 * in spirit to running `Runtime.evaluate` via the Chrome DevTools protocol.
 */
object WebTools {

    val names = setOf("web_search", "fetch_url")

    const val MAX_PAGE_CHARS = 3000
    const val MAX_RESULTS = 6

    /** DuckDuckGo/Bing-style search URL. Bing markup is stable and scrape-friendly. */
    fun searchUrl(query: String): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return "https://www.bing.com/search?q=$q&setlang=en&cc=US"
    }

    /** Basic http(s) URL validation; also upgrades bare "example.com" to https. */
    fun normalizeUrl(raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            if (!u.contains(' ') && u.contains('.')) u = "https://$u" else return null
        }
        return if (u.length in 1..2000) u else null
    }

    /**
     * JavaScript that scrapes Bing result nodes into a JSON array of {title,url,snippet}.
     * Tolerant of minor markup changes: falls back to any result-like anchors.
     */
    val searchJs: String = """
        (function(){
          var out = [];
          function push(title,url,snippet){
            if(title && url && url.indexOf('http')===0){ out.push({title:title.trim(),url:url,snippet:(snippet||'').trim()}); }
          }
          var items = document.querySelectorAll('li.b_algo');
          for (var i=0;i<items.length && out.length<${MAX_RESULTS};i++){
            var n=items[i];
            var a=n.querySelector('h2 a');
            if(!a) continue;
            var sn=n.querySelector('.b_caption p')||n.querySelector('p');
            push(a.innerText, a.href, sn?sn.innerText:'');
          }
          if(out.length===0){
            var anchors=document.querySelectorAll('h2 a, h3 a');
            for (var j=0;j<anchors.length && out.length<${MAX_RESULTS};j++){
              var an=anchors[j];
              push(an.innerText, an.href, '');
            }
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /**
     * Readability-style extractor: clone the DOM, drop non-content elements, and return the
     * main text as {title,text,url}. Runs in the real page context via the WebView.
     */
    val readabilityJs: String = """
        (function(){
          try{
            var doc=document.cloneNode(true);
            ['script','style','noscript','nav','header','footer','aside','form','iframe','svg','button']
              .forEach(function(t){
                var els=doc.querySelectorAll(t);
                for(var i=0;i<els.length;i++){ els[i].parentNode && els[i].parentNode.removeChild(els[i]); }
              });
            var main=doc.querySelector('main')||doc.querySelector('article')||doc.body||doc.documentElement;
            var txt=(main.innerText||main.textContent||'');
            txt=txt.replace(/[ \t\r\f\v]+/g,' ').replace(/\n[ ]+/g,'\n').replace(/\n{3,}/g,'\n\n').trim();
            return JSON.stringify({title:document.title||'', text:txt, url:location.href});
          }catch(e){ return JSON.stringify({title:'', text:'', url:location.href, error:String(e)}); }
        })()
    """.trimIndent()

    /**
     * WebView.evaluateJavascript hands back the return value serialized as JSON (so a String
     * result arrives double-encoded). Decode one layer to recover the inner JSON text.
     */
    fun decodeEvalResult(value: String?): String {
        if (value == null || value == "null") return ""
        return try {
            when (val v = JSONTokener(value).nextValue()) {
                is String -> v
                else -> value
            }
        } catch (_: Exception) {
            value
        }
    }

    data class SearchHit(val title: String, val url: String, val snippet: String)

    fun parseSearchResults(innerJson: String): List<SearchHit> {
        if (innerJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(innerJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").trim()
                val url = o.optString("url").trim()
                if (title.isEmpty() || url.isEmpty()) null
                else SearchHit(title, url, o.optString("snippet").trim())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun formatSearchResults(query: String, hits: List<SearchHit>): String {
        if (hits.isEmpty()) return "No results found for \"$query\"."
        val sb = StringBuilder("Search results for \"$query\":\n")
        hits.forEachIndexed { i, h ->
            sb.append("\n${i + 1}. ${h.title}\n   ${h.url}\n")
            if (h.snippet.isNotEmpty()) sb.append("   ${h.snippet.take(300)}\n")
        }
        sb.append("\nUse fetch_url with one of these URLs to read a page.")
        return sb.toString()
    }

    /**
     * Return the window of [text] starting at character [offset] within [budget] chars, plus
     * a footer telling the model how to continue. Enables paging through large pages.
     */
    fun paginate(text: String, offset: Int, budget: Int = MAX_PAGE_CHARS): String {
        val total = text.length
        val start = offset.coerceIn(0, total)
        val end = minOf(start + budget, total)
        val chunk = text.substring(start, end)
        return if (end < total) {
            chunk + "\n\n[Showed chars $start\u2013$end of $total. " +
                "More remains \u2014 call fetch_url again with the same url and offset=$end.]"
        } else {
            chunk + "\n\n[End of page ($total chars).]"
        }
    }
}
