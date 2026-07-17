package com.copiosa.domain.quiz

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Numbers 1..[QuizPoolConfig.numberMax]. Sampling skews hard toward short numbers — 1..10 are always
 * present and each extra digit roughly halves how many of that magnitude appear — so a wide range still
 * drills the everyday small numbers while sprinkling in the harder large ones. Sampling and distractors
 * also lean on the confusion sets learners actually mishear — the teen/ty pairs every language shares
 * (trece/treinta) plus per-language traps (soixante/soixante-dix, zwölf/zwanzig) — so the options force
 * the listener to resolve exactly the distinction the audio blurred.
 */
object NumberCueGenerator : CueGenerator {
    override val type: CueType = CueType.NUMBER

    /** Teen vs ty for the same digit (13/30 .. 19/90) — confusable in every supported language. */
    private val TEEN_TY: List<Set<Int>> = (3..9).map { setOf(it + 10, it * 10) }

    /** Per-language extra confusion sets, layered on top of [TEEN_TY]. */
    private val LANG_SETS: Map<String, List<Set<Int>>> = mapOf(
        // soixante/soixante-dix and quatre-vingts/quatre-vingt-dix share their first word; deux/douze.
        "fr" to listOf(setOf(60, 70), setOf(80, 90), setOf(2, 12)),
        // sesenta/setenta, seis/dieciséis/sesenta.
        "es" to listOf(setOf(60, 70), setOf(6, 16, 60)),
        "it" to listOf(setOf(60, 70), setOf(6, 16, 60)),
        "pt" to listOf(setOf(60, 70), setOf(6, 16, 60)),
        // zwölf/zwanzig; sechs/sechzehn/sechzig; sieben/siebzehn/siebzig.
        "de" to listOf(setOf(12, 20), setOf(6, 16, 60), setOf(7, 17, 70)),
    )

    private fun confusionSets(lang: String): List<Set<Int>> = TEEN_TY + LANG_SETS[lang].orEmpty()

    override fun generateValues(count: Int, config: QuizPoolConfig, random: Random): List<CueValue> {
        val max = config.numberMax.coerceAtLeast(2)
        val chosen = LinkedHashSet<Int>()

        // 1..10 are always in the pool, whatever the range.
        for (v in 1..minOf(10, max)) chosen += v

        val buckets = magnitudeBuckets(max)
        // Weight halves per extra digit: 1..9 dominate, then 10..99, then 100..999, … so short numbers
        // are common and each larger magnitude is progressively rarer but still represented.
        val weights = List(buckets.size) { 0.5.pow(it) }
        val weightSum = weights.sum()
        val budget = (count - chosen.size).coerceAtLeast(0)

        // Confusion-set partners (teen/ty, per-language traps) live in the low buckets; seed a share up
        // front so both members of a pair keep surfacing as answers, not just as distractors.
        val confusable = confusionSets(config.langCode).flatten().filter { it in 1..max }
        chosen += confusable.shuffled(random).take(budget / 4)

        // Distribute the remaining slots across buckets by weight, sampling distinct values in each.
        buckets.forEachIndexed { i, range ->
            fillFromRange(chosen, range, (budget * weights[i] / weightSum).roundToInt(), random)
        }
        // Rounding and small-bucket caps can leave a shortfall; top up from the smallest buckets first
        // so filling in never flattens the skew.
        for (range in buckets) {
            if (chosen.size >= count) break
            fillFromRange(chosen, range, count - chosen.size, random)
        }

        // Keep 1..10 no matter where the trim falls, then shuffle the rest for varied pool order.
        val guaranteed = chosen.filter { it <= 10 }
        val rest = (chosen - guaranteed.toSet()).shuffled(random)
        return (guaranteed + rest).take(count).map { CueValue.Number(it) }
    }

    /** Powers-of-ten magnitude buckets up to [max]: 1..9, 10..99, 100..999, … (last one clamped). */
    private fun magnitudeBuckets(max: Int): List<IntRange> = buildList {
        var lo = 1
        var hi = 9
        while (lo <= max) {
            add(lo..minOf(hi, max))
            lo = hi + 1
            hi = hi * 10 + 9
        }
    }

    /** Adds up to [quota] distinct values sampled from [range] into [into] (a no-op past the range). */
    private fun fillFromRange(into: MutableSet<Int>, range: IntRange, quota: Int, random: Random) {
        val span = range.last - range.first + 1
        val target = quota.coerceAtMost(span)
        var added = 0
        var guard = 0
        while (added < target && guard++ < target * 40 + 40) {
            if (into.add(range.first + random.nextInt(span))) added++
        }
    }

    override fun distractors(
        answer: CueValue,
        config: QuizPoolConfig,
        random: Random,
        count: Int,
        difficultyLevel: Int,
    ): List<CueValue> {
        val n = (answer as CueValue.Number).value
        val max = config.numberMax.coerceAtLeast(2)

        // Tier 1: curated partners of the answer's confusion set(s).
        val curated = confusionSets(config.langCode)
            .filter { n in it }
            .flatten().filter { it != n }

        // Tier 2: structural mutations that stay audio- or shape-plausible. Small numbers lean on the
        // teen/ty and neighbouring-ten shapes; large numbers on same-length digit near-misses (a wrong
        // digit or a transposed pair — the way a long number is actually misheard).
        val mutations = buildList {
            if (n < 100) {
                transpose(n)?.let { add(it) }
                if (n in 13..19) add((n - 10) * 10) // teen -> ty
                if (n % 10 == 0 && n / 10 in 3..9) add(n / 10 + 10) // ty -> teen
                if (n >= 20) { add(n - 10); add(n + 10) } // neighbouring tens, same unit
                add(n * 10)
                if (n % 10 == 0) add(n / 10)
                add(n - 1); add(n + 1)
            } else {
                addAll(digitNearMisses(n))
                add(n * 10) // heard an extra place
                if (n % 10 == 0) add(n / 10) // dropped a place
            }
        }

        // Tier 3: small offsets, the filler when a small range is tight (near-identical for big
        // numbers, so only useful — and only reached — down here).
        val offsets = (2..9).flatMap { listOf(n - it, n + it) }.shuffled(random)

        // Difficulty widens the window on the nasty tiers: a fresh bucket sees one curated trap at
        // most, a mastered one gets options drawn almost entirely from tiers 1-2.
        val curatedCap = when {
            difficultyLevel >= 2 -> count
            difficultyLevel == 1 -> 2
            else -> 1
        }
        val candidates =
            curated.shuffled(random).take(curatedCap) + mutations.shuffled(random) + offsets
        return candidates
            .filter { it in 1..max && it != n }
            .distinct()
            .take(count)
            .map { CueValue.Number(it) }
    }

    /**
     * The local speller is CLDR-correct through [LOCAL_SPELL_MAX]; larger numbers defer to the LLM
     * (and are dropped if none is available), so the pool never holds a guessed large-number phrasing.
     */
    override fun localSpokenText(
        value: CueValue,
        config: QuizPoolConfig,
        speller: NumberSpeller,
        random: Random,
    ): String? {
        val n = (value as CueValue.Number).value
        return if (n <= LOCAL_SPELL_MAX) speller.spell(n, config.langCode) else null
    }

    override fun displayText(value: CueValue): String = (value as CueValue.Number).value.toString()

    /** Swaps the digits of a two-digit number (73 -> 37); null when the swap is degenerate. */
    private fun transpose(n: Int): Int? {
        if (n !in 10..99) return null
        val swapped = (n % 10) * 10 + n / 10
        return swapped.takeIf { it != n && it >= 10 }
    }

    /**
     * Same-length near-misses for a long number: bump any one digit by ±1, or swap an adjacent pair —
     * the transpositions and single-place slips that make a big number genuinely confusable. Never
     * produces a leading zero (which would change the number's length).
     */
    private fun digitNearMisses(n: Int): List<Int> {
        val s = n.toString()
        val out = mutableListOf<Int>()
        for (i in s.indices) {
            val d = s[i] - '0'
            for (nd in intArrayOf(d - 1, d + 1)) {
                if (nd in 0..9 && !(i == 0 && nd == 0)) {
                    out += (s.substring(0, i) + ('0' + nd) + s.substring(i + 1)).toInt()
                }
            }
        }
        for (i in 0 until s.length - 1) {
            if (s[i] == s[i + 1]) continue
            val chars = s.toCharArray()
            chars[i] = s[i + 1]
            chars[i + 1] = s[i]
            if (chars[0] != '0') out += String(chars).toInt()
        }
        return out
    }

    /** Above this the local speller is untested against CLDR, so the pool asks the LLM instead. */
    private const val LOCAL_SPELL_MAX = 1000
}
