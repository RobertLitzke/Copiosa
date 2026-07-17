package com.copiosa.domain.routine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineValidationTest {

    @Test
    fun pi_proDroppedSentence_isValid() {
        val item = PiItem("Comí una manzana fresca.", listOf("yo", "tú", "ella"), 0)
        assertTrue(RoutineValidation.piStructurallyValid(item))
    }

    @Test
    fun pi_survivingSubjectPronoun_isRejected() {
        // "yo" hands the learner the answer instead of making them parse the verb.
        val item = PiItem("Yo comí una manzana.", listOf("yo", "tú", "ella"), 0)
        assertFalse(RoutineValidation.piStructurallyValid(item))
    }

    @Test
    fun pi_survivingTimeAdverb_isRejected() {
        val item = PiItem("Ayer comí una manzana.", listOf("ayer", "hoy", "mañana"), 0)
        assertFalse(RoutineValidation.piStructurallyValid(item))
    }

    @Test
    fun pi_unaccentedTuAndEl_areNotMistakenForPronouns() {
        // "tu" (your) and "el" (the) are legitimate here — only accented "tú"/"él" are cues.
        val item = PiItem("Perdió el libro de tu hermano.", listOf("yo", "tú", "él"), 2)
        assertTrue(RoutineValidation.piStructurallyValid(item))
    }

    @Test
    fun pi_badAnswerIndexOrTooFewOptions_isRejected() {
        assertFalse(RoutineValidation.piStructurallyValid(PiItem("Comí.", listOf("yo", "tú"), 5)))
        assertFalse(RoutineValidation.piStructurallyValid(PiItem("Comí.", listOf("yo"), 0)))
    }

    @Test
    fun cloze_wellFormedItem_isValid() {
        val item = ClozeItem("Mi hermano ${ClozeItem.BLANK} cansado.", listOf("es", "está"), 1)
        assertTrue(RoutineValidation.clozeStructurallyValid(item))
    }

    @Test
    fun cloze_missingBlankOrWrongOptionCount_isRejected() {
        assertFalse(RoutineValidation.clozeStructurallyValid(ClozeItem("Mi hermano está cansado.", listOf("es", "está"), 1)))
        assertFalse(RoutineValidation.clozeStructurallyValid(ClozeItem("Mi hermano ${ClozeItem.BLANK} cansado.", listOf("es"), 0)))
    }

    @Test
    fun cloze_identicalOptions_isRejected() {
        val item = ClozeItem("Mi hermano ${ClozeItem.BLANK} cansado.", listOf("es", "ES"), 0)
        assertFalse(RoutineValidation.clozeStructurallyValid(item))
    }

    @Test
    fun mcCloze_threeDistinctOptions_isValid() {
        val item = ClozeItem("Quiero ${ClozeItem.BLANK} agua.", listOf("beber", "comer", "tener"), 0)
        assertTrue(RoutineValidation.mcClozeStructurallyValid(item))
    }

    @Test
    fun mcCloze_wrongOptionCountOrDuplicateOrNoBlank_isRejected() {
        // Two options is a contrast cloze, not a 3-way MC cloze.
        assertFalse(RoutineValidation.mcClozeStructurallyValid(ClozeItem("Quiero ${ClozeItem.BLANK} agua.", listOf("beber", "comer"), 0)))
        // A duplicate distractor.
        assertFalse(RoutineValidation.mcClozeStructurallyValid(ClozeItem("Quiero ${ClozeItem.BLANK} agua.", listOf("beber", "Beber", "tener"), 0)))
        // No blank.
        assertFalse(RoutineValidation.mcClozeStructurallyValid(ClozeItem("Quiero beber agua.", listOf("beber", "comer", "tener"), 0)))
    }
}
