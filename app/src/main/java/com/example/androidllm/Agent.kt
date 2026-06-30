package com.example.androidllm

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The agent's sandboxed working directory. All tool file paths are resolved inside this
 * folder; paths that try to escape it (via `..` or absolute paths) are rejected.
 *
 * Lives in internal storage so it always works without permissions.
 */
object Workspace {
    fun dir(context: Context): File =
        File(context.filesDir, "workspace").apply { mkdirs() }

    /** Resolve [path] inside the workspace, or null if it would escape the sandbox. */
    fun resolve(context: Context, path: String): File? {
        val base = dir(context).canonicalFile
        val clean = path.trim().trimStart('/', '\\')
        if (clean.isEmpty()) return null
        val f = File(base, clean).canonicalFile
        return if (f.path == base.path || f.path.startsWith(base.path + File.separator)) f else null
    }
}

/** A parsed request from the model to run a tool. */
data class ToolCall(val name: String, val args: JSONObject)

/** Result of running a tool, fed back to the model and shown in the UI. */
data class ToolResult(val ok: Boolean, val output: String)

object Tools {

    val names = setOf("read_file", "write_file", "list_files")

    private const val MAX_READ_CHARS = 6000

    /** Human-readable description injected into the system prompt. */
    val systemInstructions: String = """
You can use tools to read and write text files in your workspace.

Available tools:
- read_file — read a text file. args: {"path": "<filename>"}
- write_file — create or overwrite a text file. args: {"path": "<filename>", "content": "<text>"}
- list_files — list files in the workspace. args: {}

To call a tool, reply with ONLY a single JSON object and nothing else, for example:
{"tool": "read_file", "args": {"path": "notes.txt"}}

After each tool call you will receive a message beginning with "TOOL RESULT:". Use it to
continue. Call one tool at a time, and only when needed. When you are done, reply to the
user in plain text (no JSON).
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
                if (name in names) {
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
        "read_file" -> "read_file(\"${call.args.optString("path")}\")"
        "write_file" -> "write_file(\"${call.args.optString("path")}\")"
        "list_files" -> "list_files()"
        else -> call.name
    }

    fun execute(context: Context, call: ToolCall): ToolResult = try {
        when (call.name) {
            "read_file" -> readFile(context, call.args.optString("path"))
            "write_file" -> writeFile(context, call.args.optString("path"), call.args.optString("content"))
            "list_files" -> listFiles(context)
            else -> ToolResult(false, "Unknown tool '${call.name}'")
        }
    } catch (e: Exception) {
        ToolResult(false, "Error running ${call.name}: ${e.message}")
    }

    private fun readFile(context: Context, path: String): ToolResult {
        val f = Workspace.resolve(context, path)
            ?: return ToolResult(false, "Invalid path '$path'")
        if (!f.exists() || !f.isFile) return ToolResult(false, "File not found: '$path'")
        val text = f.readText()
        val shown = if (text.length > MAX_READ_CHARS)
            text.take(MAX_READ_CHARS) + "\n…(truncated, ${text.length} chars total)"
        else text
        return ToolResult(true, shown)
    }

    private fun writeFile(context: Context, path: String, content: String): ToolResult {
        val f = Workspace.resolve(context, path)
            ?: return ToolResult(false, "Invalid path '$path'")
        f.parentFile?.mkdirs()
        f.writeText(content)
        return ToolResult(true, "Wrote ${content.toByteArray().size} bytes to '$path'.")
    }

    private fun listFiles(context: Context): ToolResult {
        val base = Workspace.dir(context)
        val files = base.walkTopDown()
            .filter { it.isFile }
            .map { "${it.relativeTo(base).path} (${it.length()} bytes)" }
            .toList()
        return if (files.isEmpty()) ToolResult(true, "(workspace is empty)")
        else ToolResult(true, files.joinToString("\n"))
    }
}
