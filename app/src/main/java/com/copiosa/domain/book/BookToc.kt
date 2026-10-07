package com.copiosa.domain.book

import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.data.local.ChapterHeading
import com.copiosa.data.local.ChapterSpan
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.BookDownloadMode

/**
 * The reader's two languages, read off a paragraph row. Which stored column holds which language
 * depends on the book's direction: a FORWARD book's target text is the LLM's output, a REVERSE
 * book's is the EPUB's own text. The reader is written entirely in terms of these two, so the
 * direction is handled once, here, rather than at every render site.
 */

/** The text in the language being learned; null in FORWARD past the translation frontier. */
fun BookParagraphEntity.targetText(direction: BookDirection): String? =
    if (direction == BookDirection.FORWARD) translatedText else originalText

/** The learner's own language; null in REVERSE until (and unless) a gloss pass runs. */
fun BookParagraphEntity.nativeText(direction: BookDirection): String? =
    if (direction == BookDirection.FORWARD) originalText else translatedText

/**
 * Whether this book should be having sections translated at all.
 *
 * A forward book always should — untranslated means unreadable. A reverse book is readable the
 * moment it is imported, so its gloss pass is opt-in, and any download mode above OFF is that
 * opt-in (a reverse book is imported at OFF for exactly this reason).
 */
val BookEntity.wantsTranslation: Boolean
    get() = direction == BookDirection.FORWARD || downloadMode != BookDownloadMode.OFF

/**
 * Whether the book reads end to end in the target language, and so is worth exporting as an EPUB.
 *
 * Not simply `translatedSections >= totalSections`: a reverse book that never opted into a gloss
 * *is* its own target text and is complete from the moment it is imported, so it would otherwise be
 * locked out of an export it needs nothing for.
 *
 * Taken in the loose form because the two menu homes have different data to hand — the reader has a
 * [BookEntity], the library row has its own projection — and a gating rule that exists twice is one
 * that drifts.
 */
fun bookFullyReadable(wantsTranslation: Boolean, translatedSections: Int, totalSections: Int): Boolean =
    !wantsTranslation || translatedSections >= totalSections

/** Both languages present end to end — the precondition for the bilingual export variant. */
fun bookFullyBilingual(wantsTranslation: Boolean, translatedSections: Int, totalSections: Int): Boolean =
    wantsTranslation && translatedSections >= totalSections

val BookEntity.fullyReadable: Boolean
    get() = bookFullyReadable(wantsTranslation, translatedSections, totalSections)

val BookEntity.fullyBilingual: Boolean
    get() = bookFullyBilingual(wantsTranslation, translatedSections, totalSections)

/** How much of a chapter the reader can actually read. */
enum class ChapterProgress {
    /** Nothing readable yet — the translation frontier has not reached this chapter. */
    NONE,

    /** The frontier stops partway through: the chapter opens readable and trails off. */
    PARTIAL,

    /** Readable end to end. */
    FULL,
}

/** One entry in the reader's table of contents. */
data class BookChapter(
    val index: Int,
    /** Null when the chapter has no heading block; the UI falls back to "Chapter N". */
    val title: String?,
    val firstOrd: Int,
    val firstSection: Int,
    val lastSection: Int,
    val progress: ChapterProgress,
) {
    /** Whether tapping this chapter goes anywhere — i.e. whether there is text to land on. */
    val jumpable: Boolean get() = progress != ChapterProgress.NONE
}

/**
 * A chapter's readable extent against the frontier. [frontier] is `BookEntity.translatedSections`,
 * a *count*, so sections `0..frontier-1` are done — hence the strict comparisons.
 */
fun chapterProgress(frontier: Int, firstSection: Int, lastSection: Int): ChapterProgress = when {
    frontier > lastSection -> ChapterProgress.FULL
    frontier > firstSection -> ChapterProgress.PARTIAL
    else -> ChapterProgress.NONE
}

/**
 * A chapter title from its heading block: EpubParser writes these as "## Title", and the LLM is
 * asked to keep the `##`, so both languages arrive marked up. Emphasis markers are stripped too —
 * a heading rendered into a one-line list row should not show stray asterisks. Null if nothing is
 * left, so the caller can fall back to "Chapter N".
 */
fun chapterTitleOf(headingText: String): String? = headingText
    .trim()
    .trimStart('#')
    .replace(EMPHASIS, "")
    .replace(WHITESPACE, " ")
    .trim()
    .ifBlank { null }

/**
 * Assembles the table of contents from the two DAO projections.
 *
 * The direction decides both which language titles come from and what "readable" means. A REVERSE
 * book *is* its own target-language text, so every chapter is readable from the moment it is
 * imported and the frontier is irrelevant — its translation pass only ever adds a native gloss.
 */
fun buildToc(
    direction: BookDirection,
    frontier: Int,
    spans: List<ChapterSpan>,
    headings: List<ChapterHeading>,
): List<BookChapter> {
    // Headings arrive in reading order, so the first one seen for a chapter is its title.
    val titles = headings
        .groupBy { it.chapterIndex }
        .mapValues { (_, rows) ->
            val first = rows.first()
            val text = when (direction) {
                // The heading's own translation, once its section has been through the LLM.
                BookDirection.FORWARD -> first.translatedText ?: first.originalText
                BookDirection.REVERSE -> first.originalText
            }
            chapterTitleOf(text)
        }

    return spans.map { span ->
        BookChapter(
            index = span.chapterIndex,
            title = titles[span.chapterIndex],
            firstOrd = span.firstOrd,
            firstSection = span.firstSection,
            lastSection = span.lastSection,
            progress = when (direction) {
                BookDirection.FORWARD -> chapterProgress(frontier, span.firstSection, span.lastSection)
                BookDirection.REVERSE -> ChapterProgress.FULL
            },
        )
    }
}

/** The chapter containing [ord] — the last one that starts at or before it; -1 if there are none. */
fun chapterIndexAt(chapters: List<BookChapter>, ord: Int): Int =
    chapters.lastOrNull { it.firstOrd <= ord }?.index ?: -1

private val EMPHASIS = Regex("[*_]{1,3}")
private val WHITESPACE = Regex("\\s+")
