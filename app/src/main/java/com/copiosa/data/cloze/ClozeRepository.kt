package com.copiosa.data.cloze

import com.copiosa.data.local.ClozeDao
import com.copiosa.data.local.ClozeReviewEntity
import com.copiosa.data.local.ClozeSentenceEntity
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.translate.DictionaryResult
import com.copiosa.data.translate.LocalDictionary
import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeDistractors
import com.copiosa.domain.cloze.formatGloss
import com.copiosa.domain.cloze.ClozeGate
import com.copiosa.domain.cloze.ClozeGrading
import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.cloze.ClozeReject
import com.copiosa.domain.cloze.ClozeSessionItem
import com.copiosa.domain.cloze.ClozeText
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.domain.model.Cloze
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.srs.SpacedRepetitionScheduler
import com.copiosa.domain.vocab.freq.FrequencyList
import com.copiosa.domain.vocab.freq.FrequencyLists
import com.copiosa.domain.vocab.freq.FunctionWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * What one mining pass produced, so a caller can say "nothing new" rather than nothing at all.
 *
 * [reasons] is why the refused candidates were refused. Without it a pass that banks one sentence out
 * of two hundred is indistinguishable from a deck with nothing in it, which is exactly the state the
 * gate is most likely to leave a learner in and the hardest one to explain from the outside.
 */
data class ClozeMineResult(
    val banked: Int,
    val rejected: Int,
    val reasons: Map<ClozeReject, Int> = emptyMap(),
) {
    /** The rule that turned away the most candidates — what to tell the learner about. */
    val topReason: ClozeReject? get() = reasons.maxByOrNull { it.value }?.key
}

/**
 * The cloze bank: mining sentences into it, composing sittings out of it, and grading answers.
 *
 * Scheduling goes through the deck's own FSRS engine ([SpacedRepetitionScheduler.Memory]) rather than
 * a second scheduler, per the architecture's standing rule. A graded answer therefore does two
 * separate things — it moves the *sentence's* schedule, and (via
 * [FlashcardRepository.clozeNudge]) it nudges the *card's*. They are deliberately different
 * mechanisms: the sentence is genuinely reviewed, while the card is only credited a quarter of one.
 */
@Singleton
class ClozeRepository @Inject constructor(
    private val dao: ClozeDao,
    private val flashcardDao: FlashcardDao,
    private val flashcards: FlashcardRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val settingsRepo: SettingsRepository,
    private val highlight: HighlightTargetProvider,
    private val dictionary: LocalDictionary,
) {

    // --- state for the screen ------------------------------------------------

    fun observeActiveCount(lang: String): Flow<Int> = dao.observeCountActive(lang)

    fun observeDueCount(lang: String, now: Long = System.currentTimeMillis()): Flow<Int> =
        dao.observeCountDue(lang, now)

    fun observeFlagged(lang: String): Flow<List<ClozeSentenceEntity>> = dao.observeFlagged(lang)

    suspend fun activeLanguage(): String = settingsRepo.settings.first().targetLanguage

    // --- mining --------------------------------------------------------------

    /**
     * Assembles the per-run view of the learner. Built once and handed to every source, because the
     * gate asks a question per token and a book chapter is thousands of them.
     */
    suspend fun buildContext(lang: String): ClozeMineContext = withContext(Dispatchers.IO) {
        val cards = flashcardDao.allActive(lang)
        val formsByCard = highlight.formsByCard(cards)
        val formToCard = HashMap<String, Long>(cards.size * 4)
        for ((cardId, forms) in formsByCard) {
            // First card wins: two cards claiming one form is rare and either is a fine nudge target.
            for (form in forms) formToCard.putIfAbsent(form, cardId)
        }
        ClozeMineContext(
            lang = lang,
            formToCard = formToCard,
            list = FrequencyLists.forTag(lang),
            banked = dao.countsByLemma(lang).associate { it.lemma to it.total },
            posByCard = cards.associate { it.id to it.partOfSpeech },
            functionWords = FunctionWords.forTag(lang),
            bankSize = dao.countAll(lang),
        )
    }

    /**
     * Runs [sources] in order and banks whatever passes the gate.
     *
     * Every source is held to the same [ClozeGate], so quality lives in one place rather than being
     * re-implemented per source. Insertion is IGNORE-on-conflict, which is what lets this run on a
     * timer: rescanning the same material banks nothing and costs one index probe per candidate.
     */
    suspend fun mine(
        sources: List<ClozeSource>,
        ctx: ClozeMineContext,
        limitPerSource: Int,
        now: Long = System.currentTimeMillis(),
    ): ClozeMineResult = withContext(Dispatchers.IO) {
        var rejected = 0
        val reasons = HashMap<ClozeReject, Int>()
        val rows = ArrayList<ClozeSentenceEntity>()
        // Mirrors the caps while this run accumulates, so one pass can't blow past them before the
        // counts are re-read on the next.
        val addedPerLemma = HashMap<String, Int>()

        for (source in sources) {
            for (candidate in source.mine(ctx, limitPerSource)) {
                val lemma = candidate.lemma
                val already = (ctx.banked[lemma] ?: 0) + (addedPerLemma[lemma] ?: 0)
                if (already >= ClozeMineContext.PER_LEMMA_CAP) continue
                if (ctx.bankSize + rows.size >= ClozeMineContext.BANK_MAX_PER_LANGUAGE) break

                val pos = candidate.cardId?.let { ctx.posByCard[it] }
                val reject =
                    ClozeGate.reject(candidate, ctx.knownForms, ctx.list, pos, ctx.functionWords)
                if (reject != null) {
                    rejected++
                    reasons[reject] = (reasons[reject] ?: 0) + 1
                    continue
                }
                rows += candidate.toRow(ctx.lang, now)
                addedPerLemma[lemma] = (addedPerLemma[lemma] ?: 0) + 1
            }
        }

        val banked = if (rows.isEmpty()) emptyList() else dao.insertAll(rows)
        ClozeMineResult(banked = banked.count { it != -1L }, rejected = rejected, reasons = reasons)
    }

    private fun ClozeCandidate.toRow(lang: String, now: Long) = ClozeSentenceEntity(
        targetLanguage = lang,
        text = ClozeText.markupFor(this),
        answer = answer,
        lemma = lemma,
        textKey = ClozeText.keyOf(sentence),
        cardId = cardId,
        translation = translation,
        source = source,
        // New sentences are due immediately: unlike a bulk card seeding run this is a pool the
        // learner dips into, not a queue that has to be worked off.
        dueAt = now,
        state = FlashcardState.NEW,
        createdAt = now,
    )

    // --- composing a sitting -------------------------------------------------

    /**
     * Up to [size] items, soonest-due first, with options resolved.
     *
     * An item whose options can't be filled is **dropped, never padded** — a padded option set gives
     * the answer away, which is worse than a shorter sitting. The pool is over-read to absorb that.
     */
    suspend fun session(
        lang: String,
        size: Int,
        seed: Long = System.currentTimeMillis(),
    ): List<ClozeSessionItem> = withContext(Dispatchers.IO) {
        val pool = dao.studyPool(lang, limit = size * OVER_READ)
        val list = FrequencyLists.forTag(lang)
        // Only needed for a language with no bundled frequency list, where the deck is the only pool
        // of real words available to draw wrong options from.
        val deckFallback = if (list == null) {
            flashcardDao.allActive(lang).map { normalizeWord(it.targetTerm) }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val out = ArrayList<ClozeSessionItem>(size)
        for (row in pool) {
            if (out.size >= size) break
            itemFor(row, list, deckFallback, Random(seed xor row.id))?.let { out += it }
        }
        out
    }

    private suspend fun itemFor(
        row: ClozeSentenceEntity,
        list: FrequencyList?,
        deckFallback: List<String>,
        random: Random,
    ): ClozeSessionItem? {
        val prompt = Cloze.question(row.text, activeOrd = null)
        val plain = Cloze.answer(row.text)
        val sentenceTokens = plain.split(WHITESPACE).map(::normalizeWord).filter { it.isNotEmpty() }.toSet()

        val stored = row.distractors?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }
        val distractors = stored?.takeIf { it.size >= OPTIONS - 1 }
            ?: freshDistractors(row, list, sentenceTokens, deckFallback, random)?.also {
                dao.setDistractors(row.id, it.joinToString(SEPARATOR))
            }
            ?: return null

        val options = (distractors + row.answer).shuffled(random)
        return ClozeSessionItem(
            sentenceId = row.id,
            prompt = prompt,
            options = options,
            answerIndex = options.indexOf(row.answer),
            translation = row.translation,
            glosses = options.map { displayGloss(it, row.targetLanguage) },
            wordCount = plain.split(WHITESPACE).count { it.isNotBlank() },
            repetitions = row.repetitions,
            cardId = row.cardId,
        )
    }

    /**
     * Picks options and then checks them for meaning: a distractor that shares an English gloss with
     * the answer is a second correct answer, not a wrong one.
     *
     * The gloss check runs only when the dictionary pack happens to be downloaded, and only over the
     * three chosen words rather than the pool — so it is a handful of lookups per item, and its
     * absence costs quality rather than function.
     */
    private suspend fun freshDistractors(
        row: ClozeSentenceEntity,
        list: FrequencyList?,
        sentenceTokens: Set<String>,
        deckFallback: List<String>,
        random: Random,
        alsoExclude: Set<String> = emptySet(),
    ): List<String>? {
        val card = row.cardId?.let { flashcardDao.getById(it) }
        val excluded = HashSet<String>()
        excluded += alsoExclude.map(::normalizeWord)
        // The card's own synonym field is the app's existing record of "these mean the same thing".
        card?.altForms?.split(SEPARATOR, ",")?.forEach { normalizeWord(it).takeIf(String::isNotEmpty)?.let(excluded::add) }

        val answerGlosses = englishGlosses(row.answer, row.targetLanguage)
        repeat(GLOSS_RETRIES) {
            val picked = ClozeDistractors.pick(
                answer = row.answer,
                answerPos = card?.partOfSpeech,
                list = list,
                sentenceTokens = sentenceTokens,
                excluded = excluded,
                deckFallback = deckFallback,
                random = random,
                count = OPTIONS - 1,
            ) ?: return null

            if (answerGlosses.isEmpty()) return picked
            val tooClose = picked.filter { englishGlosses(it, row.targetLanguage).any(answerGlosses::contains) }
            if (tooClose.isEmpty()) return picked
            excluded += tooClose
        }
        return null
    }

    /**
     * A word's meaning in a few words, for showing beside it after the answer, or null when the
     * dictionary has nothing to say — which includes the common case of no pack downloaded.
     *
     * Deliberately not [englishGlosses]: that one lowercases into a set for the collision check and
     * so loses both order and case, and the first sense as the dictionary wrote it is the one worth
     * reading. Two senses is the ceiling because this has to sit on one line beside the word.
     */
    private suspend fun displayGloss(word: String, lang: String): String? {
        if (!dictionary.isDownloaded(lang)) return null
        val result = dictionary.lookup(word, lang) as? DictionaryResult.Found ?: return null
        return formatGloss(result.entries.flatMap { it.englishDefinitions })
    }

    /** The answer's English meanings, or empty when there is no pack to ask. */
    private suspend fun englishGlosses(word: String, lang: String): Set<String> {
        if (!dictionary.isDownloaded(lang)) return emptySet()
        val result = dictionary.lookup(word, lang)
        if (result !is DictionaryResult.Found) return emptySet()
        return result.entries
            .flatMap { it.englishDefinitions }
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    // --- translation ---------------------------------------------------------

    /**
     * Banked sentences still missing a translation, as (id, plain sentence) pairs.
     *
     * The stored text carries `{{c1::…}}` markup; what goes to a translator is the sentence with the
     * blank filled in, because a model handed the markup translates the markup as often as the prose.
     */
    suspend fun pendingTranslation(lang: String, limit: Int): List<Pair<Long, String>> =
        withContext(Dispatchers.IO) {
            dao.untranslated(lang, limit).map { it.id to Cloze.answer(it.text) }
        }

    /** Stores one back-filled translation. Blank input is ignored rather than stored as a gap. */
    suspend fun applyTranslation(sentenceId: Long, translation: String) = withContext(Dispatchers.IO) {
        translation.trim().takeIf { it.isNotEmpty() }?.let { dao.setTranslation(sentenceId, it) }
        Unit
    }

    // --- adjudication --------------------------------------------------------

    /**
     * Items waiting to be checked for having more than one right answer — the learner's own
     * "could be more than one answer" reports, built into full items so a model can see the options
     * they were actually shown.
     */
    suspend fun pendingAdjudication(lang: String, limit: Int): List<ClozeSessionItem> =
        withContext(Dispatchers.IO) {
            val rows = dao.flaggedFor(lang, FLAG_AMBIGUOUS, limit)
            if (rows.isEmpty()) return@withContext emptyList()
            val list = FrequencyLists.forTag(lang)
            val deckFallback = if (list == null) {
                flashcardDao.allActive(lang).map { normalizeWord(it.targetTerm) }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            rows.mapNotNull { itemFor(it, list, deckFallback, Random(it.id)) }
        }

    /**
     * Acts on one verdict.
     *
     * An underdetermined *sentence* is suspended: no set of options can rescue a sentence that
     * constrains nothing. Options that also fit are excluded and replaced, and the flag is cleared —
     * so the item returns to circulation with a set that has survived a check.
     */
    suspend fun applyVerdict(
        item: ClozeSessionItem,
        alsoFit: List<String>,
        underdetermined: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (underdetermined) {
            dao.setFlag(item.sentenceId, suspended = true, reason = FLAG_WRONG)
            return@withContext
        }
        val row = dao.byId(item.sentenceId) ?: return@withContext
        if (alsoFit.isEmpty()) {
            // Checked and sound: clear the flag but keep the options that were verified.
            dao.setDistractors(item.sentenceId, item.options.filter { it != row.answer }.joinToString(SEPARATOR))
            dao.setFlag(item.sentenceId, suspended = false, reason = null)
            return@withContext
        }

        val list = FrequencyLists.forTag(row.targetLanguage)
        val plain = Cloze.answer(row.text)
        val tokens = plain.split(WHITESPACE).map(::normalizeWord).filter { it.isNotEmpty() }.toSet()
        val replacement = freshDistractors(
            row = row,
            list = list,
            sentenceTokens = tokens,
            deckFallback = emptyList(),
            random = Random(row.id + alsoFit.size),
            alsoExclude = alsoFit.toSet(),
        )
        if (replacement == null) {
            // Nothing else in the pool is safely wrong for this sentence, so the item can't be fixed.
            dao.setFlag(item.sentenceId, suspended = true, reason = FLAG_WRONG)
        } else {
            dao.setDistractors(item.sentenceId, replacement.joinToString(SEPARATOR))
            dao.setFlag(item.sentenceId, suspended = false, reason = null)
        }
    }

    // --- grading -------------------------------------------------------------

    /**
     * Records one answer: the sentence is genuinely reviewed, and the card it teaches is credited a
     * quarter of a review.
     *
     * [nudgeCard] is false when this card has already been nudged in the current sitting. Without
     * that guard three sentences for one word would move it three times in one session — see
     * `FlashcardRepository.clozeNudge`.
     */
    /**
     * Grades a sentence identified only by id — what a stored routine plan carries.
     *
     * A plan outlives the bank it was built from, so an id that no longer resolves is expected and
     * simply does nothing: the learner still answered the question, the bank just has nothing to
     * update.
     */
    suspend fun recordById(
        sentenceId: Long,
        correct: Boolean,
        latencyMs: Long,
        nudgeCard: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val row = dao.byId(sentenceId) ?: return@withContext
        val plain = Cloze.answer(row.text)
        record(
            item = ClozeSessionItem(
                sentenceId = row.id,
                prompt = Cloze.question(row.text, activeOrd = null),
                options = listOf(row.answer),
                answerIndex = 0,
                translation = row.translation,
                wordCount = plain.split(WHITESPACE).count { it.isNotBlank() },
                repetitions = row.repetitions,
                cardId = row.cardId,
            ),
            correct = correct,
            latencyMs = latencyMs,
            nudgeCard = nudgeCard,
            now = now,
        )
    }

    suspend fun record(
        item: ClozeSessionItem,
        correct: Boolean,
        latencyMs: Long,
        nudgeCard: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val row = dao.byId(item.sentenceId) ?: return@withContext
        val rating = ClozeGrading.gradeFor(correct, latencyMs, item.wordCount, row.repetitions)
        val retention = settingsRepo.settingsFor(row.targetLanguage).desiredRetention
        val after = scheduler.schedule(row.memory(), rating, now, retention, cardId = row.id.toInt())

        dao.update(
            row.copy(
                dueAt = after.dueAt,
                intervalDays = after.intervalDays,
                stability = after.stability,
                difficulty = after.difficulty,
                fsrsStep = after.fsrsStep,
                repetitions = after.repetitions,
                lapses = after.lapses,
                lastReviewedAt = after.lastReviewedAt,
                state = after.state,
            ),
        )
        dao.insertReview(
            ClozeReviewEntity(
                clozeId = row.id,
                reviewedAt = now,
                rating = rating,
                lastIntervalDays = row.intervalDays,
                intervalDays = after.intervalDays,
                stabilityAfter = after.stability,
                difficultyAfter = after.difficulty,
                state = after.state,
                latencyMs = latencyMs,
            ),
        )

        if (nudgeCard) {
            row.cardId?.let { flashcardDao.getById(it) }?.let { flashcards.clozeNudge(it, correct, now) }
        }
    }

    private fun ClozeSentenceEntity.memory() = SpacedRepetitionScheduler.Memory(
        state = state,
        dueAt = dueAt,
        intervalDays = intervalDays,
        stability = stability,
        difficulty = difficulty,
        fsrsStep = fsrsStep,
        lastReviewedAt = lastReviewedAt,
        repetitions = repetitions,
        lapses = lapses,
    )

    // --- the learner throwing an item out ------------------------------------

    /**
     * "Could be more than one answer": the sentence survives but its options don't. Clearing
     * [ClozeSentenceEntity.distractors] is what makes them regenerate on next sight, and the flag is
     * what the LLM adjudication pass looks for.
     */
    suspend fun flagAmbiguous(sentenceId: Long) = withContext(Dispatchers.IO) {
        dao.setDistractors(sentenceId, null)
        dao.setFlag(sentenceId, suspended = false, reason = FLAG_AMBIGUOUS)
    }

    /**
     * "Bad sentence": suspended rather than deleted, and that is load-bearing. The unique index means
     * a deleted row would be re-mined identically from the same book paragraph on the next
     * background pass; the suspended row is the tombstone that makes the rejection stick.
     */
    suspend fun flagWrong(sentenceId: Long) = withContext(Dispatchers.IO) {
        dao.setFlag(sentenceId, suspended = true, reason = FLAG_WRONG)
    }

    /** Puts a flagged sentence back into circulation. */
    suspend fun unflag(sentenceId: Long) = withContext(Dispatchers.IO) {
        dao.setFlag(sentenceId, suspended = false, reason = null)
    }

    /** Removes a sentence for good — and, with it, the tombstone stopping it being re-mined. */
    suspend fun delete(sentenceId: Long) = withContext(Dispatchers.IO) { dao.delete(sentenceId) }

    companion object {
        const val OPTIONS = 4
        const val FLAG_AMBIGUOUS = "ambiguous"
        const val FLAG_WRONG = "wrong"

        /** Multiple of the session size to read, so items dropped for want of options don't shorten it. */
        private const val OVER_READ = 2

        /** Attempts to replace a distractor that turned out to share the answer's meaning. */
        private const val GLOSS_RETRIES = 3

        private const val SEPARATOR = "; "
        private val WHITESPACE = Regex("\\s+")
    }
}
