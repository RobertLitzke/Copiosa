package com.copiosa.domain.conjugation

import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/** What the drill remembers about one item between sessions. */
data class ItemStat(
    val attempts: Int = 0,
    val mastery: Double = DEFAULT_MASTERY,
    val lastSeenAt: Long? = null,
) {
    companion object {
        /** Matches `activity_proficiency`'s starting mastery, so the two engines start level. */
        const val DEFAULT_MASTERY = 0.3
    }
}

/**
 * One card. The four shapes differ in what is given and what is asked, not just in presentation:
 * [Transform] and [Generalize] are the two that test whether a pattern generalized rather than being
 * memorized as a list.
 *
 * [itemKey] is the persistence key. Its format is five pipe-separated fields —
 * `<version>|<direction>|<subject>|<source>|<target>` — with the subject holding an infinitive, or an
 * irregularity class for [Generalize]. **Frozen once shipped**; the leading version exists so it can
 * be widened again without a migration.
 */
sealed interface DrillItem {
    val direction: DrillDirection
    val target: Cell
    val itemKey: String

    /** The verb actually shown. Recorded on every attempt, even where [itemKey] omits it. */
    val infinitive: String

    data class Produce(override val infinitive: String, override val target: Cell) : DrillItem {
        override val direction get() = DrillDirection.PRODUCE
        override val itemKey get() = key(direction, infinitive, null, target)
    }

    data class Decode(override val infinitive: String, override val target: Cell) : DrillItem {
        override val direction get() = DrillDirection.DECODE
        override val itemKey get() = key(direction, infinitive, null, target)
    }

    data class Transform(
        override val infinitive: String,
        val rule: TransformRule,
        val source: Cell,
        override val target: Cell,
    ) : DrillItem {
        override val direction get() = DrillDirection.TRANSFORM
        override val itemKey get() = key(direction, infinitive, source, target)
    }

    /**
     * [infinitive] is chosen at selection time from verbs the learner has never drilled, and is
     * deliberately absent from [itemKey]: the skill being measured is the class, not the word.
     */
    data class Generalize(
        val cls: IrregularityClass,
        override val infinitive: String,
        override val target: Cell,
    ) : DrillItem {
        override val direction get() = DrillDirection.GENERALIZE
        override val itemKey get() = key(direction, cls.slug, null, target)
    }

    companion object {
        const val KEY_VERSION = "1"

        fun key(direction: DrillDirection, subject: String, source: Cell?, target: Cell): String =
            "$KEY_VERSION|${direction.slug}|$subject|${source?.slug.orEmpty()}|${target.slug}"
    }
}

/**
 * Draws a session's items.
 *
 * Weighting blends four things: how common the verb is, how well the learner knows the item, whether
 * they have ever seen it, and how long ago. Frequency dominates deliberately — roughly three quarters
 * of a cold-start session lands in the forty commonest verbs — while [VerbEntry.weightMultiplier]
 * damps the handful (ser, estar, haber, ir) that would otherwise crowd everything else out.
 *
 * Pure and seedable: pass a fixed [Random] for deterministic tests.
 */
class ConjugationDrillSelector(
    private val spec: ConjugationSpec,
    private val random: Random = Random.Default,
) {

    fun select(
        config: ConjugationDrillConfig,
        stats: Map<String, ItemStat> = emptyMap(),
        familyMastery: Map<IrregularityFamily, Double> = emptyMap(),
        seenVerbs: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
        count: Int = config.itemCount,
        scope: DrillVerbFilter? = null,
    ): List<DrillItem> {
        val clean = config.sanitized(spec)
        val verbs = spec.lexicon.verbs.filter { scope?.allows(it, clean.tenses) ?: true }
        if (verbs.isEmpty()) return emptyList()
        val topFrequency = verbs.maxOf { it.frequency }.coerceAtLeast(1.0)

        val candidates = candidates(verbs, clean, seenVerbs, scope)
            .map { it to weigh(it, clean, stats, familyMastery, topFrequency, now) }
            .filter { it.second > 0.0 }
            .toMutableList()

        val picked = mutableListOf<DrillItem>()
        // One question per cell per session: asking for "tuvo" twice, once each way, is a duplicate
        // as far as the learner is concerned.
        val usedCells = mutableSetOf<Pair<String, Cell>>()
        while (picked.size < count && candidates.isNotEmpty()) {
            val index = weightedIndex(candidates) ?: break
            val (item, _) = candidates.removeAt(index)
            if (!usedCells.add(item.infinitive to item.target)) continue
            picked += item
        }
        return picked
    }

    private fun candidates(
        verbs: List<VerbEntry>,
        config: ConjugationDrillConfig,
        seenVerbs: Set<String>,
        scope: DrillVerbFilter? = null,
    ): List<DrillItem> = buildList {
        val persons = scope?.personsIn(config.persons(spec)) ?: config.persons(spec)
        val cells = config.tenses.flatMap { tense -> persons.map { Cell(tense, it) } }

        for (verb in verbs) {
            for (cell in cells) {
                if (!verb.allows(cell)) continue
                if (DrillDirection.PRODUCE in config.directions && verb.allows(DrillDirection.PRODUCE)) {
                    add(DrillItem.Produce(verb.infinitive, cell))
                }
                if (DrillDirection.DECODE in config.directions && verb.allows(DrillDirection.DECODE)) {
                    add(DrillItem.Decode(verb.infinitive, cell))
                }
            }
            if (DrillDirection.TRANSFORM in config.directions) {
                for ((rule, target) in TransformRules.cardsFor(verb, persons, spec.transformRules)) {
                    if (target.tense !in config.tenses) continue
                    add(DrillItem.Transform(verb.infinitive, rule, rule.sourceCell(), target))
                }
            }
        }

        if (DrillDirection.GENERALIZE in config.directions) {
            for (cls in spec.lexicon.generalizableClasses) {
                if (scope?.irregularityClasses?.contains(cls) == false) continue
                // The card shows a pattern by example and then asks about a verb the learner has
                // never drilled. Once the class is exhausted it simply stops offering, rather than
                // re-showing a verb whose answer is already memorised.
                val members = spec.lexicon.verbsInClass(cls)
                    .filter { it.allows(DrillDirection.GENERALIZE) }
                    .filter { scope?.allows(it, config.tenses) ?: true }
                if (members.size < VerbLexicon.MIN_GENERALIZE_VERBS) continue
                val unseen = members.filter { it.infinitive !in seenVerbs }
                val pick = unseen.randomOrNull(random) ?: continue
                for (cell in cells) {
                    if (!pick.allows(cell)) continue
                    if (pick.overrideAt(cell.tense, cell.person)?.classes?.contains(cls) != true) continue
                    add(DrillItem.Generalize(cls, pick.infinitive, cell))
                }
            }
        }
    }

    private fun weigh(
        item: DrillItem,
        config: ConjugationDrillConfig,
        stats: Map<String, ItemStat>,
        familyMastery: Map<IrregularityFamily, Double>,
        topFrequency: Double,
        now: Long,
    ): Double {
        val verb = spec.lexicon.byInfinitive(item.infinitive) ?: return 0.0
        val form = spec.engine.conjugate(verb, item.target.tense, item.target.person)
            ?: return 0.0
        val stat = stats[item.itemKey]

        val frequency = (verb.frequency / topFrequency).pow(FREQ_EXPONENT)
        val mastery = (MASTERY_CEILING - (stat?.mastery ?: ItemStat.DEFAULT_MASTERY))
            .coerceIn(MASTERY_FLOOR, MASTERY_CEILING)
        val novelty = if ((stat?.attempts ?: 0) == 0) NEW_BOOST else 1.0
        val recency = stat?.lastSeenAt?.let {
            val hours = (now - it).coerceAtLeast(0L) / MILLIS_PER_HOUR
            (1.0 - exp(-hours / RECENCY_HALF_LIFE_HOURS)).coerceAtLeast(RECENCY_FLOOR)
        } ?: 1.0

        // Transform and generalize ask the learner to extend a pattern, so they surface as that
        // pattern starts to land rather than cold on the first session.
        val directionWeight = when (item.direction) {
            DrillDirection.PRODUCE, DrillDirection.DECODE -> 1.0
            DrillDirection.TRANSFORM, DrillDirection.GENERALIZE ->
                (familyMastery[form.family] ?: 0.0).coerceIn(DERIVED_FLOOR, 1.0)
        }
        val emphasis = if (form.isIrregular) {
            1.0 + config.irregularEmphasis * IRREGULAR_BOOST
        } else {
            1.0 - config.irregularEmphasis * REGULAR_DAMP
        }

        return frequency * mastery * novelty * recency * directionWeight *
            verb.weightMultiplier * emphasis
    }

    private fun weightedIndex(items: List<Pair<DrillItem, Double>>): Int? {
        val total = items.sumOf { it.second }
        if (total <= 0.0) return null
        var roll = random.nextDouble() * total
        for (i in items.indices) {
            roll -= items[i].second
            if (roll <= 0.0) return i
        }
        return items.lastIndex
    }

    companion object {
        /**
         * Sub-linear, and it has to be. Spanish verb frequency is roughly Zipfian, so the raw curve
         * alone already lands near the three-quarters target — but irregular verbs cluster hard in
         * the top forty, so [IRREGULAR_BOOST] concentrates the draw further on exactly the verbs
         * frequency was already favouring. Compressing the frequency term is what pays that back;
         * at 1.0 the top forty take about 85% of a session.
         *
         * Calibrated by `ConjugationDrillSelectorTest.topFortyShareIsInTargetBand` — retune it there,
         * not by eye, and re-check it after changing [IRREGULAR_BOOST] or the default emphasis.
         */
        const val FREQ_EXPONENT = 0.82

        const val MASTERY_FLOOR = 0.2
        const val MASTERY_CEILING = 1.2
        const val NEW_BOOST = 1.6
        const val RECENCY_HALF_LIFE_HOURS = 18.0
        const val RECENCY_FLOOR = 0.15

        /** Derived cards never vanish entirely, so a stuck learner still meets the pattern. */
        const val DERIVED_FLOOR = 0.12

        const val IRREGULAR_BOOST = 2.0
        const val REGULAR_DAMP = 0.9

        private const val MILLIS_PER_HOUR = 3_600_000.0
    }
}
