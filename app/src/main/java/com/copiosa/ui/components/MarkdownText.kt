package com.copiosa.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.copiosa.ui.theme.LocalReadingStyle

/**
 * A minimal Markdown renderer covering what graded essays use: headings, bullets, bold and
 * italic, and paragraphs. Deliberately dependency-free for build robustness; can be swapped for
 * a full renderer later behind this same composable.
 *
 * Pass a [colorizer] to tint individual words (e.g. saved vocabulary); when null, text renders in
 * the surrounding color.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, colorizer: WordColorizer? = null) {
    Column(modifier = modifier.fillMaxWidth()) {
        markdown.split("\n").forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(8.dp))
                isHorizontalRule(line) -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                line.startsWith("### ") -> Text(
                    inline(line.removePrefix("### "), colorizer),
                    style = readingStyle(MaterialTheme.typography.titleSmall),
                )
                line.startsWith("## ") -> Text(
                    inline(line.removePrefix("## "), colorizer),
                    style = readingStyle(MaterialTheme.typography.titleMedium),
                )
                line.startsWith("# ") -> Text(
                    inline(line.removePrefix("# "), colorizer),
                    style = readingStyle(MaterialTheme.typography.titleLarge),
                )
                line.startsWith("- ") || line.startsWith("* ") -> Text(
                    buildAnnotatedString {
                        append("•  ")
                        append(inline(line.drop(2), colorizer))
                    },
                    style = readingStyle(MaterialTheme.typography.bodyLarge),
                )
                else -> Text(
                    inline(line, colorizer),
                    style = readingStyle(MaterialTheme.typography.bodyLarge),
                )
            }
        }
    }
}

/** Applies the current reading typeface + size scale ([LocalReadingStyle]) on top of [base]. */
@Composable
fun readingStyle(base: TextStyle): TextStyle {
    val reading = LocalReadingStyle.current
    return base.copy(
        fontFamily = reading.fontFamily,
        fontSize = if (base.fontSize.isSpecified) base.fontSize * reading.scale else base.fontSize,
        lineHeight = if (base.lineHeight.isSpecified) base.lineHeight * reading.scale else base.lineHeight,
    )
}

/**
 * True for a Markdown thematic break: a line of 3+ '*', '-' or '_' (optionally space-separated).
 * Covers Gemini's habit of emitting a bare "***" as a section divider.
 */
private fun isHorizontalRule(line: String): Boolean {
    val marks = line.filterNot { it == ' ' }
    return marks.length >= 3 &&
        (marks.all { it == '*' } || marks.all { it == '-' } || marks.all { it == '_' })
}

/**
 * Parses inline emphasis into an [AnnotatedString], tinting saved words via [colorizer]. Handles
 * `**bold**`/`__bold__`, `*italic*`/`_italic_`, `` `code` ``, and backslash escapes (`\*` → literal
 * `*`). Underscore emphasis only fires at a word boundary, so `snake_case` and `el/la_x` survive.
 * An unterminated marker is emitted literally rather than swallowing the rest of the line.
 */
private fun inline(text: String, colorizer: WordColorizer? = null): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length -> {
                appendColored(text[i + 1].toString(), colorizer); i += 2
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendColored(text.substring(i + 2, end), colorizer) }
                    i = end + 2
                } else { appendColored("**", colorizer); i += 2 }
            }
            text.startsWith("__", i) && atWordStart(text, i) -> {
                val end = text.indexOf("__", i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendColored(text.substring(i + 2, end), colorizer) }
                    i = end + 2
                } else { appendColored("__", colorizer); i += 2 }
            }
            c == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendColored(text.substring(i + 1, end), colorizer) }
                    i = end + 1
                } else { appendColored("*", colorizer); i += 1 }
            }
            c == '_' && atWordStart(text, i) -> {
                val end = text.indexOf('_', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendColored(text.substring(i + 1, end), colorizer) }
                    i = end + 1
                } else { appendColored("_", colorizer); i += 1 }
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { appendColored("`", colorizer); i += 1 }
            }
            else -> {
                var j = i
                while (j < text.length && text[j] !in "*_`\\") j++
                if (j == i) j = i + 1
                appendColored(text.substring(i, j), colorizer)
                i = j
            }
        }
    }
}

/** True when the marker at [i] begins at a word boundary (start of line or after a non-alphanumeric). */
private fun atWordStart(text: String, i: Int): Boolean = i == 0 || !text[i - 1].isLetterOrDigit()

/**
 * Removes the inline-Markdown emphasis this app understands (`**bold**`/`__bold__`, `*italic*`/`_italic_`,
 * `` `code` ``, backslash escapes) down to plain text, keeping the inner content. Used to keep a
 * vocabulary card's headword and translation markup-free — those fields must read as plain words; only
 * the usage note and example sentence carry formatting. Mirrors [inline]'s tokenizer (same word-boundary
 * rules, same "emit an unterminated marker literally" behavior) so what it strips matches what would
 * otherwise render.
 */
fun stripInlineMarkdown(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length -> { out.append(text[i + 1]); i += 2 }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) { out.append(text, i + 2, end); i = end + 2 } else { out.append("**"); i += 2 }
            }
            text.startsWith("__", i) && atWordStart(text, i) -> {
                val end = text.indexOf("__", i + 2)
                if (end > i + 1) { out.append(text, i + 2, end); i = end + 2 } else { out.append("__"); i += 2 }
            }
            c == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) { out.append(text, i + 1, end); i = end + 1 } else { out.append('*'); i += 1 }
            }
            c == '_' && atWordStart(text, i) -> {
                val end = text.indexOf('_', i + 1)
                if (end > i + 1) { out.append(text, i + 1, end); i = end + 1 } else { out.append('_'); i += 1 }
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) { out.append(text, i + 1, end); i = end + 1 } else { out.append('`'); i += 1 }
            }
            else -> {
                var j = i
                while (j < text.length && text[j] !in "*_`\\") j++
                if (j == i) j = i + 1
                out.append(text, i, minOf(j, text.length)); i = j
            }
        }
    }
    return out.toString()
}

/**
 * Renders the same Markdown subset as [MarkdownText] but flattened into a single [AnnotatedString]
 * (headings/bullets become inline span styles, dividers a rule of box-drawing chars). A single
 * string means a single selectable, so a text selection's character offsets map straight back to
 * the substring — which is what lets [SelectableMessage] keep the highlight and read the selection.
 */
@Composable
fun rememberSelectableMessage(
    content: String,
    colorizer: WordColorizer?,
    scale: Float = 1f,
): AnnotatedString {
    // Heading sizes are baked in as absolute spans, so scale them to match the reading-size setting
    // (the body size is applied by the caller's Text style).
    val h1 = MaterialTheme.typography.titleLarge.fontSize * scale
    val h2 = MaterialTheme.typography.titleMedium.fontSize * scale
    val h3 = MaterialTheme.typography.titleSmall.fontSize * scale
    return remember(content, colorizer, h1, h2, h3) {
        buildMessageString(content, colorizer, h1, h2, h3)
    }
}

private fun buildMessageString(
    markdown: String,
    colorizer: WordColorizer?,
    h1: TextUnit,
    h2: TextUnit,
    h3: TextUnit,
): AnnotatedString = buildAnnotatedString {
    val lines = markdown.split("\n")
    lines.forEachIndexed { idx, raw ->
        val line = raw.trimEnd()
        fun heading(size: TextUnit, prefix: String) =
            withStyle(SpanStyle(fontSize = size, fontWeight = FontWeight.Bold)) {
                append(inline(line.removePrefix(prefix), colorizer))
            }
        when {
            line.isBlank() -> {}
            isHorizontalRule(line) -> append("──────────")
            line.startsWith("### ") -> heading(h3, "### ")
            line.startsWith("## ") -> heading(h2, "## ")
            line.startsWith("# ") -> heading(h1, "# ")
            line.startsWith("- ") || line.startsWith("* ") -> {
                append("•  "); append(inline(line.drop(2), colorizer))
            }
            else -> append(inline(line, colorizer))
        }
        if (idx < lines.lastIndex) append("\n")
    }
}

/**
 * Appends [text], then tints the spans the [colorizer] recognizes (saved words, plus a preceding
 * article when the word is a gendered noun). Appending first and styling by range — rather than
 * wrapping each word — lets a span reach back over an article that was already emitted, and the
 * [androidx.compose.ui.text.AnnotatedString.Builder.addStyle] colors merge with any surrounding
 * bold/italic spans.
 */
private fun AnnotatedString.Builder.appendColored(text: String, colorizer: WordColorizer?) {
    if (colorizer == null || colorizer.isEmpty) {
        append(text)
        return
    }
    val base = length
    val spans = colorizer.spansFor(text)
    append(text)
    for (span in spans) addStyle(colorizer.savedStyle, base + span.start, base + span.end)
}
