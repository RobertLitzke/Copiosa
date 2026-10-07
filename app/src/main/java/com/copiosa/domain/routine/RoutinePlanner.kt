package com.copiosa.domain.routine

import com.copiosa.domain.language.LanguageProfiles
import kotlin.random.Random

/** Adaptation-derived knobs for one activity/category bucket, consumed by the planner. */
data class ActivityStats(
    val selectionWeight: Double = 1.0,
    val difficultyLevel: Int = 0,
    /** The bucket's mastery EMA, used (e.g. by rapid-fire) to derive an auto listening speed. */
    val mastery: Double = 0.5,
)

/** One slot in a planned routine: which activity, where it sits, and how hard / what flavour. */
data class ActivitySlot(
    val type: ActivityType,
    val band: ArousalBand,
    val arousalWeight: Double,
    val estMinutes: Double,
    val difficultyLevel: Int,
    /** Contrast-category slug for [ActivityType.CONTRAST_CLOZE]; null for other types. */
    val category: String? = null,
)

/** The planner's output: an ordered, monotonically escalating or de-escalating [ActivitySlot] sequence. */
data class RoutineBlueprint(
    val routineType: RoutineType,
    val durationMinutes: Int,
    val targetLanguage: String,
    val slots: List<ActivitySlot>,
)

/**
 * Builds a routine's activity sequence. The budget is split across the arousal bands, which are played
 * in the routine type's own [RoutineType.bandOrder]. Within each band activities are drawn randomly,
 * weighted by the adaptation selection weight, then ordered by arousal in the type's direction; the
 * closer, if the type has one, is always appended last. So the curve — de-escalating toward sleep at
 * bedtime, escalating toward a peak by day — is guaranteed while the picks vary run to run.
 *
 * A routine draws freely from [RoutineType.maxArousal] and below, plus a small quota of more
 * activating activities confined to [RoutineType.highArousalBand] — see [RoutineType].
 *
 * Pure and seedable: pass a fixed [Random] for deterministic tests.
 */
class RoutinePlanner(private val random: Random = Random.Default) {

    fun plan(
        routineType: RoutineType,
        durationMinutes: Int,
        targetLanguage: String,
        stats: Map<String, ActivityStats> = emptyMap(),
        /**
         * Types whose runtime prerequisites aren't met right now (e.g. rapid-fire listening with no
         * rendered cue pool) — excluded from planning entirely so no slot is wasted on them.
         */
        unavailable: Set<ActivityType> = emptySet(),
    ): RoutineBlueprint {
        // Softened activation cap: the routine draws freely from its own tier and below, and may
        // additionally admit up to [RoutineType.highArousalSlots] activities from above it — but only
        // in [RoutineType.highArousalBand] (Engage), where a jolt still has the rest of the routine to
        // decay. Below SKIP_BAND_MAX_MINUTES the bedtime Engage band gets no budget at all, so a very
        // short wind-down still cannot contain anything above the cap.
        val available = ActivityDefinition.forLanguage(targetLanguage)
            .filter { it.type !in unavailable && routineType.admits(it) }
        val budgets = bandBudgets(routineType, durationMinutes)
        val usedTypes = mutableSetOf<ActivityType>()
        val slots = mutableListOf<ActivitySlot>()
        var aboveCapRemaining = routineType.highArousalSlots

        // The routine type owns the macro arc; ArousalBand's declaration order is not the sequence.
        for (band in routineType.bandOrder) {
            val budget = budgets[band] ?: 0.0
            if (budget <= 0.0) continue
            val quota = if (band == routineType.highArousalBand) aboveCapRemaining else 0
            val filled = fillBand(band, budget, available, stats, usedTypes, routineType, quota, targetLanguage)
            aboveCapRemaining -= filled.count { routineType.isAboveCap(ActivityDefinition[it.type]) }
            slots += filled
        }
        return RoutineBlueprint(routineType, durationMinutes, targetLanguage, slots)
    }

    /**
     * Per-band minute budgets. A very short routine drops the band furthest from its purpose entirely:
     * a five-minute wind-down shouldn't wake you up, and a five-minute activation shouldn't spend a
     * fifth of itself warming up.
     */
    private fun bandBudgets(routineType: RoutineType, duration: Int): Map<ArousalBand, Double> {
        val d = duration.toDouble()
        val short = duration <= SKIP_BAND_MAX_MINUTES
        return when (routineType) {
            RoutineType.BEDTIME -> if (short) {
                mapOf(ArousalBand.SETTLE to d * 0.50, ArousalBand.DRIFT to d * 0.50)
            } else {
                mapOf(
                    ArousalBand.ENGAGE to d * 0.30,
                    ArousalBand.SETTLE to d * 0.40,
                    ArousalBand.DRIFT to d * 0.30,
                )
            }
            RoutineType.DAYTIME -> if (short) {
                mapOf(ArousalBand.SETTLE to d * 0.40, ArousalBand.ENGAGE to d * 0.60)
            } else {
                mapOf(
                    ArousalBand.DRIFT to d * 0.20,
                    ArousalBand.SETTLE to d * 0.40,
                    ArousalBand.ENGAGE to d * 0.40,
                )
            }
        }
    }

    private fun fillBand(
        band: ArousalBand,
        budget: Double,
        available: List<ActivityDefinition>,
        stats: Map<String, ActivityStats>,
        usedTypes: MutableSet<ActivityType>,
        routineType: RoutineType,
        /** How many above-cap activities this band may take; zero for every band but the first. */
        aboveCapQuota: Int,
        targetLanguage: String,
    ): List<ActivitySlot> {
        // A closer is never above the cap — however the routine opens, it has to end calm. A routine
        // type with no closer (the daytime activation) simply never resolves one.
        val closer = available.firstOrNull {
            it.type == routineType.closerType && band in it.eligibleBands && !routineType.isAboveCap(it)
        }
        var aboveCapLeft = aboveCapQuota
        val pool = available
            .filter { band in it.eligibleBands && it.type != routineType.closerType && it.type !in usedTypes }
            .filter { !routineType.isAboveCap(it) || aboveCapLeft > 0 }
            .toMutableList()
        val reserved = closer?.estMinutes ?: 0.0
        val cap = budget - reserved

        // A band with no closer must contribute at least one activity; a band whose closer already
        // fills it (e.g. 5-min Drift) adds non-closer activities only if they genuinely fit.
        val forceFirst = closer == null
        val chosen = mutableListOf<ActivityDefinition>()
        var used = 0.0
        while (pool.isNotEmpty()) {
            val candidates = if (chosen.isEmpty() && forceFirst) {
                pool
            } else {
                pool.filter { used + it.estMinutes <= cap + EPSILON }
            }
            if (candidates.isEmpty()) break
            var pick = weightedPick(candidates) { stats[it.bucketKey()]?.selectionWeight ?: 1.0 }
            if (pick == null) {
                // Only the mandatory first pick of a closer-less band falls back to a uniform choice
                // (so the band is never empty); optional picks just stop when nothing has weight.
                if (chosen.isEmpty() && forceFirst) pick = candidates[random.nextInt(candidates.size)] else break
            }
            pool.remove(pick)
            if (routineType.isAboveCap(pick)) {
                aboveCapLeft--
                // Quota spent: drop the rest so one band can't take two above-cap activities.
                if (aboveCapLeft <= 0) pool.removeAll { routineType.isAboveCap(it) }
            }
            chosen += pick
            used += pick.estMinutes
            if (used >= cap) break
        }

        chosen.forEach { usedTypes += it.type }
        closer?.let { usedTypes += it.type }

        // Within-band order follows the type's direction; the closer (lowest arousal) still lands last.
        val ordered =
            if (routineType.ascending) chosen.sortedBy { it.arousalWeight }
            else chosen.sortedByDescending { it.arousalWeight }
        val slots = ordered.map { toSlot(it, band, stats, targetLanguage) }.toMutableList()
        if (closer != null) slots += toSlot(closer, band, stats, targetLanguage)
        return slots
    }

    private fun toSlot(
        def: ActivityDefinition,
        band: ArousalBand,
        stats: Map<String, ActivityStats>,
        targetLanguage: String,
    ): ActivitySlot {
        if (def.type == ActivityType.CONTRAST_CLOZE) {
            val cat = pickCategory(stats, targetLanguage)
            if (cat != null) {
                return ActivitySlot(
                    type = def.type, band = band, arousalWeight = def.arousalWeight, estMinutes = def.estMinutes,
                    difficultyLevel = stats[cat.slug]?.difficultyLevel ?: 0, category = cat.slug,
                )
            }
        }
        return ActivitySlot(
            type = def.type, band = band, arousalWeight = def.arousalWeight, estMinutes = def.estMinutes,
            difficultyLevel = stats[def.type.name]?.difficultyLevel ?: 0, category = null,
        )
    }

    /**
     * Weighted-random contrast category from the *language's* catalog — weak or rarely-seen
     * categories surface more via their weight. Null when the language has no contrasts, which the
     * activity's `GRAMMAR` requirement should already have ruled out.
     */
    private fun pickCategory(stats: Map<String, ActivityStats>, targetLanguage: String): ContrastCategory? {
        val cats = LanguageProfiles.forTag(targetLanguage)?.contrasts.orEmpty()
        if (cats.isEmpty()) return null
        return weightedPick(cats) { stats[it.slug]?.selectionWeight ?: 1.0 } ?: cats.first()
    }

    /**
     * Picks one item with probability proportional to [weight], or null when every item is
     * zero-weight (so adaptation can fully suppress a bucket — callers that must not return empty
     * handle the all-suppressed case themselves).
     */
    private fun <T> weightedPick(items: List<T>, weight: (T) -> Double): T? {
        if (items.isEmpty()) return null
        val positive = items.map { it to weight(it).coerceAtLeast(0.0) }.filter { it.second > 0.0 }
        if (positive.isEmpty()) return null
        val total = positive.sumOf { it.second }
        var roll = random.nextDouble() * total
        for ((item, w) in positive) {
            roll -= w
            if (roll <= 0.0) return item
        }
        return positive.last().first
    }

    private companion object {
        const val SKIP_BAND_MAX_MINUTES = 5
        const val EPSILON = 0.001
    }
}
