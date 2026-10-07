package com.copiosa.domain.book

import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.model.BookDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What reaches the TTS engine. Every case here is markdown the reader renders and a voice would
 * otherwise read out loud as punctuation.
 */
class BookSpeechTextTest {

    @Test
    fun `heading markers and emphasis are stripped`() {
        val text = speechTextOf(
            listOf(
                para(BookParagraphEntity.KIND_HEADING, "## **El regreso**"),
                para(BookParagraphEntity.KIND_PARA, "Volvió *muy* tarde."),
            ),
            BookDirection.REVERSE,
        )

        assertEquals("El regreso\n\nVolvió muy tarde.", text)
    }

    @Test
    fun `images, tables and notes are dropped whole`() {
        val text = speechTextOf(
            listOf(
                para(BookParagraphEntity.KIND_IMAGE, "![](2.jpg)"),
                para(BookParagraphEntity.KIND_TABLE, "| a | b |\n| --- | --- |\n| 1 | 2 |"),
                para(BookParagraphEntity.KIND_NOTE, "[^3]: Véase el capítulo anterior."),
                para(BookParagraphEntity.KIND_PARA, "El único texto hablado."),
            ),
            BookDirection.REVERSE,
        )

        assertEquals("El único texto hablado.", text)
    }

    @Test
    fun `list markers and footnote references are removed from the spoken line`() {
        val text = speechTextOf(
            listOf(
                para(BookParagraphEntity.KIND_LIST, "  - Primero[^1]"),
                para(BookParagraphEntity.KIND_LIST, "2. Segundo"),
            ),
            BookDirection.REVERSE,
        )

        assertEquals("Primero\n\nSegundo", text)
        assertFalse("[^1]" in text)
    }

    @Test
    fun `inline links keep their text and lose their target`() {
        val text = speechTextOf(
            listOf(para(BookParagraphEntity.KIND_PARA, "Consulta [la nota](http://ejemplo.com) ahora.")),
            BookDirection.REVERSE,
        )

        assertEquals("Consulta la nota ahora.", text)
    }

    @Test
    fun `a forward book speaks its translation, a reverse book its own text`() {
        val row = BookParagraphEntity(
            bookId = 1,
            ord = 0,
            chapterIndex = 0,
            sectionIndex = 0,
            kind = BookParagraphEntity.KIND_PARA,
            originalText = "He came back late.",
            translatedText = "Volvió tarde.",
        )

        assertEquals("Volvió tarde.", speechTextOf(listOf(row), BookDirection.FORWARD))
        assertEquals("He came back late.", speechTextOf(listOf(row), BookDirection.REVERSE))
    }

    @Test
    fun `an untranslated forward chapter yields nothing rather than the wrong language`() {
        val row = BookParagraphEntity(
            bookId = 1,
            ord = 0,
            chapterIndex = 0,
            sectionIndex = 0,
            kind = BookParagraphEntity.KIND_PARA,
            originalText = "Past the frontier.",
            translatedText = null,
        )

        assertTrue(speechTextOf(listOf(row), BookDirection.FORWARD).isBlank())
    }

    private fun para(kind: String, text: String) = BookParagraphEntity(
        bookId = 1,
        ord = 0,
        chapterIndex = 0,
        sectionIndex = 0,
        kind = kind,
        originalText = text,
        translatedText = null,
    )
}
