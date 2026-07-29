package com.copiosa.domain.topics

import com.copiosa.domain.topics.TopicSuggestionScorer.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicSuggestionScorerTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun `empty input yields nothing`() {
        assertEquals(emptyList<TopicSuggestionScorer.Scored>(), TopicSuggestionScorer.rank(emptyList(), now))
    }

    @Test
    fun `more recent topics outrank older ones of equal weight`() {
        val ranked = TopicSuggestionScorer.rank(
            listOf(
                Signal("old", at = now - 60 * day),
                Signal("recent", at = now - 1 * day),
            ),
            now,
        )
        assertEquals(listOf("recent", "old"), ranked.map { it.phrase })
    }

    @Test
    fun `repeat sightings raise a topic above a fresher one-off`() {
        val ranked = TopicSuggestionScorer.rank(
            listOf(
                Signal("repeated", at = now - 3 * day, weight = 12),
                Signal("once", at = now - 1 * day, weight = 1),
            ),
            now,
        )
        assertEquals("repeated", ranked.first().phrase)
    }

    @Test
    fun `frequency never fully overcomes a stale timestamp`() {
        // Half-life is 14 days, so a year-old topic is decayed into irrelevance no matter the count.
        val ranked = TopicSuggestionScorer.rank(
            listOf(
                Signal("ancient", at = now - 365 * day, weight = 1000),
                Signal("fresh", at = now, weight = 1),
            ),
            now,
        )
        assertEquals("fresh", ranked.first().phrase)
    }

    @Test
    fun `duplicates merge case-insensitively, summing weight and keeping the newest timestamp`() {
        val ranked = TopicSuggestionScorer.rank(
            listOf(
                Signal("Cooking", at = now - 30 * day, weight = 1, description = null),
                Signal("cooking", at = now - 1 * day, weight = 1, description = "food talk"),
                Signal("unrelated", at = now - 1 * day, weight = 1),
            ),
            now,
        )
        assertEquals(2, ranked.size)
        val cooking = ranked.single { it.phrase.equals("cooking", ignoreCase = true) }
        // First spelling wins the display form; the description is picked up from the later sighting.
        assertEquals("Cooking", cooking.phrase)
        assertEquals("food talk", cooking.description)
        // Merged weight of 2 at the newest timestamp beats the single unrelated signal.
        assertTrue(cooking.score > ranked.single { it.phrase == "unrelated" }.score)
    }

    @Test
    fun `blank phrases are dropped and the limit is respected`() {
        val ranked = TopicSuggestionScorer.rank(
            listOf(
                Signal("   ", at = now),
                Signal("a", at = now),
                Signal("b", at = now - day),
                Signal("c", at = now - 2 * day),
            ),
            now,
            limit = 2,
        )
        assertEquals(listOf("a", "b"), ranked.map { it.phrase })
    }

    @Test
    fun `phrases are trimmed`() {
        val ranked = TopicSuggestionScorer.rank(listOf(Signal("  spaced  ", at = now)), now)
        assertEquals("spaced", ranked.single().phrase)
    }
}
