package com.copiosa.domain.routine

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

/** The planner's output: an ordered, monotonically de-escalating sequence of [ActivitySlot]s. */
data class RoutineBlueprint(
    val routineType: RoutineType,
    val durationMinutes: Int,
    val targetLanguage: String,
    val slots: List<ActivitySlot>,
)

/**
 * Builds a routine's activity sequence. The budget is split into arousal bands (Engage ~30% /
 * Settle ~40% / Drift ~30%; Engage skipped at <=5 min, since five minutes shouldn't wake you up).
 * Within each band activities are drawn randomly, weighted by the adaptation selection weight, then
 * ordered by descending arousal; the closer (bedtime story) is always appended last in Drift. So the
 * de-escalating curve is guaranteed while the picks vary night to night.
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
        // Hard activation cap: the bedtime wind-down never includes a HIGH-arousal activity, no matter
        // how the bands fill — so adding day-mode-only drills later can't leak into a calm routine.
        val available = ActivityDefinition.forLanguage(targetLanguage)
            .filter { it.arousalLevel.ordinal <= routineType.maxArousal.ordinal && it.type !in unavailable }
        val budgets = bandBudgets(durationMinutes)
        val usedTypes = mutableSetOf<ActivityType>()
        val slots = mutableListOf<ActivitySlot>()

        // ArousalBand.entries is ENGAGE, SETTLE, DRIFT in declaration order — the macro de-escalation.
        for (band in ArousalBand.entries) {
            val budget = budgets[band] ?: 0.0
            if (budget <= 0.0) continue
            slots += fillBand(band, budget, available, stats, usedTypes)
        }
        return RoutineBlueprint(routineType, durationMinutes, targetLanguage, slots)
    }

    /** Per-band minute budgets. Engage is dropped entirely for very short routines. */
    private fun bandBudgets(duration: Int): Map<ArousalBand, Double> {
        val d = duration.toDouble()
        return if (duration <= SKIP_ENGAGE_MAX_MINUTES) {
            mapOf(ArousalBand.SETTLE to d * 0.5, ArousalBand.DRIFT to d * 0.5)
        } else {
            mapOf(
                ArousalBand.ENGAGE to d * 0.30,
                ArousalBand.SETTLE to d * 0.40,
                ArousalBand.DRIFT to d * 0.30,
            )
        }
    }

    private fun fillBand(
        band: ArousalBand,
        budget: Double,
        available: List<ActivityDefinition>,
        stats: Map<String, ActivityStats>,
        usedTypes: MutableSet<ActivityType>,
    ): List<ActivitySlot> {
        val closer = available.firstOrNull { it.isCloser && band in it.eligibleBands }
        val pool = available
            .filter { band in it.eligibleBands && !it.isCloser && it.type !in usedTypes }
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
            chosen += pick
            used += pick.estMinutes
            if (used >= cap) break
        }

        chosen.forEach { usedTypes += it.type }
        closer?.let { usedTypes += it.type }

        // Within-band order is descending arousal; the closer (lowest) lands last in Drift.
        val slots = chosen
            .sortedByDescending { it.arousalWeight }
            .map { toSlot(it, band, stats) }
            .toMutableList()
        if (closer != null) slots += toSlot(closer, band, stats)
        return slots
    }

    private fun toSlot(def: ActivityDefinition, band: ArousalBand, stats: Map<String, ActivityStats>): ActivitySlot {
        if (def.type == ActivityType.CONTRAST_CLOZE) {
            val cat = pickCategory(stats)
            return ActivitySlot(
                type = def.type, band = band, arousalWeight = def.arousalWeight, estMinutes = def.estMinutes,
                difficultyLevel = stats[cat.slug]?.difficultyLevel ?: 0, category = cat.slug,
            )
        }
        return ActivitySlot(
            type = def.type, band = band, arousalWeight = def.arousalWeight, estMinutes = def.estMinutes,
            difficultyLevel = stats[def.type.name]?.difficultyLevel ?: 0, category = null,
        )
    }

    /** Weighted-random contrast category (weak/rarely-seen categories surface more via their weight). */
    private fun pickCategory(stats: Map<String, ActivityStats>): ContrastCategory {
        val cats = ContrastCategory.entries
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
        const val SKIP_ENGAGE_MAX_MINUTES = 5
        const val EPSILON = 0.001
    }
}
