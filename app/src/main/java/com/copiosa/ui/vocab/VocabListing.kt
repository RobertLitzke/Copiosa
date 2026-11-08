package com.copiosa.ui.vocab

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.noteKey
import com.copiosa.domain.srs.isLeech
import com.copiosa.ui.components.stripInlineMarkdown
import java.text.Collator

/** How the vocabulary list is ordered. [NEWEST] is what it always was, and the default. */
enum class VocabSort {
    NEWEST,
    OLDEST,

    /** By headword, in the deck language's own collation — accented letters sort with their base. */
    ALPHABETICAL,

    /** By when the word next comes up; frozen words, which never do, go last. */
    DUE_SOONEST,

    /** By how often it has been forgotten — the words that are costing the most. */
    MOST_LAPSED,

    /** By frequency rank, commonest first; a word the list does not carry goes last. */
    MOST_COMMON,
}

/** Whether the list is narrowed by the word having been explained (LLM enrichment) or not. */
enum class ExplainedFilter { ANY, EXPLAINED, UNEXPLAINED }

/**
 * Everything that narrows the vocabulary list: the search text and each filter. The defaults let
 * everything through, so a filter at its default is one the learner has not touched.
 */
data class VocabFilter(
    val query: String = "",
    val markedOnly: Boolean = false,
    val dueWindow: DueWindow = DueWindow.ALL,
    val status: CardStatus? = null,
    /** A normalized part-of-speech key, as stored on the note. */
    val partOfSpeech: String? = null,
    val explained: ExplainedFilter = ExplainedFilter.ANY,
    /** Only words with a direction that keeps being forgotten — see [com.copiosa.domain.srs.Leech]. */
    val leechesOnly: Boolean = false,
) {
    /** Filters beyond the search box and the two always-visible controls, which live in the sheet. */
    val sheetFiltersActive: Boolean
        get() = status != null || partOfSpeech != null || explained != ExplainedFilter.ANY || leechesOnly

    /** Whether anything at all is narrowing the list. */
    val active: Boolean
        get() = query.isNotBlank() || markedOnly || dueWindow != DueWindow.ALL || sheetFiltersActive
}

/**
 * The list as shown: [cards] (one row per note — see [leadCard]) narrowed by [filter], then ordered
 * by [sort]. [ranks] is the frequency rank by card id, for [VocabSort.MOST_COMMON]; [now] is one
 * clock for the whole pass so every row is judged against the same moment. [leeches] is the note
 * keys with a leech among their cards — handed in because a row is only its note's lead card, and
 * the direction that keeps slipping may well be the other one.
 *
 * Every ordering falls back to newest-first for ties, so the list never reshuffles between
 * identical rows from one recomposition to the next.
 */
internal fun listVocab(
    cards: List<FlashcardEntity>,
    filter: VocabFilter,
    sort: VocabSort,
    now: Long,
    ranks: Map<Long, Int> = emptyMap(),
    collator: Collator = Collator.getInstance(),
    leeches: Set<Long> = emptySet(),
): List<FlashcardEntity> {
    val needle = filter.query.trim()
    // A frozen card is not scheduled to be seen, so it is never "coming up" in a window.
    val maxDue = filter.dueWindow.windowMs?.let { now + it }
    val kept = cards.filter { card ->
        (needle.isEmpty() ||
            card.front.contains(needle, ignoreCase = true) ||
            card.back.contains(needle, ignoreCase = true)) &&
            (!filter.markedOnly || card.marked) &&
            (maxDue == null || (!card.suspended && card.dueAt <= maxDue)) &&
            (filter.status == null || statusOf(card) == filter.status) &&
            (filter.partOfSpeech == null || card.partOfSpeech?.trim()?.lowercase() == filter.partOfSpeech) &&
            (!filter.leechesOnly || card.noteKey in leeches) &&
            when (filter.explained) {
                ExplainedFilter.ANY -> true
                ExplainedFilter.EXPLAINED -> !card.explanation.isNullOrBlank()
                ExplainedFilter.UNEXPLAINED -> card.explanation.isNullOrBlank()
            }
    }

    val newestFirst = compareByDescending<FlashcardEntity> { it.createdAt }.thenByDescending { it.noteKey }
    val order: Comparator<FlashcardEntity> = when (sort) {
        VocabSort.NEWEST -> newestFirst
        VocabSort.OLDEST -> newestFirst.reversed()
        VocabSort.ALPHABETICAL ->
            Comparator<FlashcardEntity> { a, b -> collator.compare(headword(a), headword(b)) }.then(newestFirst)
        VocabSort.DUE_SOONEST ->
            compareBy<FlashcardEntity> { it.suspended }.thenBy { it.dueAt }.then(newestFirst)
        VocabSort.MOST_LAPSED -> compareByDescending<FlashcardEntity> { it.lapses }.then(newestFirst)
        VocabSort.MOST_COMMON ->
            compareBy<FlashcardEntity> { ranks[it.id] ?: Int.MAX_VALUE }.then(newestFirst)
    }
    return kept.sortedWith(order)
}

/** The notes with a leech among [cards] — every card of the deck, not one row per note. */
internal fun leechNotesIn(cards: List<FlashcardEntity>): Set<Long> =
    cards.filter { it.isLeech }.mapTo(HashSet()) { it.noteKey }

/** The distinct part-of-speech keys in [cards], most frequent first — what the filter offers. */
internal fun partsOfSpeechIn(cards: List<FlashcardEntity>): List<String> =
    cards.mapNotNull { it.partOfSpeech?.trim()?.lowercase()?.ifBlank { null } }
        .groupingBy { it }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

private fun headword(card: FlashcardEntity): String = stripInlineMarkdown(card.front).trim()
