package com.copiosa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClozeTest {

    @Test
    fun hasCloze_detectsMarkup() {
        assertTrue(Cloze.hasCloze("Yo {{c1::soy}} feliz"))
        assertFalse(Cloze.hasCloze("Yo soy feliz"))
        assertFalse(Cloze.hasCloze(null))
    }

    @Test
    fun source_picksWhicheverSideHasMarkup() {
        assertEquals("Yo {{c1::soy}}", Cloze.source("Yo {{c1::soy}}", "I am"))
        assertEquals("Yo {{c1::soy}}", Cloze.source("I am", "Yo {{c1::soy}}"))
        assertEquals(null, Cloze.source("Yo soy", "I am"))
    }

    @Test
    fun question_blanksActiveAndFillsOthers() {
        val text = "Ayer {{c1::fui}} al cine y {{c2::comí}} palomitas"
        assertEquals("Ayer ${Cloze.BLANK} al cine y comí palomitas", Cloze.question(text, activeOrd = 1))
        assertEquals("Ayer fui al cine y ${Cloze.BLANK} palomitas", Cloze.question(text, activeOrd = 2))
    }

    @Test
    fun question_nullOrdBlanksEveryDeletion() {
        val text = "{{c1::Soy}} muy {{c2::feliz}}"
        assertEquals("${Cloze.BLANK} muy ${Cloze.BLANK}", Cloze.question(text, activeOrd = null))
    }

    @Test
    fun question_showsHintInBrackets() {
        val text = "Yo {{c1::soy::ser/estar}} feliz"
        assertEquals("Yo [ser/estar] feliz", Cloze.question(text, activeOrd = 1))
    }

    @Test
    fun answer_fillsEverything() {
        val text = "Ayer {{c1::fui}} al cine y {{c2::comí::comer}} palomitas"
        assertEquals("Ayer fui al cine y comí palomitas", Cloze.answer(text))
    }

    @Test
    fun activeAnswers_returnsTargetedDeletions() {
        val text = "Ayer {{c1::fui}} al cine y {{c2::comí}} palomitas"
        assertEquals(listOf("fui"), Cloze.activeAnswers(text, activeOrd = 1))
        assertEquals(listOf("fui", "comí"), Cloze.activeAnswers(text, activeOrd = null))
    }

    // --- splitting a multi-deletion card, for the legacy conversion ----------

    @Test
    fun ords_listsEachClozeNumberOnce() {
        assertEquals(listOf(1, 2), Cloze.ords("El {{c1::gato}} ve al {{c2::perro}}"))
        // A number used twice is one deletion shown in two places, not two deletions.
        assertEquals(listOf(1), Cloze.ords("{{c1::El}} gato {{c1::duerme}}"))
        assertEquals(emptyList<Int>(), Cloze.ords("No markup"))
    }

    @Test
    fun singleDeletion_keepsOneBlankAndFillsInTheRest() {
        val text = "El {{c1::gato}} ve al {{c2::perro}}"
        assertEquals("El {{c1::gato}} ve al perro", Cloze.singleDeletion(text, 1))
        // The kept deletion is renumbered to c1, so every banked row is uniformly single-cloze.
        assertEquals("El gato ve al {{c1::perro}}", Cloze.singleDeletion(text, 2))
    }

    @Test
    fun singleDeletion_preservesAHint() {
        assertEquals(
            "El {{c1::gato::animal}} duerme",
            Cloze.singleDeletion("El {{c1::gato::animal}} duerme", 1),
        )
    }

    @Test
    fun singleDeletion_isNullForAnOrdinalThatIsNotThere() {
        assertNull(Cloze.singleDeletion("El {{c1::gato}} duerme", 2))
    }
}
