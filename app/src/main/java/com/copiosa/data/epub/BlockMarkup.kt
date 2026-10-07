package com.copiosa.data.epub

/**
 * Reading the *structure* back out of a stored block's markdown.
 *
 * The rule every caller here exists to enforce: **structure comes from `originalText`, content from
 * whichever text is being shown.** A translated body cannot be trusted to carry its own markers.
 * Two independent reasons, and the first is not a matter of prompting:
 *
 *  - `BookSectionAligner` trims every translated paragraph on both ends
 *    (`raw.substring(...).trim()`), so a list item's leading indent — which is how nesting depth is
 *    stored — is *unconditionally* removed from `translatedText`. Read depth off the translation
 *    and every nested list in a translated section flattens to depth 0.
 *  - The model may renumber an ordinal, drop a `[^3]:` prefix, or localise a `- ` bullet, however
 *    firmly the marker contract asks it not to.
 *
 * This is the same defence the codebase already applies to headings, where `chapterTitleOf` and
 * `headingBody` both strip `#` markers rather than trusting them to be there.
 */

/** How a [BlockKind.LIST] row sits in its list: nesting depth, and its marker. */
data class ListMarkup(val depth: Int, val ordered: Boolean, val ordinal: Int)

/** Two spaces of indent per nesting level, as [EpubParser] writes it. */
private const val INDENT = 2

private val ORDERED_MARKER = Regex("""^(\d{1,3})[.)]\s+""")
private val BULLET_MARKER = Regex("""^[-*+]\s+""")
private val NOTE_PREFIX = Regex("""^\[\^([^\]]{1,16})]:\s*""")
private val IMAGE_MARKUP = Regex("""^!\[[^\]]*]\(([^)]+)\)\s*$""")

/**
 * The list structure encoded in [originalText], or null when there is none. Pass the *original*
 * text — a translated one has had its indent trimmed away and may have lost its marker entirely.
 */
fun listMarkupOf(originalText: String): ListMarkup? {
    val depth = originalText.takeWhile { it == ' ' }.length / INDENT
    val body = originalText.trimStart(' ')
    ORDERED_MARKER.find(body)?.let {
        return ListMarkup(depth, ordered = true, ordinal = it.groupValues[1].toIntOrNull() ?: 1)
    }
    if (BULLET_MARKER.containsMatchIn(body)) return ListMarkup(depth, ordered = false, ordinal = 0)
    // A stored LIST row whose marker the model ate is still a list item; it just has no marker to
    // read. Treating it as depth 0 beats rendering it as a stray paragraph.
    return null
}

/** A list item's text with its indent and `- ` / `1. ` marker removed, whichever text you pass. */
fun listItemBody(text: String): String {
    val body = text.trimStart(' ')
    ORDERED_MARKER.find(body)?.let { return body.substring(it.value.length) }
    BULLET_MARKER.find(body)?.let { return body.substring(it.value.length) }
    return body
}

/** The label of a `[^3]: …` note row, read from its *original* text. Null when there is no prefix. */
fun noteLabelOf(originalText: String): String? =
    NOTE_PREFIX.find(originalText.trimStart())?.groupValues?.get(1)

/** A note row's body, with any `[^3]: ` prefix removed — the model often drops or moves it. */
fun noteBodyOf(text: String): String {
    val trimmed = text.trimStart()
    return NOTE_PREFIX.find(trimmed)?.let { trimmed.substring(it.value.length) } ?: trimmed
}

/** The image key a `![](2.jpg)` row names, or null when the row isn't an image reference. */
fun imageKeyOf(originalText: String): String? =
    IMAGE_MARKUP.find(originalText.trim())?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
