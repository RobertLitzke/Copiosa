package com.copiosa.domain.course

import com.copiosa.domain.conjugation.Cell
import com.copiosa.domain.conjugation.ConjugationSpecs
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.Tense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckupSchedulerTest {

    private val day = CheckupScheduler.DAY_MS
    private val graduatedAt = 1_700_000_000_000L

    @Test
    fun `the first checkup lands on the ladder's first rung`() {
        assertEquals(graduatedAt + 3 * day, CheckupScheduler.firstCheckup(graduatedAt))
    }

    @Test
    fun `passing walks the ladder out and then holds at the top`() {
        var stage = 0
        var now = graduatedAt
        val intervals = mutableListOf<Long>()
        repeat(CheckupScheduler.LADDER_DAYS.size + 2) {
            val outcome = CheckupScheduler.afterCheckup(stage, accuracy = 1.0, now = now)
            intervals += (outcome.nextCheckupAt - now) / day
            stage = outcome.stage
            now = outcome.nextCheckupAt
        }
        assertEquals(listOf(7L, 14L, 30L, 60L, 60L, 60L, 60L), intervals)
        assertEquals(CheckupScheduler.LADDER_DAYS.lastIndex, stage)
    }

    @Test
    fun `failing steps back one rung rather than resetting`() {
        val outcome = CheckupScheduler.afterCheckup(stage = 3, accuracy = 0.5, now = graduatedAt)
        assertEquals(2, outcome.stage)
        assertEquals(graduatedAt + 14 * day, outcome.nextCheckupAt)
    }

    @Test
    fun `failing at the bottom stays at the bottom`() {
        val outcome = CheckupScheduler.afterCheckup(stage = 0, accuracy = 0.0, now = graduatedAt)
        assertEquals(0, outcome.stage)
        assertEquals(graduatedAt + 3 * day, outcome.nextCheckupAt)
    }

    @Test
    fun `the pass threshold is applied at its boundary`() {
        assertEquals(1, CheckupScheduler.afterCheckup(0, CheckupScheduler.PASS_ACCURACY, graduatedAt).stage)
        assertEquals(0, CheckupScheduler.afterCheckup(0, CheckupScheduler.PASS_ACCURACY - 0.01, graduatedAt).stage)
    }

    @Test
    fun `only lessons below the flag threshold are reopened`() {
        val flagged = CheckupScheduler.lessonsToFlag(
            mapOf("meaning" to 1.0, "strong_preterites" to 0.4, "orthographic" to 0.6),
        )
        assertEquals(setOf("strong_preterites"), flagged)
    }
}

class CheckupBucketingTest {

    private val spec = ConjugationSpecs.SPANISH
    private val course = CourseCatalog.course("es", "es_preterite")!!

    private fun answer(
        infinitive: String,
        tense: Tense,
        person: Person,
        correct: Boolean,
        direction: DrillDirection = DrillDirection.PRODUCE,
    ) = CheckupAnswer(infinitive, Cell(tense, person), correct, direction)

    @Test
    fun `an answer is attributed to the lesson whose scope covers it`() {
        // tuve is a strong preterite: the strong-preterites lesson must see these misses.
        val answers = List(4) { answer("tener", Tense.PRETERITE, Person.YO, correct = false) }
        val accuracy = CheckupBucketing.accuracyByLesson(course, answers, spec)
        assertEquals(0.0, accuracy.getValue("strong_preterites"), 0.001)
        assertTrue("strong_preterites" in CheckupScheduler.lessonsToFlag(accuracy))
    }

    @Test
    fun `a regular -ar answer does not implicate the strong-preterite lesson`() {
        val answers = List(4) { answer("hablar", Tense.PRETERITE, Person.YO, correct = false) }
        val accuracy = CheckupBucketing.accuracyByLesson(course, answers, spec)
        assertTrue("regular_ar" in accuracy)
        assertTrue("strong_preterites" !in accuracy)
    }

    @Test
    fun `a lesson with too little evidence is not accused`() {
        val answers = listOf(answer("tener", Tense.PRETERITE, Person.YO, correct = false))
        val accuracy = CheckupBucketing.accuracyByLesson(course, answers, spec)
        assertTrue("strong_preterites" !in accuracy)
    }

    @Test
    fun `mixed results produce a proportional accuracy`() {
        // All four inside `regular_ar`'s scope, which is -ar verbs in the first and second person:
        // a lesson only answers for the cells it taught.
        val answers = listOf(
            answer("hablar", Tense.PRETERITE, Person.YO, correct = true),
            answer("trabajar", Tense.PRETERITE, Person.TU, correct = true),
            answer("comprar", Tense.PRETERITE, Person.YO, correct = false),
            answer("caminar", Tense.PRETERITE, Person.TU, correct = true),
        )
        val accuracy = CheckupBucketing.accuracyByLesson(course, answers, spec)
        assertEquals(0.75, accuracy.getValue("regular_ar"), 0.001)
        assertTrue(CheckupScheduler.lessonsToFlag(accuracy).isEmpty())
    }

    /**
     * The preterite course opens with a recognition-only lesson: it teaches the learner to *read* a
     * past form, never to build one. Failing to produce *hablé* is evidence about the lesson that
     * taught the -é ending, not about the one that taught what the tense means — and sending the
     * learner back to a prose sitting they already understood is exactly the wrong remedy.
     */
    @Test
    fun `a recognition-only lesson is not accused of a production miss`() {
        val produced = List(4) { answer("hablar", Tense.PRETERITE, Person.YO, correct = false) }
        assertTrue("meaning" !in CheckupBucketing.accuracyByLesson(course, produced, spec))

        val read = produced.map { it.copy(direction = DrillDirection.DECODE) }
        assertTrue("meaning" in CheckupBucketing.accuracyByLesson(course, read, spec))
    }

    @Test
    fun `answers outside the course's tenses are ignored entirely`() {
        val answers = List(4) { answer("hablar", Tense.FUTURE, Person.YO, correct = false) }
        assertTrue(CheckupBucketing.accuracyByLesson(course, answers, spec).isEmpty())
    }

    @Test
    fun `no answers means nothing to attribute`() {
        assertTrue(CheckupBucketing.accuracyByLesson(course, emptyList(), spec).isEmpty())
    }
}
