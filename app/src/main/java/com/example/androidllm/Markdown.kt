package com.example.androidllm

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A tiny, dependency-free Markdown renderer for Compose. Supports the subset SLMs actually emit:
 * headings, bold, italic, inline code, fenced code blocks, and bullet / numbered lists. Rendering
 * markdown (instead of showing literal asterisks) is the single biggest perceived-quality win for
 * assistant replies.
 */
object Markdown {

    /** Inline styling (bold/italic/code) of a single line into an [AnnotatedString]. */
    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = text.length
        val codeBg = SpanStyle(fontFamily = FontFamily.Monospace)
        while (i < n) {
            when {
                // `inline code`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(codeBg) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                // **bold**
                i + 1 < n && text[i] == '*' && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // *italic* or _italic_
                (text[i] == '*' || text[i] == '_') -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end > i && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

/** Render [text] as Markdown. Falls back to plain text for anything unrecognized. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current,
) {
    val lines = text.replace("\r\n", "\n").split("\n")
    Column(modifier = modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                // Fenced code block
                trimmed.startsWith("```") -> {
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        buf.append(lines[i]).append('\n'); i++
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        Text(
                            buf.toString().trimEnd('\n'),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = color
                        )
                    }
                    i++
                }
                // Headings
                trimmed.startsWith("### ") -> {
                    Text(Markdown.inline(trimmed.removePrefix("### ")), color = color,
                        style = MaterialTheme.typography.titleSmall)
                    i++
                }
                trimmed.startsWith("## ") -> {
                    Text(Markdown.inline(trimmed.removePrefix("## ")), color = color,
                        style = MaterialTheme.typography.titleMedium)
                    i++
                }
                trimmed.startsWith("# ") -> {
                    Text(Markdown.inline(trimmed.removePrefix("# ")), color = color,
                        style = MaterialTheme.typography.titleLarge)
                    i++
                }
                // Bullet list
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                        Text("•  ", color = color, style = MaterialTheme.typography.bodyMedium)
                        Text(Markdown.inline(trimmed.substring(2)), color = color,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    i++
                }
                // Numbered list (keep the number)
                trimmed.matches(Regex("^\\d+\\. .*")) -> {
                    val dot = trimmed.indexOf(". ")
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                        Text(trimmed.substring(0, dot + 1) + " ", color = color,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(Markdown.inline(trimmed.substring(dot + 2)), color = color,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    i++
                }
                trimmed.isEmpty() -> { Spacer(Modifier.width(1.dp)); i++ }
                else -> {
                    Text(Markdown.inline(line), color = color,
                        style = MaterialTheme.typography.bodyMedium)
                    i++
                }
            }
        }
    }
}
