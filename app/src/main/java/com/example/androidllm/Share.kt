package com.example.androidllm

/**
 * Pure routing logic for the share-to-assistant feature (no Android dependencies,
 * so it can be exercised by host unit tests). Given content shared from another app,
 * it classifies the content and builds the prompt for a chosen quick action.
 */
object ShareRouting {
    enum class Kind { TEXT, URL, FILE }
    enum class Action { SUMMARIZE, KEY_POINTS, TRANSLATE, REPLY, ASK }
    data class Content(val kind: Kind, val display: String, val payload: String)

    /** Classify shared text as a bare URL or free text. */
    fun classifyText(text: String): Content {
        val trimmed = text.trim()
        val isUrl = trimmed.isNotEmpty() && !trimmed.any { it.isWhitespace() } &&
            (trimmed.startsWith("http://") || trimmed.startsWith("https://"))
        return if (isUrl) Content(Kind.URL, trimmed, trimmed)
        else Content(Kind.TEXT, trimmed, trimmed)
    }

    /** Build the seed prompt for a quick action on shared content. */
    fun buildPrompt(action: Action, c: Content): String {
        val instruction = when (action) {
            Action.SUMMARIZE -> "Summarize"
            Action.KEY_POINTS -> "List the key points from"
            Action.TRANSLATE -> "Translate into English"
            Action.REPLY -> "Draft a short reply to"
            Action.ASK -> "Answer questions about"
        }
        return when (c.kind) {
            Kind.URL -> "$instruction this web page: ${c.payload}"
            Kind.FILE -> "$instruction the attached file (\"${c.payload}\")."
            Kind.TEXT -> "$instruction the following text:\n\n${c.payload}"
        }
    }
}
