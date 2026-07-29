package com.copiosa.domain.conjugation

import com.copiosa.domain.routine.AdaptationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConjugationMasteryTest {

    @Test
    fun recallSpeedSeparatesAnswersTheRoutineEngineWouldTreatAsIdentical() {
        // The regression this test exists for: AdaptationMath calls anything under 8s "fast", so a
        // snap recall and a laboured reconstruction score the same. For conjugation they must not.
        val instant = ConjugationMastery.next(0.5, DrillGrade.GOT_IT, 800)
        val laboured = ConjugationMastery.next(0.5, DrillGrade.GOT_IT, 6_000)
        assertTrue("800ms and 6s must not score alike", instant > laboured)

        assertEquals(
            AdaptationMath.nextMastery(0.5, correct = true, latencyMs = 800),
            AdaptationMath.nextMastery(0.5, correct = true, latencyMs = 6_000),
            1e-9,
        )
    }

    @Test
    fun theFastThresholdIsMuchTighterThanTheRoutineEngines() {
        assertTrue(ConjugationMastery.FAST_MS < AdaptationMath.FAST_MS / 2)
    }

    @Test
    fun aMissedAnswerScoresZeroHoweverFastItWas() {
        assertEquals(0.0, ConjugationMastery.sample(DrillGrade.MISSED, 200), 1e-9)
        assertEquals(0.0, ConjugationMastery.sample(DrillGrade.MISSED, 60_000), 1e-9)
        assertTrue(ConjugationMastery.next(0.6, DrillGrade.MISSED, 200) < 0.6)
    }

    @Test
    fun samplesDescendWithLatency() {
        val samples = listOf(500L, 2_700L, 5_000L, 8_900L, 20_000L)
            .map { ConjugationMastery.sample(DrillGrade.GOT_IT, it) }
        assertEquals(samples.sortedDescending(), samples)
        assertEquals(ConjugationMastery.FAST_SAMPLE, samples.first(), 1e-9)
        assertEquals(ConjugationMastery.SLOW_SAMPLE, samples.last(), 1e-9)
    }

    @Test
    fun masteryStaysInRangeUnderRepetition() {
        var high = 0.5
        repeat(30) { high = ConjugationMastery.next(high, DrillGrade.GOT_IT, 400) }
        assertTrue(high in 0.0..1.0)
        var low = 0.5
        repeat(30) { low = ConjugationMastery.next(low, DrillGrade.MISSED, 400) }
        assertTrue(low in 0.0..1.0)
        assertTrue(high > low)
    }

    @Test
    fun latencyIsRescaledBeforeItReachesTheRoutinePlanner() {
        // A 2.7s answer is fast for this drill, so the planner should see it as fast too.
        val scaled = ConjugationMastery.scaleForRoutineAdaptation(2_700, AdaptationMath.FAST_MS)
        assertTrue("2.7s should read as fast to the routine engine", scaled <= AdaptationMath.FAST_MS)
        // A 6s answer is slow for this drill, and should stay slow after rescaling.
        val slow = ConjugationMastery.scaleForRoutineAdaptation(6_000, AdaptationMath.FAST_MS)
        assertTrue("6s should read as slow to the routine engine", slow > AdaptationMath.FAST_MS)
    }

    @Test
    fun sessionStatsSplitEveryWay() {
        val verb = VerbLexicon.byInfinitive("tener")!!
        fun answer(tense: Tense, person: Person, grade: DrillGrade, ms: Long) = DrillAnswer(
            item = DrillItem.Produce("tener", Cell(tense, person)),
            form = ConjugationEngine.conjugate(verb, tense, person)!!,
            grade = grade, latencyMs = ms,
        )
        val stats = ConjugationSessionStats.from(
            listOf(
                answer(Tense.PRESENT, Person.YO, DrillGrade.GOT_IT, 1_000),
                answer(Tense.PRESENT, Person.TU, DrillGrade.MISSED, 3_000),
                answer(Tense.PRETERITE, Person.YO, DrillGrade.GOT_IT, 2_000),
            ),
        )
        assertEquals(3, stats.answered)
        assertEquals(2, stats.correct)
        assertEquals(66, stats.accuracyPercent)
        assertEquals(2_000L, stats.avgLatencyMs)
        assertEquals(2 to 1, stats.byTense[Tense.PRESENT])
        assertEquals(1 to 1, stats.byTense[Tense.PRETERITE])
        assertEquals(3 to 2, stats.byDirection[DrillDirection.PRODUCE])
        // tener's yo is a -go form; its preterite is a strong preterite.
        assertEquals(1 to 1, stats.byFamily[IrregularityFamily.STRONG_PRETERITE])
    }

    @Test
    fun emptySessionsDoNotDivideByZero() {
        val stats = ConjugationSessionStats.from(emptyList())
        assertEquals(0, stats.answered)
        assertEquals(0, stats.accuracyPercent)
        assertEquals(0L, stats.avgLatencyMs)
    }
}
