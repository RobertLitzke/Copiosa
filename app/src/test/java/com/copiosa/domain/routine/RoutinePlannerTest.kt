package com.copiosa.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoutinePlannerTest {

    private fun plan(duration: Int, seed: Long, language: String = "es", stats: Map<String, ActivityStats> = emptyMap()) =
        RoutinePlanner(Random(seed)).plan(RoutineType.BEDTIME, duration, language, stats)

    /** Runs [check] against bedtime plans across many seeds and the three core durations. */
    private fun forManyPlans(language: String = "es", check: (RoutineBlueprint) -> Unit) {
        for (duration in listOf(5, 10, 20)) {
            for (seed in 0L until 60L) check(plan(duration, seed, language))
        }
    }

    @Test
    fun everyBedtimePlan_endsWithTheShortText_exactlyOnce() = forManyPlans { bp ->
        assertTrue("expected at least one slot for ${bp.durationMinutes}m", bp.slots.isNotEmpty())
        assertEquals(
            "exactly one closer for ${bp.durationMinutes}m",
            1,
            bp.slots.count { it.type == ActivityType.SHORT_TEXT },
        )
        assertEquals(
            "story is last for ${bp.durationMinutes}m",
            ActivityType.SHORT_TEXT,
            bp.slots.last().type,
        )
    }

    @Test
    fun everyBedtimePlan_hasMonotonicNonIncreasingArousal() = forManyPlans { bp ->
        var prev = Double.MAX_VALUE
        for (slot in bp.slots) {
            assertTrue(
                "arousal must not rise (${bp.durationMinutes}m): ${bp.slots.map { it.type to it.arousalWeight }}",
                slot.arousalWeight <= prev + 1e-9,
            )
            prev = slot.arousalWeight
        }
    }

    @Test
    fun fiveMinutePlan_skipsEngage() {
        for (seed in 0L until 60L) {
            val bp = plan(5, seed)
            assertFalse("5m must skip Engage", bp.slots.any { it.band == ArousalBand.ENGAGE })
            // The documented shape: a Settle then the Drift closer.
            assertTrue("5m has a Settle", bp.slots.any { it.band == ArousalBand.SETTLE })
            assertEquals(ActivityType.SHORT_TEXT, bp.slots.last().type)
        }
    }

    @Test
    fun tenAndTwentyMinutePlans_includeAllThreeBands() {
        for (duration in listOf(10, 20)) {
            for (seed in 0L until 60L) {
                val bp = plan(duration, seed)
                val bands = bp.slots.map { it.band }.toSet()
                assertTrue("${duration}m must include all bands, got $bands", bands.containsAll(ArousalBand.entries))
            }
        }
    }

    @Test
    fun everyBandPresentInPlan_hasAtLeastOneSlot() = forManyPlans { bp ->
        val byBand = bp.slots.groupBy { it.band }
        for ((band, slots) in byBand) {
            assertTrue("band $band should not be empty", slots.isNotEmpty())
        }
    }

    @Test
    fun spanishOnlyActivities_neverAppearForOtherLanguages() {
        val spanishOnly = setOf(
            ActivityType.REFERENTIAL_PI, ActivityType.CONTRAST_CLOZE, ActivityType.AFFECTIVE_PI,
            ActivityType.CONJUGATION_DRILL,
        )
        for (duration in listOf(5, 10, 20)) {
            for (seed in 0L until 40L) {
                val bp = plan(duration, seed, language = "fr")
                assertTrue(
                    "fr plan must use no activity needing a grammar profile it lacks",
                    bp.slots.none { it.type in spanishOnly },
                )
                // Still a usable wind-down: reading + a story closer.
                assertEquals(ActivityType.SHORT_TEXT, bp.slots.last().type)
            }
        }
    }

    /** Above-cap activities in a blueprint, in order. */
    private fun aboveCap(bp: RoutineBlueprint) =
        bp.slots.filter { RoutineType.BEDTIME.isAboveCap(ActivityDefinition[it.type]) }

    @Test
    fun bedtimePlan_admitsAtMostOneAboveCapActivity() = forManyPlans { bp ->
        val over = aboveCap(bp)
        assertTrue(
            "bedtime took ${over.map { it.type }} — quota is ${RoutineType.BEDTIME.highArousalSlots}",
            over.size <= RoutineType.BEDTIME.highArousalSlots,
        )
    }

    @Test
    fun aboveCapActivities_onlyAppearInTheOpeningBand() = forManyPlans { bp ->
        for (slot in aboveCap(bp)) {
            assertEquals(
                "${slot.type} is above the cap and must stay in the opening band",
                RoutineType.BEDTIME.highArousalBand,
                slot.band,
            )
        }
    }

    @Test
    fun shortPlans_stayEntirelyWithinTheCap() {
        // Engage gets no budget under six minutes, so the quota can never be spent.
        for (seed in 0L until 60L) {
            assertTrue("5m must stay calm throughout", aboveCap(plan(5, seed)).isEmpty())
        }
    }

    @Test
    fun theCloserIsNeverAboveTheCap() = forManyPlans { bp ->
        // However activating the opening is, the routine has to end calm.
        val last = ActivityDefinition[bp.slots.last().type]
        assertFalse("closer ${bp.slots.last().type} is above the cap", RoutineType.BEDTIME.isAboveCap(last))
    }

    @Test
    fun theConjugationDrill_isReachableInLongerSpanishPlans() {
        val seen = (0L until 60L).any { seed ->
            plan(20, seed).slots.any { it.type == ActivityType.CONJUGATION_DRILL }
        }
        assertTrue("the drill should be plannable at 20m — it is the quota's only user", seen)
    }

    @Test
    fun sameSeed_producesIdenticalPlan() {
        val a = plan(20, seed = 7L)
        val b = plan(20, seed = 7L)
        assertEquals(a.slots, b.slots)
    }

    @Test
    fun zeroSelectionWeight_excludesABucket() {
        // Suppress both Spanish Settle activities; Settle should fall back to narrow reading only.
        val stats = mapOf(
            ActivityType.REFERENTIAL_PI.name to ActivityStats(selectionWeight = 0.0),
            ActivityType.CONTRAST_CLOZE.name to ActivityStats(selectionWeight = 0.0),
        )
        for (seed in 0L until 60L) {
            val bp = plan(20, seed, stats = stats)
            assertTrue(
                "suppressed activities must never be picked",
                bp.slots.none { it.type == ActivityType.REFERENTIAL_PI || it.type == ActivityType.CONTRAST_CLOZE },
            )
        }
    }

    @Test
    fun clozeSlots_carryAValidContrastCategory() = forManyPlans { bp ->
        for (slot in bp.slots.filter { it.type == ActivityType.CONTRAST_CLOZE }) {
            assertTrue(
                "cloze category must be a known slug, got ${slot.category}",
                ContrastCategory.fromSlug(slot.category) != null,
            )
        }
    }

    @Test
    fun unavailableTypes_areNeverPlanned() {
        for (duration in listOf(5, 10, 20)) {
            for (seed in 0L until 40L) {
                val bp = RoutinePlanner(Random(seed)).plan(
                    RoutineType.BEDTIME, duration, "es",
                    unavailable = setOf(ActivityType.RAPID_FIRE_LISTENING),
                )
                assertTrue(
                    "unavailable type must never be planned",
                    bp.slots.none { it.type == ActivityType.RAPID_FIRE_LISTENING },
                )
            }
        }
    }

    @Test
    fun rapidFire_appearsInEngageWhenAvailable() {
        val seen = (0L until 60L).any { seed ->
            plan(20, seed).slots.any {
                it.type == ActivityType.RAPID_FIRE_LISTENING && it.band == ArousalBand.ENGAGE
            }
        }
        assertTrue("rapid-fire should be plannable at 20m", seen)
    }

    @Test
    fun difficultyLevel_flowsFromStatsIntoSlots() {
        val stats = mapOf(ActivityType.GUESS_THEN_FLIP.name to ActivityStats(difficultyLevel = 3))
        val bp = plan(20, seed = 1L, stats = stats)
        val guess = bp.slots.firstOrNull { it.type == ActivityType.GUESS_THEN_FLIP }
        assertTrue("expected a guess-then-flip slot at 20m", guess != null)
        assertEquals(3, guess!!.difficultyLevel)
    }

    /**
     * The short text asks for 2.0 minutes against a Drift budget of 1.5-6.0 across the offered
     * durations. Its predecessor (the serial story) asked for 5.0 and so could never actually be
     * planned — this guards against the merged activity drifting back out of budget.
     */
    @Test
    fun theShortText_fitsTheDriftBudgetAtEveryDuration() {
        for (duration in listOf(5, 10, 20)) {
            for (seed in 0L until 60L) {
                assertTrue(
                    "${duration}m must be able to plan the short text",
                    plan(duration, seed).slots.any { it.type == ActivityType.SHORT_TEXT },
                )
            }
        }
    }

    @Test
    fun anActivityTaggedForOneRoutineType_neverAppearsInTheOther() {
        // The gate is on the definition, so exercise it through the real registry entry.
        val bedtimeOnly = ActivityDefinition[ActivityType.SHORT_TEXT].copy(
            routineTypes = setOf(RoutineType.BEDTIME),
        )
        assertTrue("BEDTIME must admit its own activity", RoutineType.BEDTIME.admits(bedtimeOnly))
        assertFalse("DAYTIME must reject it", RoutineType.DAYTIME.admits(bedtimeOnly))
    }

    // --- Daytime activation: the mirror image of the wind-down ---

    private fun dayPlan(duration: Int, seed: Long, language: String = "es") =
        RoutinePlanner(Random(seed)).plan(RoutineType.DAYTIME, duration, language)

    private fun forManyDayPlans(language: String = "es", check: (RoutineBlueprint) -> Unit) {
        for (duration in listOf(5, 10, 15)) {
            for (seed in 0L until 60L) check(dayPlan(duration, seed, language))
        }
    }

    @Test
    fun everyDaytimePlan_hasMonotonicNonDecreasingArousal() = forManyDayPlans { bp ->
        var prev = -1.0
        for (slot in bp.slots) {
            assertTrue(
                "arousal must not fall (${bp.durationMinutes}m): ${bp.slots.map { it.type to it.arousalWeight }}",
                slot.arousalWeight >= prev - 1e-9,
            )
            prev = slot.arousalWeight
        }
    }

    @Test
    fun daytimePlan_playsBandsDriftSettleEngage() {
        for (seed in 0L until 60L) {
            val bands = dayPlan(15, seed).slots.map { it.band }.distinct()
            assertEquals(
                listOf(ArousalBand.DRIFT, ArousalBand.SETTLE, ArousalBand.ENGAGE),
                bands,
            )
        }
    }

    @Test
    fun daytimePlan_opensOnTheShortTextAndDoesNotEndOnIt() = forManyDayPlans { bp ->
        assertTrue("expected at least one slot for ${bp.durationMinutes}m", bp.slots.isNotEmpty())
        // No pin: the text lands first because Drift leads, and can only ever be the sole Drift pick.
        assertFalse(
            "a daytime routine must not end on the prose read",
            bp.slots.last().type == ActivityType.SHORT_TEXT,
        )
        if (bp.slots.any { it.type == ActivityType.SHORT_TEXT }) {
            assertEquals(ActivityType.SHORT_TEXT, bp.slots.first().type)
        }
    }

    @Test
    fun shortDaytimePlan_skipsDrift() {
        // The mirror of the wind-down dropping Engage: five minutes shouldn't be spent warming up.
        for (seed in 0L until 60L) {
            val bp = dayPlan(5, seed)
            assertFalse("5m must skip Drift", bp.slots.any { it.band == ArousalBand.DRIFT })
            assertTrue("5m must reach Engage", bp.slots.any { it.band == ArousalBand.ENGAGE })
        }
    }

    @Test
    fun daytimePlan_reachesTheHighArousalTier() {
        // Daytime draws freely from every tier, so the drill needs no quota slot to appear.
        val seen = (0L until 60L).any { seed ->
            dayPlan(15, seed).slots.any { it.type == ActivityType.CONJUGATION_DRILL }
        }
        assertTrue("the conjugation drill should be plannable in a 15m activation", seen)
    }

    @Test
    fun daytimePlan_endsOnItsMostActivatingSlot() = forManyDayPlans { bp ->
        val peak = bp.slots.maxOf { it.arousalWeight }
        assertEquals(
            "the last slot should be the peak for ${bp.durationMinutes}m: " +
                "${bp.slots.map { it.type to it.arousalWeight }}",
            peak,
            bp.slots.last().arousalWeight,
            1e-9,
        )
    }
}
