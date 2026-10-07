package com.copiosa.data.vocab

import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.translate.LocalTranslateResult
import com.copiosa.data.translate.OnDeviceTranslator
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.domain.vocab.freq.FrequencyEntry
import com.copiosa.domain.vocab.freq.FrequencyLists
import com.copiosa.domain.vocab.freq.FrequencyPos
import com.copiosa.domain.vocab.freq.VocabCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What one seeding run produced. [untranslated] words got a card, but with an unusable back. */
data class SeedSummary(val created: Int, val untranslated: Int)

/** Why a seeding run couldn't start or finish. */
sealed interface SeedError {
    /** The active language ships no frequency list. */
    data object NoList : SeedError

    /** ML Kit can't translate this language pair on-device. */
    data object Unsupported : SeedError

    /** The translation model isn't downloaded and couldn't be fetched. */
    data object Unavailable : SeedError
}

/** Either a completed run or the reason it couldn't happen. */
sealed interface SeedResult {
    data class Done(val summary: SeedSummary) : SeedResult
    data class Failed(val error: SeedError) : SeedResult
}

/**
 * Fills gaps in the deck from the bundled frequency list — the "seed a new language" path, and the
 * standing answer to "which word should I learn next".
 *
 * Backs come from the same on-device ML Kit translator the save-a-selection path uses, so seeding
 * works with no network and no API key. That buys one bare gloss per word and nothing else, which is
 * the deliberate trade: the cards land immediately and the existing background enrichment fills in
 * explanation, gender and CEFR later, a bounded batch at a time.
 *
 * A bare lemma translated in isolation is also where the gloss is weakest — *tener* and *haber* both
 * come back as "to have" — so cards stamped [SOURCE] are the one case where enrichment is allowed to
 * replace the back it finds, with a gloss written to distinguish the word from its near-synonyms.
 * See `FlashcardRepository.improvedBack`.
 *
 * New cards are due immediately everywhere else in the app, which is right for a word the learner
 * just met but wrong for five hundred at once — the review queue would be nothing else for weeks.
 * So [seed] spreads due dates forward at [perDay], introducing the most frequent words first.
 */
@Singleton
class FrequencySeeder @Inject constructor(
    private val flashcards: FlashcardRepository,
    private val coverage: VocabCoverage,
    private val translator: OnDeviceTranslator,
    private val settingsRepo: SettingsRepository,
) {

    /** The words ranked at or above [maxRank] with no card yet, most frequent first. */
    suspend fun missing(maxRank: Int): List<FrequencyEntry> = withContext(Dispatchers.Default) {
        val lang = settingsRepo.activeProfileTag.first()
        val list = FrequencyLists.forTag(lang) ?: return@withContext emptyList()
        coverage.missing(flashcards.observeAll().first(), list, maxRank)
    }

    /** How many cards [seed] would create for [maxRank] — the figure the confirm dialog shows. */
    suspend fun missingCount(maxRank: Int): Int = missing(maxRank).size

    /**
     * Creates a card for every missing word ranked at or above [maxRank], reporting `(done, total)`
     * as it goes. Cards come due at a rate of [perDay], starting today.
     *
     * A word ML Kit hands straight back untranslated still gets a card, counted in
     * [SeedSummary.untranslated]: a card with a poor back is visible and fixable, whereas a silently
     * skipped word is neither, and the enrichment pass may well repair it before the user sees it.
     */
    suspend fun seed(
        maxRank: Int,
        perDay: Int = DEFAULT_PER_DAY,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SeedResult = withContext(Dispatchers.Default) {
        val settings = settingsRepo.settings.first()
        val lang = settings.targetLanguage
        FrequencyLists.forTag(lang) ?: return@withContext SeedResult.Failed(SeedError.NoList)

        val pending = missing(maxRank)
        if (pending.isEmpty()) return@withContext SeedResult.Done(SeedSummary(0, 0))

        val rate = perDay.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        var created = 0
        var untranslated = 0
        onProgress(0, pending.size)
        for ((index, entry) in pending.withIndex()) {
            val back = when (val result = translator.translate(entry.lemma, lang, settings.nativeLanguage)) {
                is LocalTranslateResult.Success -> result.text
                // Nothing later in the run can succeed if the model itself is missing, so stop rather
                // than manufacture hundreds of untranslated cards. Whatever was created already stands.
                LocalTranslateResult.Unsupported ->
                    return@withContext failOrPartial(created, untranslated, SeedError.Unsupported)
                LocalTranslateResult.Unavailable ->
                    return@withContext failOrPartial(created, untranslated, SeedError.Unavailable)
            }
            if (back.trim().equals(entry.lemma, ignoreCase = true)) untranslated++
            flashcards.add(
                front = entry.lemma,
                back = back,
                contextSentence = null,
                sourceConversationId = null,
                partOfSpeech = cardPosFor(entry.pos),
                cardKind = CardKind.VOCAB,
                autoSource = SOURCE,
                dueAt = dueAtFor(now, index, rate),
                enrich = false,
            )
            created++
            onProgress(created, pending.size)
        }
        SeedResult.Done(SeedSummary(created, untranslated))
    }

    /** A run that died mid-way still counts what it made; only a run that made nothing is a failure. */
    private fun failOrPartial(created: Int, untranslated: Int, error: SeedError): SeedResult =
        if (created > 0) SeedResult.Done(SeedSummary(created, untranslated)) else SeedResult.Failed(error)

    companion object {
        /** The [com.copiosa.data.local.FlashcardEntity.autoSource] value this generator stamps. */
        const val SOURCE = FlashcardRepository.AUTO_SOURCE_FREQ

        /** New words introduced per day when the caller doesn't say. */
        const val DEFAULT_PER_DAY = 20

        /**
         * When the word at [index] of the seeding run should first come up: today for the first
         * [perDay], tomorrow for the next [perDay], and so on. Words arrive in rank order, so the
         * most useful ones are the ones that arrive today.
         */
        fun dueAtFor(now: Long, index: Int, perDay: Int): Long =
            now + (index / perDay.coerceAtLeast(1)) * ReviewWindows.DAY_MS

        /**
         * Translates a dictionary-pack part of speech into the vocabulary the card editor uses.
         * The pack says `adj`/`adv`/`intj`; the editor's dropdown says `adjective`/`adverb`, and
         * [com.copiosa.domain.highlight.HighlightTargetProvider] reads the card's value back when it
         * expands the headword, so a mismatch would silently disable inflection highlighting.
         *
         * Shared with [FrequencyPos.compatible], which reads the same value back off the card to
         * decide whether a frequency entry is really about that card's word.
         */
        fun cardPosFor(packPos: String): String = FrequencyPos.cardPosFor(packPos)
    }
}
