package com.copiosa.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Smoke test against a real Standard Ebooks file when one is available locally; skipped otherwise
 * (the fixture-based [EpubParserTest] is the always-on coverage).
 */
class RealEpubSmokeTest {

    private val real = File("F:/Programming/franklin-w-dixon_the-tower-treasure.epub")

    @Test
    fun `parses the tower treasure`() {
        assumeTrue(real.isFile)
        val book = EpubParser().parse(real)

        assertEquals("The Tower Treasure", book.title)
        assertEquals("Franklin W. Dixon", book.author)
        // 24 numbered chapters; titlepage/imprint/colophon/uncopyright are skipped.
        assertEquals(24, book.chapters.size)
        assertEquals("I: The Speed Demon", book.chapters[0].title)
        assertNotNull(book.coverBytes)

        val sectioned = BookSectioner.assign(book.chapters)
        val sections = sectioned.map { it.sectionIndex }.distinct()
        assertEquals((0..sections.max()).toList(), sections)
        assertEquals((0 until sectioned.size).toList(), sectioned.map { it.ord })
        // Inline italics from chapter 1 survive as markdown.
        assertTrue(sectioned.any { it.text.contains("*could*") })
    }
}
