package com.example.androidllm

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * The agent's working directory for file tools.
 *
 * Default location is user-visible shared storage (`/sdcard/AndroidLLM/files`) when the app
 * has All-files access, so you can find written files in a file manager. The folder is
 * configurable in Settings. Falls back to app-private internal storage if access isn't granted.
 *
 * Relative tool paths (e.g. "notes.txt") resolve inside this folder and cannot escape it.
 * Absolute paths on shared storage (e.g. "/sdcard/Download/out.txt") are honored when the app
 * has All-files access, so the user can direct writes anywhere.
 */
object Workspace {

    /** Built-in default folder (used when the user hasn't chosen one in Settings). */
    fun defaultDir(context: Context): File =
        if (ModelStorage.hasAllFilesAccess())
            File(Environment.getExternalStorageDirectory(), "AndroidLLM/files")
        else
            File(context.filesDir, "workspace")

    /** The active workspace folder (configured path or default), created if needed. */
    fun dir(context: Context): File {
        val configured = Settings.getWorkspacePath(context)
        val d = if (configured != null) File(configured) else defaultDir(context)
        d.mkdirs()
        return d
    }

    private fun isOnSharedStorage(path: String): Boolean =
        path.startsWith("/storage/") || path.startsWith("/sdcard/")

    /**
     * Pure resolution logic (no Android dependencies), so it is unit-testable:
     *  - absolute shared-storage paths are honored when [hasAllFilesAccess];
     *  - relative paths resolve inside [base] and may not escape it (`..` rejected);
     *  - everything else returns null.
     */
    fun resolvePath(base: File, path: String, hasAllFilesAccess: Boolean): File? {
        val p = path.trim()
        if (p.isEmpty()) return null

        if (p.startsWith("/")) {
            return if (isOnSharedStorage(p) && hasAllFilesAccess) File(p) else null
        }

        val b = base.canonicalFile
        val clean = p.trimStart('/', '\\')
        val f = File(b, clean).canonicalFile
        return if (f.path == b.path || f.path.startsWith(b.path + File.separator)) f else null
    }

    /** Resolve [path] against the active workspace, honoring absolute shared-storage paths. */
    fun resolve(context: Context, path: String): File? =
        resolvePath(dir(context), path, ModelStorage.hasAllFilesAccess())
}

/** A parsed request from the model to run a tool. */
data class ToolCall(val name: String, val args: JSONObject)

/** Result of running a tool, fed back to the model and shown in the UI. */
data class ToolResult(val ok: Boolean, val output: String)

object Tools {

    val names = setOf("read_file", "write_file", "list_files", "web_search", "fetch_url")

    /** Tools that require the headless browser (handled asynchronously by [Browser]). */
    val webToolNames = WebTools.names

    // Per read_file call: character budget and line caps. Kept well under the model's
    // context window so a chunk plus the question and answer all fit.
    private const val MAX_CHUNK_CHARS = 3500
    private const val DEFAULT_CHUNK_LINES = 200
    private const val MAX_CHUNK_LINES = 1000

    /** Human-readable description injected into the system prompt. */
    val systemInstructions: String = """
You can use tools to read/write files and browse the web.

Available tools:
- read_file — read a text file. Large files come back in chunks. args:
  {"path": "<filename>", "offset": <first line, default 1>, "limit": <max lines, default 200>}
  The result ends with a note telling you the next offset to continue reading.
- write_file — create or overwrite a text file. args: {"path": "<filename or /sdcard/... path>", "content": "<text>"}
  A bare filename is saved in the workspace folder; an absolute /sdcard path writes there directly.
- list_files — list files in the workspace. args: {}
- web_search — search the web. args: {"query": "<what to search for>"}
  Returns a numbered list of results with titles, URLs, and snippets.
- fetch_url — open a web page and read it as text (chunked). args:
  {"url": "<https://...>", "offset": <start char, default 0>}
  The result ends with a note telling you the next offset to continue reading.

To call a tool, reply with ONLY a single JSON object and nothing else, for example:
{"tool": "web_search", "args": {"query": "current weather in Tokyo"}}

After each tool call you will receive a message beginning with "TOOL RESULT:". Use it to
continue. Call one tool at a time, and only when needed. A typical web task is: web_search
to find a page, then fetch_url to read it. When you are done, reply to the user in plain
text (no JSON).
""".trim()

    /**
     * Try to extract a tool call from model [text]. Tolerant: accepts the whole string as
     * JSON, or the first {...} block within it. Returns null if it isn't a known tool call.
     */
    fun parseToolCall(text: String): ToolCall? {
        val trimmed = text.trim()
        val candidates = buildList {
            add(trimmed)
            val first = trimmed.indexOf('{')
            val last = trimmed.lastIndexOf('}')
            if (first in 0 until last) add(trimmed.substring(first, last + 1))
        }
        for (c in candidates) {
            try {
                val obj = JSONObject(c)
                val name = obj.optString("tool").trim()
                if (name in names || name in PhoneTools.names) {
                    val args = obj.optJSONObject("args") ?: JSONObject()
                    return ToolCall(name, args)
                }
            } catch (_: Exception) {
                // not JSON; try next candidate
            }
        }
        return null
    }

    /** A short, friendly one-line label for a tool call (used in the chat UI). */
    fun label(call: ToolCall): String = when (call.name) {
        "read_file" -> {
            val off = call.args.optInt("offset", 1)
            val path = call.args.optString("path")
            if (off > 1) "read_file(\"$path\", offset=$off)" else "read_file(\"$path\")"
        }
        "write_file" -> "write_file(\"${call.args.optString("path")}\")"
        "list_files" -> "list_files()"
        "web_search" -> "web_search(\"${call.args.optString("query")}\")"
        "fetch_url" -> {
            val off = call.args.optInt("offset", 0)
            val url = call.args.optString("url")
            if (off > 0) "fetch_url(\"$url\", offset=$off)" else "fetch_url(\"$url\")"
        }
        in PhoneTools.names -> PhoneTools.label(call)
        else -> call.name
    }

    fun execute(context: Context, call: ToolCall): ToolResult = try {
        when (call.name) {
            "read_file" -> readFile(
                context,
                call.args.optString("path"),
                call.args.optInt("offset", 1),
                call.args.optInt("limit", 0)
            )
            "write_file" -> writeFile(context, call.args.optString("path"), call.args.optString("content"))
            "list_files" -> listFiles(context)
            else -> ToolResult(false, "Unknown tool '${call.name}'")
        }
    } catch (e: Exception) {
        ToolResult(false, "Error running ${call.name}: ${e.message}")
    }

    /**
     * Read a window of [path] starting at line [offset] (1-based), up to [limit] lines and
     * a per-call character budget. The result ends with a note telling the model the next
     * offset to continue, so it can page through arbitrarily large files. Reads only as far
     * as the window (plus one line to detect "more"), so it doesn't load the whole file.
     */
    private fun readFile(context: Context, path: String, offset: Int, limit: Int): ToolResult {
        val f = Workspace.resolve(context, path)
            ?: return ToolResult(false, "Invalid path '$path'")
        if (!f.exists() || !f.isFile) return ToolResult(false, "File not found: '$path'")

        val startLine = if (offset < 1) 1 else offset
        val maxLines = if (limit <= 0) DEFAULT_CHUNK_LINES else minOf(limit, MAX_CHUNK_LINES)
        val bytes = f.length()

        val sb = StringBuilder()
        var lineNo = 0
        var returned = 0
        var lastReturned = startLine - 1
        var hasMore = false

        f.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                lineNo++
                if (lineNo >= startLine) {
                    val piece = if (line.length > MAX_CHUNK_CHARS) line.take(MAX_CHUNK_CHARS) + "…" else line
                    val wouldOverflow = sb.isNotEmpty() && sb.length + piece.length + 1 > MAX_CHUNK_CHARS
                    if (returned >= maxLines || wouldOverflow) {
                        hasMore = true
                        break
                    }
                    sb.append(piece).append('\n')
                    returned++
                    lastReturned = lineNo
                }
                line = reader.readLine()
            }
        }

        if (returned == 0) {
            return ToolResult(
                true,
                "(no content at offset $startLine; file is $bytes bytes with fewer than $startLine lines)"
            )
        }

        val footer = if (hasMore) {
            "\n[Showed lines $startLine\u2013$lastReturned of '$path' ($bytes bytes total). " +
                "More remains \u2014 call read_file again with offset=${lastReturned + 1}.]"
        } else {
            "\n[Showed lines $startLine\u2013$lastReturned of '$path'. End of file.]"
        }
        return ToolResult(true, sb.toString() + footer)
    }

    private fun writeFile(context: Context, path: String, content: String): ToolResult {
        val f = Workspace.resolve(context, path)
            ?: return ToolResult(
                false,
                "Invalid or disallowed path '$path'. Use a filename (saved in the workspace) " +
                    "or an absolute /sdcard path (needs All-files access)."
            )
        f.parentFile?.mkdirs()
        f.writeText(content)
        return ToolResult(true, "Wrote ${content.toByteArray().size} bytes to ${f.absolutePath}")
    }

    private fun listFiles(context: Context): ToolResult {
        val base = Workspace.dir(context)
        val files = base.walkTopDown()
            .filter { it.isFile }
            .map { "${it.relativeTo(base).path} (${it.length()} bytes)" }
            .toList()
        val header = "Workspace: ${base.absolutePath}\n"
        return if (files.isEmpty()) ToolResult(true, header + "(empty)")
        else ToolResult(true, header + files.joinToString("\n"))
    }
}
