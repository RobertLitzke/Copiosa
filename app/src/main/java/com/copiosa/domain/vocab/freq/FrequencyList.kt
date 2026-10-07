package com.copiosa.domain.vocab.freq

import com.copiosa.domain.highlight.normalizeWord

/** One entry of a bundled frequency list: a headword, where it ranks, and what part of speech it is. */
data class FrequencyEntry(
    val lemma: String,
    /** 1-based position in the list; 1 is the most frequent word in the language. */
    val rank: Int,
    /** Dictionary-pack part of speech (`noun`/`verb`/`adj`/`adv`/`intj`/`phrase`). */
    val pos: String,
)

/**
 * What a surface form resolved to: the [rank] of the entry claiming it, that entry's [pos], and
 * whether the form is the entry's own headword rather than one of its inflections.
 */
data class FormMatch(val rank: Int, val pos: String, val isHeadword: Boolean)

/**
 * Part-of-speech vocabularies. The bundled lists (like the dictionary pack they were generated
 * against) say `adj`/`adv`/`intj`; a card's [com.copiosa.data.local.FlashcardEntity.partOfSpeech]
 * says `adjective`/`adverb`/`expression`, because that is what the card editor's dropdown offers.
 */
object FrequencyPos {

    /** Translates a list/pack part of speech into the vocabulary the card editor uses. */
    fun cardPosFor(packPos: String): String = when (packPos.trim().lowercase()) {
        "noun" -> "noun"
        "verb" -> "verb"
        "adj" -> "adjective"
        "adv" -> "adverb"
        "intj" -> "expression"
        "phrase" -> "phrase"
        else -> UNKNOWN
    }

    /**
     * Whether a card whose part of speech is [cardPos] can be the word a [packPos] entry is about.
     *
     * Either side saying nothing means yes: a card the enrichment pass hasn't reached yet must keep
     * ranking as it always did, and refusing every un-annotated card would quietly empty the
     * coverage figures. Only a *stated* disagreement — a noun card against a verb entry — is a no.
     */
    fun compatible(packPos: String, cardPos: String?): Boolean {
        val card = cardPos?.trim()?.lowercase()?.ifBlank { null } ?: return true
        if (card == UNKNOWN || card == "other") return true
        val entry = cardPosFor(packPos)
        return entry == UNKNOWN || entry == card
    }

    private const val UNKNOWN = "unknown"
}

/**
 * The frequency band a word falls in, used for the card chips and for choosing how hard to push a
 * word up the review queue. Bands are nested: rank 40 is in [TOP_100], and every band's [maxRank]
 * includes everything below it.
 */
enum class FrequencyBucket(val maxRank: Int) {
    TOP_100(100),
    TOP_250(250),
    TOP_500(500),
    TOP_1K(1000),
    TOP_2K(2000);

    companion object {
        /** The tightest band containing [rank], or null for a word outside the list entirely. */
        fun forRank(rank: Int): FrequencyBucket? = entries.firstOrNull { rank <= it.maxRank }
    }
}

/**
 * A language's frequency-ranked vocabulary, parsed from the generated data in
 * `domain/vocab/freq/data/` (see `tools/build_frequency.py`).
 *
 * The source corpus counts surface forms, so the generator lemmatises and rolls those counts up: one
 * entry per headword, carrying every surface form the corpus spelled it with. That gives two views,
 * both built once on first use and then read-only:
 *
 * - [entries], in rank order, for "which words should I learn next".
 * - [rankOfForm], mapping *any* of those surface forms to its headword's rank, for "does the
 *   learner already have this word". A card saved as `gatos` and one saved as `gato` both resolve.
 *
 * Parsing is deferred behind [lines] so a language the user never selects costs nothing.
 */
class FrequencyList(private val lines: () -> List<String>) {

    /** Every entry, most frequent first. Index + 1 is the entry's rank. */
    val entries: List<FrequencyEntry> by lazy {
        lines().mapIndexedNotNull { index, line ->
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size < 2) return@mapIndexedNotNull null
            FrequencyEntry(lemma = parts[0], rank = index + 1, pos = parts[1])
        }
    }

    /**
     * Normalized surface form to the entry it belongs to. The generator guarantees no form is
     * claimed by two headwords, so this is unambiguous regardless of insertion order.
     */
    private val formMatches: Map<String, FormMatch> by lazy {
        val out = HashMap<String, FormMatch>(entries.size * 8)
        lines().forEachIndexed { index, line ->
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.isEmpty()) return@forEachIndexed
            val rank = index + 1
            val pos = parts.getOrNull(1).orEmpty()
            out[normalizeWord(parts[0])] = FormMatch(rank, pos, isHeadword = true)
            parts.getOrNull(2)?.split(FORM_SEPARATOR)?.forEach { form ->
                normalizeWord(form).takeIf(String::isNotEmpty)
                    ?.let { out[it] = FormMatch(rank, pos, isHeadword = it == normalizeWord(parts[0])) }
            }
        }
        out
    }

    /**
     * The entry [word] belongs to, or null when it isn't in the list.
     *
     * Richer than [rankOfForm] because a form match alone can be misleading: the generator rolls a
     * homograph's counts onto whichever reading the corpus is mostly about, so *dado* and *traje*
     * live in the paradigms of *dar* and *traer* and have no noun entry of their own. Matching a
     * learner's card for the die or the suit against those would rank a noun as a top-100 verb —
     * hence [FormMatch.pos], which lets the caller refuse a reading the card contradicts.
     */
    fun matchOfForm(word: String): FormMatch? = formMatches[normalizeWord(word)]

    /**
     * Every surface form in the list, normalized, mapped to the entry claiming it — the pool the
     * cloze distractor picker draws from. Exposed rather than rebuilt because it already exists for
     * [matchOfForm], and because it is the only structure in the app carrying *inflections* with a
     * part of speech attached, which is what lets a distractor agree with the answer's shape.
     */
    val formIndex: Map<String, FormMatch> get() = formMatches

    /** The rank of the headword [word] belongs to, or null when it isn't in the list. */
    fun rankOfForm(word: String): Int? = matchOfForm(word)?.rank

    /** Entries ranked at or above [maxRank] (i.e. `rank <= maxRank`), in rank order. */
    fun through(maxRank: Int): List<FrequencyEntry> = entries.takeWhile { it.rank <= maxRank }

    /** How many words the list holds — the denominator for coverage. */
    val size: Int get() = entries.size

    companion object {
        private const val FIELD_SEPARATOR = "|"
        private const val FORM_SEPARATOR = ","
    }
}
