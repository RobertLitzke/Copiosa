package com.copiosa.domain.topics

import kotlin.math.ln
import kotlin.math.pow

/**
 * Ranks candidate conversation topics from signals the app already has — no model call.
 *
 * A topic scores on how recently it was touched (exponential decay, [HALF_LIFE_DAYS]) times how
 * often it came up. Keeping this pure and free means the suggestion feature still works with
 * background LLM tasks switched off; the model, when allowed, only re-ranks and expands the result.
 */
object TopicSuggestionScorer {

    /** After this many days an untouched topic counts half as much as a fresh one. */
    const val HALF_LIFE_DAYS = 14.0

    private const val DAY_MS = 24.0 * 60 * 60 * 1000

    /**
     * One thing the learner has shown interest in.
     *
     * [weight] is the repeat count (a topic seen five times outranks one seen once, but sub-linearly
     * — five sightings are not five times more interesting).
     */
    data class Signal(
        val phrase: String,
        val description: String? = null,
        val at: Long,
        val weight: Int = 1,
    )

    data class Scored(
        val phrase: String,
        val description: String?,
        val score: Double,
    )

    /**
     * Best candidates first, deduplicated case-insensitively by phrase.
     *
     * Duplicates merge rather than compete: their weights sum and the most recent timestamp wins,
     * so a topic raised repeatedly over weeks beats a one-off from yesterday.
     */
    fun rank(signals: List<Signal>, now: Long, limit: Int = 5): List<Scored> {
        if (signals.isEmpty()) return emptyList()

        val merged = LinkedHashMap<String, Signal>()
        for (signal in signals) {
            val phrase = signal.phrase.trim()
            if (phrase.isEmpty()) continue
            val key = phrase.lowercase()
            val existing = merged[key]
            merged[key] = if (existing == null) {
                signal.copy(phrase = phrase)
            } else {
                existing.copy(
                    at = maxOf(existing.at, signal.at),
                    weight = existing.weight + signal.weight,
                    description = existing.description ?: signal.description?.trim()?.ifBlank { null },
                )
            }
        }

        return merged.values
            .map { Scored(it.phrase, it.description, score(it, now)) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.phrase })
            .take(limit)
    }

    private fun score(signal: Signal, now: Long): Double {
        val ageDays = ((now - signal.at).coerceAtLeast(0)) / DAY_MS
        val recency = 0.5.pow(ageDays / HALF_LIFE_DAYS)
        // ln(1+n) so repeat sightings help but never dominate recency outright.
        val frequency = 1.0 + ln(1.0 + signal.weight.coerceAtLeast(1).toDouble())
        return recency * frequency
    }
}
