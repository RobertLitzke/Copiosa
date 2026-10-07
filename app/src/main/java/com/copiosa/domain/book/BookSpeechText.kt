package com.copiosa.domain.book

import com.copiosa.data.epub.listItemBody
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.model.BookDirection

/**
 * Turning stored book blocks into something worth speaking.
 *
 * Paragraphs are stored as markdown in a string (see EpubParser), which the reader renders but a TTS
 * engine would read out loud: `##` becomes "hash hash", a pipe table becomes a wall of punctuation.
 * Everything here strips that back down to prose.
 *
 * Structure is read from `originalText` and content from whichever side the direction selects — the
 * same rule `BlockMarkup` exists to enforce, and for the same reason: a translated block has been
 * trimmed and may have lost its markers.
 */

/** Blocks that have no spoken form at all and are dropped whole. */
private val UNSPEAKABLE = setOf(
    // A filename.
    BookParagraphEntity.KIND_IMAGE,
    // A GFM pipe table read aloud is punctuation, not speech.
    BookParagraphEntity.KIND_TABLE,
    // Citations and asides. In the reader they are a tap away; in a chapter read end to end they
    // would interrupt the narrative at the point the marker happens to fall.
    BookParagraphEntity.KIND_NOTE,
)

private val FOOTNOTE_MARKER = Regex("""\[\^[^\]]{1,16}]""")
private val EMPHASIS = Regex("""[*_]{1,3}""")
private val INLINE_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val BLANK_LINES = Regex("""\n{3,}""")

/**
 * The speakable text of [paragraphs] — a chapter, or the slice of one that makes up a track.
 *
 * Returns blank when nothing survives (a plate section of nothing but images, or a forward chapter
 * the translation frontier has not reached), which callers treat as "no track here".
 */
fun speechTextOf(paragraphs: List<BookParagraphEntity>, direction: BookDirection): String =
    paragraphs
        .filterNot { it.kind in UNSPEAKABLE }
        .mapNotNull { row ->
            val text = row.targetText(direction)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            when (row.kind) {
                // Reuse the table of contents' heading cleaner: same `##`-and-emphasis problem.
                BookParagraphEntity.KIND_HEADING -> chapterTitleOf(text)
                // Read structurally from the original, so a translation that lost its bullet still loses
                // one if it kept it.
                BookParagraphEntity.KIND_LIST -> listItemBody(text).cleanForSpeech()
                else -> text.cleanForSpeech()
            }?.takeIf { it.isNotBlank() }
        }
        // Blank-line separated, which TtsEngine.splitForSpeech already treats as a hard boundary, so
        // each block lands with a full pause rather than running into the next.
        .joinToString("\n\n")

/**
 * Strips the inline markdown a sentence can carry. Emphasis markers would be spoken as asterisks;
 * a `[^3]` marker mid-sentence would be read as a bracketed number; a link's target is not speech.
 */
private fun String.cleanForSpeech(): String = this
    .replace(FOOTNOTE_MARKER, "")
    .replace(INLINE_LINK) { it.groupValues[1] }
    .replace(EMPHASIS, "")
    .replace(BLANK_LINES, "\n\n")
    .trim()
