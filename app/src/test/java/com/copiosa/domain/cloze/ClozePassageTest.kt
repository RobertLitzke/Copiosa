package com.copiosa.domain.cloze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClozePassageTest {

    private val text =
        "Aquella mañana el viejo pescador salió de su casa muy temprano. Caminó despacio hasta el " +
            "puerto, donde lo esperaba su barca azul. El mar estaba tranquilo y el cielo no tenía " +
            "ninguna nube. Pensó que sería un buen día para trabajar."

    private val cards = mapOf(
        "pescador" to 1L, "casa" to 2L, "puerto" to 3L, "barca" to 4L, "mar" to 5L, "cielo" to 6L,
        "nube" to 7L, "aquella" to 8L, "pensó" to 9L,
    )

    private fun build(seed: Int = 1, forms: Map<String, Long> = cards, source: String = text) =
        ClozePassage.build(source, "That morning…", forms, extras = listOf("montaña", "puerto", "río", "a pesar de"), Random(seed))

    @Test
    fun `blanks are the learner's words, spaced out, in passage order`() {
        val item = requireNotNull(build())
        assertTrue(item.blanks.size in ClozePassage.MIN_BLANKS..ClozePassage.MAX_BLANKS)
        assertEquals(item.blanks.sortedBy { it.start }, item.blanks)
        item.blanks.forEach { blank ->
            assertEquals(blank.answer, text.substring(blank.start, blank.end))
            assertEquals(cards.getValue(blank.answer.lowercase()), blank.cardId)
        }
        assertEquals(item.blanks.size, item.blanks.map { it.cardId }.distinct().size)
    }

    @Test
    fun `a sentence's first word is never blanked`() {
        repeat(20) { seed ->
            val answers = requireNotNull(build(seed)).blanks.map { it.answer }
            assertFalse("Aquella" in answers)
            assertFalse("Pensó" in answers)
        }
    }

    @Test
    fun `the bank holds every answer plus words that fit nowhere`() {
        val item = requireNotNull(build())
        val answers = item.blanks.map { it.answer.lowercase() }
        assertTrue(item.bank.containsAll(answers))
        val spare = item.bank - answers.toSet()
        assertEquals(ClozePassage.EXTRAS, spare.size)
        // Never a word the passage already uses, and never a phrase.
        assertTrue(spare.all { it == "montaña" || it == "río" })
    }

    @Test
    fun `too few of the learner's words, or the wrong length, is no passage`() {
        assertNull(build(forms = mapOf("casa" to 2L, "mar" to 5L)))
        assertNull(build(source = "El mar estaba tranquilo y el cielo no tenía ninguna nube."))
    }

    @Test
    fun `a word that appears twice is left alone`() {
        val twice = text.replace("su barca azul", "su casa azul")
        repeat(20) { seed ->
            val item = build(seed, source = twice) ?: return@repeat
            assertFalse("casa" in item.blanks.map { it.answer })
        }
    }

    @Test
    fun `a blank takes its own word in any case and nothing else`() {
        val blank = PassageBlank(0, 4, "casa", 2L)
        assertTrue(ClozePassage.fits(blank, "Casa"))
        assertFalse(ClozePassage.fits(blank, "mar"))
        assertFalse(ClozePassage.fits(blank, null))
    }
}
