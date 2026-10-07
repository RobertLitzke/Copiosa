package com.copiosa.domain.book

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Splitting a chapter into tracks. The invariant the whole feature rests on is that a section is
 * never split — it is the translation unit, so half of one in each of two tracks would put half a
 * model call's output in each.
 */
class BookAudioChunkerTest {

    @Test
    fun `a chapter under the cap is one track`() {
        val chunks = chunkChapter(chapter(first = 0, last = 3), sizes(0 to 4000, 1 to 4000, 2 to 4000, 3 to 4000))

        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].firstSection)
        assertEquals(3, chunks[0].lastSection)
        assertEquals(1, chunks[0].partCount)
    }

    @Test
    fun `an oversized chapter splits on section boundaries and numbers its parts`() {
        // 10 sections of 4000 chars = 40,000, against an 18,000 cap.
        val sizes = (0..9).associateWith { 4000 }
        val chunks = chunkChapter(chapter(first = 0, last = 9), sizes)

        assertEquals(3, chunks.size)
        assertEquals(listOf(0, 1, 2), chunks.map { it.partIndex })
        chunks.forEach { assertEquals(3, it.partCount) }
        // Contiguous and complete: every section lands in exactly one track.
        assertEquals((0..9).toList(), chunks.flatMap { it.sections.toList() })
        chunks.forEach { chunk ->
            val chars = chunk.sections.sumOf { sizes.getValue(it) }
            assert(chars <= CHUNK_CHARS) { "chunk $chunk holds $chars chars, over the cap" }
        }
    }

    @Test
    fun `a single section over the cap gets its own track rather than being dropped`() {
        val chunks = chunkChapter(chapter(first = 0, last = 1), sizes(0 to 50_000, 1 to 1000))

        assertEquals(2, chunks.size)
        assertEquals(0..0, chunks[0].sections)
        assertEquals(1..1, chunks[1].sections)
    }

    @Test
    fun `sections with no recorded size do not fragment the chapter`() {
        // A forward chapter whose translations have not landed: every size is absent, so nothing
        // accumulates and the chapter stays whole (the caller then finds it has no text and skips it).
        val chunks = chunkChapter(chapter(first = 4, last = 8), emptyMap())

        assertEquals(1, chunks.size)
        assertEquals(4..8, chunks[0].sections)
    }

    @Test
    fun `a track is titled after its chapter, with a part suffix only when split`() {
        val single = AudioChunk(chapterIndex = 2, partIndex = 0, partCount = 1, firstSection = 0, lastSection = 0)
        val split = AudioChunk(chapterIndex = 2, partIndex = 1, partCount = 3, firstSection = 1, lastSection = 1)

        assertEquals("El regreso", trackTitle(single, "El regreso", "Chapter 3"))
        assertEquals("El regreso (2/3)", trackTitle(split, "El regreso", "Chapter 3"))
        assertEquals("Chapter 3", trackTitle(single, null, "Chapter 3"))
        assertEquals("Chapter 3", trackTitle(single, "  ", "Chapter 3"))
    }

    private fun chapter(first: Int, last: Int) = BookChapter(
        index = 0,
        title = null,
        firstOrd = 0,
        firstSection = first,
        lastSection = last,
        progress = ChapterProgress.FULL,
    )

    private fun sizes(vararg pairs: Pair<Int, Int>): Map<Int, Int> = pairs.toMap()
}
