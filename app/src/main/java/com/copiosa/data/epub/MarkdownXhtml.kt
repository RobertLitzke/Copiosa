package com.copiosa.data.epub

/**
 * Markdown → XHTML, for the EPUB export. The inverse of [EpubParser]'s `inlineMarkdown`.
 *
 * Escaping is per *literal run* rather than over the finished string: each piece of source text is
 * escaped as it is appended and the generated tags never pass through the escaper. That is what
 * makes the order problem go away — a "convert emphasis, then escape" pass would escape its own
 * markup, and a "escape, then convert" pass has to reason about `&lt;` looking like a marker.
 * Anyone tempted to collapse this into a regex should read that sentence twice.
 */

/**
 * Escapes a string for an XML text node.
 *
 * Escapes exactly once: jsoup decoded entities at import, so a stored paragraph holds a literal `&`.
 * C0 control characters are *dropped* rather than escaped — XML 1.0 forbids them outright and
 * `&#0;` is illegal too, so a single stray control character out of an LLM would otherwise make the
 * whole file unparseable while being invisible in a diff.
 */
fun xmlText(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in text) {
        when {
            ch == '&' -> out.append("&amp;")
            ch == '<' -> out.append("&lt;")
            ch == '>' -> out.append("&gt;")
            ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
            else -> out.append(ch)
        }
    }
    return out.toString()
}

/** Escapes a string for an XML attribute value. */
fun xmlAttr(text: String): String =
    xmlText(text).replace("\"", "&quot;").replace("'", "&apos;")

/**
 * Renders one stored paragraph's markdown as XHTML inline content (no `<p>` wrapper).
 *
 * A third mirror of `com.copiosa.ui.components.inline` (MarkdownText.kt) and its sibling
 * `stripInlineMarkdown`, token for token: `**`/`__` bold, `*`/`_` italic with underscores only at a
 * word boundary so `snake_case` survives, `` `code` ``, backslash escapes, and an unterminated
 * marker emitted literally rather than swallowing the rest of the line. Keeping the three in step
 * means the exported file says exactly what the reader showed.
 *
 * Like the reader, emphasis does **not** nest: the content of a `**…**` run is taken verbatim, so
 * `**a *b* c**` renders bold with literal asterisks around `b`, in the file just as on screen.
 *
 * [EpubParser] only ever *produces* `*`/`**`, but `translatedText` is LLM output and does carry
 * `_`, `__` and backticks, so all five markers are handled.
 */
fun inlineXhtml(markdown: String, notes: Set<String> = emptySet()): String {
    val out = StringBuilder(markdown.length + 16)
    var i = 0
    while (i < markdown.length) {
        val c = markdown[i]
        when {
            c == '\\' && i + 1 < markdown.length -> {
                out.append(xmlText(markdown[i + 1].toString())); i += 2
            }
            markdown.startsWith("[^", i) -> {
                val end = markdown.indexOf(']', i + 2)
                val label = if (end > i + 2) markdown.substring(i + 2, end) else null
                // Only a label whose body is actually being written becomes a link: an <a> pointing
                // at a fragment that isn't in the file is an epubcheck error, and a marker the model
                // invented is exactly how one would get here.
                if (label != null && label in notes) {
                    out.append("<a class=\"noteref\" epub:type=\"noteref\" href=\"#")
                        .append(xmlAttr(noteId(label))).append("\"><sup>")
                        .append(xmlText(label)).append("</sup></a>")
                    i = end + 1
                } else {
                    out.append(xmlText("[^")); i += 2
                }
            }
            markdown.startsWith("**", i) -> i = emphasis(markdown, i, "**", "strong", out)
            markdown.startsWith("__", i) && atWordStart(markdown, i) -> i = emphasis(markdown, i, "__", "strong", out)
            c == '*' -> i = emphasis(markdown, i, "*", "em", out)
            c == '_' && atWordStart(markdown, i) -> i = emphasis(markdown, i, "_", "em", out)
            c == '`' -> i = emphasis(markdown, i, "`", "code", out)
            else -> {
                var j = i
                while (j < markdown.length && markdown[j] !in MARKERS) j++
                if (j == i) j = i + 1
                out.append(xmlText(markdown.substring(i, minOf(j, markdown.length))))
                i = j
            }
        }
    }
    return out.toString()
}

/**
 * A `## Title` HEADING row as an `<hN>` element. The level is the leading `#` count clamped to
 * 1..6; a row with no hashes at all becomes `<h2>`, matching what [EpubParser] emits for a chapter
 * title. [attributes] is appended to the open tag verbatim and must already be escaped.
 */
fun headingXhtml(markdown: String, attributes: String = "", notes: Set<String> = emptySet()): String {
    val trimmed = markdown.trim()
    var hashes = 0
    while (hashes < trimmed.length && trimmed[hashes] == '#') hashes++
    val level = if (hashes == 0) 2 else hashes.coerceIn(1, 6)
    return "<h$level$attributes>${inlineXhtml(headingBody(trimmed), notes)}</h$level>"
}

/**
 * A heading row's own markdown, with the leading `#` markers taken off. Rendering the counterpart
 * of a heading anywhere other than in an `<hN>` needs this: `##` is only a marker at the start of a
 * heading row, and passing it through [inlineXhtml] would print the hashes as body text.
 */
fun headingBody(markdown: String): String = markdown.trim().trimStart('#').trimStart()

/** The `id` a note body gets, and the fragment its markers link to. Sanitised to a valid XML name. */
fun noteId(label: String): String = "note-" + label.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

/**
 * Emits the run delimited by [marker] as [tag], or the marker itself when there is no closing one.
 * Returns the index to continue from. The `end > i + 1` test is the reader's, verbatim — for a
 * two-character marker it admits an empty run (`****`), for a one-character marker it does not.
 */
private fun emphasis(text: String, i: Int, marker: String, tag: String, out: StringBuilder): Int {
    val end = text.indexOf(marker, i + marker.length)
    if (end > i + 1) {
        out.append('<').append(tag).append('>')
            .append(xmlText(text.substring(i + marker.length, end)))
            .append("</").append(tag).append('>')
        return end + marker.length
    }
    out.append(xmlText(marker))
    return i + marker.length
}

/** True when the marker at [i] begins at a word boundary (start, or after a non-alphanumeric). */
private fun atWordStart(text: String, i: Int): Boolean = i == 0 || !text[i - 1].isLetterOrDigit()

/**
 * Every character that can begin a token, and so must stop a run of literal text.
 *
 * A marker missing from here is invisible: the literal scanner runs straight past it and the branch
 * that would have handled it never gets a chance to look. Mirrored in `MarkdownText`'s two
 * tokenizers — a marker added to one of the three belongs in all of them.
 */
internal const val MARKERS = "*_`\\["
