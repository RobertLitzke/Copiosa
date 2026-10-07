package com.copiosa.domain.book

import com.copiosa.data.local.ChapterHeading
import com.copiosa.data.local.ChapterSpan
import com.copiosa.domain.model.BookDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table of contents is derived rather than stored — chapters come from grouping paragraph rows,
 * titles from the heading block each chapter opens with, and readability from the translation
 * frontier. These are the boundaries that derivation can get wrong.
 */
class BookTocTest {

    // --- export gating -----------------------------------------------------

    @Test
    fun `a forward book is exportable only once it is fully translated`() {
        assertFalse(bookFullyReadable(wantsTranslation = true, translatedSections = 5, totalSections = 12))
        assertTrue(bookFullyReadable(wantsTranslation = true, translatedSections = 12, totalSections = 12))
    }

    /**
     * The case a bare `translatedSections >= totalSections` gets wrong: a reverse book with the
     * gloss switched off is its own target text and complete the moment it is imported.
     */
    @Test
    fun `a reverse book without a gloss is exportable with nothing translated`() {
        assertTrue(bookFullyReadable(wantsTranslation = false, translatedSections = 0, totalSections = 12))
    }

    @Test
    fun `bilingual needs both languages end to end`() {
        assertTrue(bookFullyBilingual(wantsTranslation = true, translatedSections = 12, totalSections = 12))
        assertFalse(bookFullyBilingual(wantsTranslation = true, translatedSections = 11, totalSections = 12))
        // Readable, but there is no second language to pair with.
        assertFalse(bookFullyBilingual(wantsTranslation = false, translatedSections = 0, totalSections = 12))
    }

    // --- chapterProgress ---------------------------------------------------

    @Test
    fun `a chapter the frontier has not reached is unreadable`() {
        assertEquals(ChapterProgress.NONE, chapterProgress(frontier = 0, firstSection = 0, lastSection = 2))
        assertEquals(ChapterProgress.NONE, chapterProgress(frontier = 3, firstSection = 3, lastSection = 5))
    }

    @Test
    fun `a chapter the frontier stops inside is partial`() {
        assertEquals(ChapterProgress.PARTIAL, chapterProgress(frontier = 1, firstSection = 0, lastSection = 2))
        assertEquals(ChapterProgress.PARTIAL, chapterProgress(frontier = 2, firstSection = 0, lastSection = 2))
    }

    @Test
    fun `a chapter is only full once the frontier is past its last section`() {
        // The frontier is a *count*: 3 means sections 0..2 are done, so a chapter ending at 2 is
        // complete — while 2 (sections 0..1 done) leaves its last section untranslated.
        assertEquals(ChapterProgress.PARTIAL, chapterProgress(frontier = 2, firstSection = 0, lastSection = 2))
        assertEquals(ChapterProgress.FULL, chapterProgress(frontier = 3, firstSection = 0, lastSection = 2))
    }

    @Test
    fun `a single-section chapter goes straight from none to full`() {
        assertEquals(ChapterProgress.NONE, chapterProgress(frontier = 4, firstSection = 4, lastSection = 4))
        assertEquals(ChapterProgress.FULL, chapterProgress(frontier = 5, firstSection = 4, lastSection = 4))
    }

    // --- chapterTitleOf ----------------------------------------------------

    @Test
    fun `strips heading markers and emphasis`() {
        assertEquals("El despertar", chapterTitleOf("## El despertar"))
        assertEquals("El despertar", chapterTitleOf("### *El despertar*"))
        assertEquals("El despertar", chapterTitleOf("## **El despertar**"))
    }

    @Test
    fun `collapses whitespace and trims`() {
        assertEquals("Capítulo uno: la partida", chapterTitleOf("##   Capítulo uno:\n la partida  "))
    }

    @Test
    fun `a heading with nothing left is no title at all`() {
        assertNull(chapterTitleOf("##"))
        assertNull(chapterTitleOf("  ***  "))
    }

    // --- buildToc ----------------------------------------------------------

    @Test
    fun `forward titles prefer the translation and fall back to the original`() {
        val toc = buildToc(
            direction = BookDirection.FORWARD,
            frontier = 1,
            spans = listOf(span(0, firstOrd = 0, 0, 0), span(1, firstOrd = 10, 1, 1)),
            headings = listOf(
                ChapterHeading(0, ord = 0, originalText = "## The Awakening", translatedText = "## El despertar"),
                // Past the frontier, so this one has no translation yet.
                ChapterHeading(1, ord = 10, originalText = "## The Journey", translatedText = null),
            ),
        )
        assertEquals(listOf("El despertar", "The Journey"), toc.map { it.title })
    }

    @Test
    fun `only the first heading in a chapter is its title`() {
        val toc = buildToc(
            direction = BookDirection.FORWARD,
            frontier = 9,
            spans = listOf(span(0, firstOrd = 0, 0, 1)),
            headings = listOf(
                ChapterHeading(0, ord = 0, originalText = "## Chapter One", translatedText = null),
                ChapterHeading(0, ord = 7, originalText = "## A Subheading", translatedText = null),
            ),
        )
        assertEquals(listOf("Chapter One"), toc.map { it.title })
    }

    @Test
    fun `a chapter with no heading row has no title`() {
        val toc = buildToc(
            direction = BookDirection.FORWARD,
            frontier = 9,
            spans = listOf(span(0, firstOrd = 0, 0, 0), span(1, firstOrd = 4, 1, 1)),
            headings = listOf(ChapterHeading(1, ord = 4, originalText = "## Two", translatedText = null)),
        )
        assertNull(toc[0].title)
        assertEquals("Two", toc[1].title)
    }

    @Test
    fun `forward chapters past the frontier are not jumpable`() {
        val toc = buildToc(
            direction = BookDirection.FORWARD,
            frontier = 1,
            spans = listOf(span(0, firstOrd = 0, 0, 0), span(1, firstOrd = 10, 1, 2)),
            headings = emptyList(),
        )
        assertEquals(ChapterProgress.FULL, toc[0].progress)
        assertTrue(toc[0].jumpable)
        assertEquals(ChapterProgress.NONE, toc[1].progress)
        assertFalse(toc[1].jumpable)
    }

    @Test
    fun `every reverse chapter is readable regardless of the frontier`() {
        // The EPUB is already the text being studied, so nothing waits on the LLM.
        val toc = buildToc(
            direction = BookDirection.REVERSE,
            frontier = 0,
            spans = listOf(span(0, firstOrd = 0, 0, 1), span(1, firstOrd = 20, 2, 4)),
            headings = listOf(
                // A gloss exists for this heading; the reader still reads the original.
                ChapterHeading(0, ord = 0, originalText = "## El despertar", translatedText = "## The Awakening"),
            ),
        )
        assertTrue(toc.all { it.progress == ChapterProgress.FULL })
        assertTrue(toc.all { it.jumpable })
        assertEquals("El despertar", toc[0].title)
    }

    @Test
    fun `spans carry through to the jump target`() {
        val toc = buildToc(
            direction = BookDirection.FORWARD,
            frontier = 99,
            spans = listOf(span(0, firstOrd = 0, 0, 1), span(1, firstOrd = 137, 2, 3)),
            headings = emptyList(),
        )
        assertEquals(listOf(0, 137), toc.map { it.firstOrd })
        assertEquals(listOf(0, 1), toc.map { it.index })
    }

    // --- chapterIndexAt ----------------------------------------------------

    @Test
    fun `the current chapter is the last one starting at or before the position`() {
        val toc = buildToc(
            direction = BookDirection.FORWARD,
            frontier = 99,
            spans = listOf(span(0, firstOrd = 0, 0, 0), span(1, firstOrd = 10, 1, 1), span(2, firstOrd = 25, 2, 2)),
            headings = emptyList(),
        )
        assertEquals(0, chapterIndexAt(toc, 0))
        assertEquals(0, chapterIndexAt(toc, 9))
        assertEquals(1, chapterIndexAt(toc, 10))
        assertEquals(2, chapterIndexAt(toc, 900))
    }

    @Test
    fun `an empty table of contents has no current chapter`() {
        assertEquals(-1, chapterIndexAt(emptyList(), 5))
    }

    private fun span(index: Int, firstOrd: Int, firstSection: Int, lastSection: Int) =
        ChapterSpan(chapterIndex = index, firstOrd = firstOrd, firstSection = firstSection, lastSection = lastSection)
}
