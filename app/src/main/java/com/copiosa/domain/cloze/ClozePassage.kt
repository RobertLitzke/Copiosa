package com.copiosa.domain.cloze

import com.copiosa.domain.highlight.normalizeWord
import kotlin.random.Random

/** One gap in a passage: where it is, what goes in it, and whose card it practises. */
data class PassageBlank(
    val start: Int,
    val end: Int,
    /** The word as the passage wrote it. */
    val answer: String,
    val cardId: Long,
)

/**
 * A stretch of reading with several of its words taken out and offered back as a word bank.
 *
 * The single-sentence cloze asks "which word fits here"; this asks the harder and more natural
 * question of sorting several words into the places they belong, with the whole passage as context
 * and every blank's answer sitting in the bank as a distractor for the others.
 */
data class ClozePassageItem(
    val text: String,
    val blanks: List<PassageBlank>,
    /** The answers and a couple of extra words, lower case and shuffled. */
    val bank: List<String>,
    val translation: String?,
)

object ClozePassage {

    const val MIN_BLANKS = 3
    const val MAX_BLANKS = 5

    /** Below this there is too little to read; above it the passage stops fitting a phone screen. */
    val WORDS = 25..90

    /** Words left standing between two blanks, so each gap keeps some context of its own. */
    const val MIN_GAP = 4

    /** Words in the bank that belong nowhere, so the last blank is not filled by elimination. */
    const val EXTRAS = 2

    private val TOKEN = Regex("[\\p{L}\\p{M}\\p{Nd}'’-]+")

    /**
     * A passage item from [text], or null when it is the wrong length or holds too few of the
     * learner's words to be worth it.
     *
     * [formToCard] is every form the learner has a card for. A word is blanked at most once per card,
     * never as the first word of a sentence (the bank is lower case, so a capital would mark it), and
     * never within [MIN_GAP] words of another blank. [extras] supplies the words that fit nowhere.
     */
    fun build(
        text: String,
        translation: String?,
        formToCard: Map<String, Long>,
        extras: List<String>,
        random: Random,
    ): ClozePassageItem? {
        val tokens = TOKEN.findAll(text).toList()
        if (tokens.size !in WORDS) return null

        val candidates = tokens.withIndex().filter { (_, token) ->
            token.value.first().isLowerCase() && formToCard.containsKey(normalizeWord(token.value))
        }
        val chosen = ArrayList<IndexedValue<MatchResult>>()
        val usedCards = HashSet<Long>()
        val usedWords = HashSet<String>()
        for (candidate in candidates.shuffled(random)) {
            if (chosen.size >= MAX_BLANKS) break
            val form = normalizeWord(candidate.value.value)
            val cardId = formToCard.getValue(form)
            if (cardId in usedCards || form in usedWords) continue
            if (chosen.any { kotlin.math.abs(it.index - candidate.index) <= MIN_GAP }) continue
            // The same word left standing elsewhere in the passage would spell the answer out.
            if (tokens.count { normalizeWord(it.value) == form } > 1) continue
            chosen += candidate
            usedCards += cardId
            usedWords += form
        }
        if (chosen.size < MIN_BLANKS) return null

        val blanks = chosen.sortedBy { it.index }.map { (_, token) ->
            PassageBlank(token.range.first, token.range.last + 1, token.value, formToCard.getValue(normalizeWord(token.value)))
        }
        val inPassage = tokens.mapTo(HashSet()) { normalizeWord(it.value) }
        val spare = extras.asSequence()
            .map(::normalizeWord)
            .filter { it.isNotEmpty() && it !in inPassage && it.none(Char::isWhitespace) }
            .distinct()
            .toList()
            .shuffled(random)
            .take(EXTRAS)
        return ClozePassageItem(
            text = text,
            blanks = blanks,
            bank = (blanks.map { normalizeWord(it.answer) } + spare).shuffled(random),
            translation = translation?.takeIf { it.isNotBlank() },
        )
    }

    /** Whether [word] from the bank is what [blank] wants. */
    fun fits(blank: PassageBlank, word: String?): Boolean =
        word != null && normalizeWord(word) == normalizeWord(blank.answer)
}
