package com.copiosa.domain.listening

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * One listen-and-rebuild puzzle: a banked sentence the learner hears and then taps back into order.
 *
 * [tiles] is the sentence's words in their spoken order; [order] is the shuffled order the word bank
 * shows them in (indices into [tiles]). Both are fixed when the item is built, never during
 * composition — the runner takes its items by value, and a shuffle inside it would reshuffle the bank
 * under the learner on every recomposition.
 *
 * [audioPath] is null only while the standalone screen is still rendering this sentence's clip; a
 * routine pre-renders every clip before the plan is saved.
 */
@Serializable
data class RebuildItem(
    val sentenceId: Long,
    /** The sentence as written, punctuation and capitals intact — shown once it is rebuilt. */
    val sentence: String,
    val tiles: List<String>,
    val order: List<Int>,
    val translation: String? = null,
    val audioPath: String? = null,
)

/**
 * The pure half of listen-and-rebuild: cutting a sentence into tiles, shuffling them, and grading an
 * arrangement.
 *
 * The tiles carry no punctuation and no sentence-initial capital, because either one gives the order
 * away without listening: the capitalized word goes first and the word with the full stop goes last.
 * Grading compares words case-insensitively rather than tile identities, so two copies of "la" are
 * interchangeable — the learner placed the right *word*, which is all the ear can tell them.
 */
object ListenRebuild {

    /** Below this the order is guessable without hearing anything. */
    const val MIN_TILES = 4

    /** Above this the bank is a reading puzzle, and too many chips to tap on a phone. */
    const val MAX_TILES = 12

    private val WHITESPACE = Regex("\\s+")

    /**
     * The sentence's words as tiles, in spoken order, or null when it can't make a fair puzzle — too
     * short, too long, or with fewer than two distinct words (nothing to put in order).
     */
    fun tilesOf(sentence: String): List<String>? {
        val words = sentence.split(WHITESPACE)
            .map { token -> token.trim { !it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (words.size !in MIN_TILES..MAX_TILES) return null
        if (words.map(::keyOf).distinct().size < 2) return null
        // Only the first word is lowered: it is the one whose capital is a positional tell. A proper
        // noun elsewhere keeps its capital, which says nothing about where it goes.
        return listOf(words.first().replaceFirstChar { it.lowercaseChar() }) + words.drop(1)
    }

    /** What two tiles must share to be the same word for grading. */
    fun keyOf(tile: String): String = tile.lowercase()

    /**
     * A display order for [tiles] that does not already read as the answer. Retried a few times; a
     * four-word sentence shuffles back into itself one time in twenty-four, and a bank that arrives
     * solved is no puzzle.
     */
    fun shuffle(tiles: List<String>, random: Random): List<Int> {
        val solved = tiles.map(::keyOf)
        var order = tiles.indices.shuffled(random)
        repeat(SHUFFLE_TRIES) {
            if (order.map { keyOf(tiles[it]) } != solved) return order
            order = tiles.indices.shuffled(random)
        }
        // Still solved after every try (only possible with heavy repetition): rotate by one, which
        // moves every tile and, with two distinct words present, cannot leave the order unchanged.
        return order.drop(1) + order.first()
    }

    /**
     * How many of [placed] (tile indices, in the order the learner tapped them) are right from the
     * start. Equal to `tiles.size` when the sentence is rebuilt.
     */
    fun correctPrefix(tiles: List<String>, placed: List<Int>): Int {
        var n = 0
        for ((position, tile) in placed.withIndex()) {
            if (position >= tiles.size || keyOf(tiles[tile]) != keyOf(tiles[position])) break
            n++
        }
        return n
    }

    /** Builds a puzzle from a banked sentence, or null when [tilesOf] refuses it. */
    fun itemFor(
        sentenceId: Long,
        sentence: String,
        translation: String?,
        random: Random,
    ): RebuildItem? {
        val tiles = tilesOf(sentence) ?: return null
        return RebuildItem(
            sentenceId = sentenceId,
            sentence = sentence,
            tiles = tiles,
            order = shuffle(tiles, random),
            translation = translation,
        )
    }

    private const val SHUFFLE_TRIES = 8
}
