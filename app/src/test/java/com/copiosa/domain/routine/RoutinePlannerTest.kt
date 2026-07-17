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
    fun everyPlan_endsWithBedtimeStory_exactlyOnce() = forManyPlans { bp ->
        assertTrue("expected at least one slot for ${bp.durationMinutes}m", bp.slots.isNotEmpty())
        assertEquals(
            "exactly one closer for ${bp.durationMinutes}m",
            1,
            bp.slots.count { it.type == ActivityType.BEDTIME_STORY },
        )
        assertEquals(
            "story is last for ${bp.durationMinutes}m",
            ActivityType.BEDTIME_STORY,
            bp.slots.last().type,
        )
    }

    @Test
    fun everyPlan_hasMonotonicNonIncreasingArousal() = forManyPlans { bp ->
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
            assertEquals(ActivityType.BEDTIME_STORY, bp.slots.last().type)
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
        val spanishOnly = setOf(ActivityType.REFERENTIAL_PI, ActivityType.CONTRAST_CLOZE, ActivityType.AFFECTIVE_PI)
        for (duration in listOf(5, 10, 20)) {
            for (seed in 0L until 40L) {
                val bp = plan(duration, seed, language = "fr")
                assertTrue(
                    "fr plan must use no Spanish-only activities",
                    bp.slots.none { it.type in spanishOnly },
                )
                // Still a usable wind-down: reading + a story closer.
                assertEquals(ActivityType.BEDTIME_STORY, bp.slots.last().type)
            }
        }
    }

    @Test
    fun bedtimePlan_neverExceedsItsArousalCap() = forManyPlans { bp ->
        for (slot in bp.slots) {
            val level = ActivityDefinition[slot.type].arousalLevel
            assertTrue(
                "bedtime must not include ${slot.type} (${level} > ${RoutineType.BEDTIME.maxArousal})",
                level.ordinal <= RoutineType.BEDTIME.maxArousal.ordinal,
            )
        }
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
}
