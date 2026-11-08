package com.copiosa.data.cloze

import com.copiosa.data.local.BookDao
import com.copiosa.data.local.ClozeDao
import com.copiosa.data.local.ClozePoolRow
import com.copiosa.data.local.ClozeReviewEntity
import com.copiosa.data.local.ClozeSentenceEntity
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.translate.DictionaryResult
import com.copiosa.data.translate.LocalDictionary
import com.copiosa.data.translate.LocalTranslateResult
import com.copiosa.data.translate.OnDeviceTranslator
import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeDistractors
import com.copiosa.domain.cloze.formatGloss
import com.copiosa.domain.cloze.ClozeGate
import com.copiosa.domain.cloze.ClozeGrading
import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.book.nativeText
import com.copiosa.domain.book.targetText
import com.copiosa.domain.cloze.ClozePassage
import com.copiosa.domain.cloze.ClozePassageItem
import com.copiosa.domain.cloze.ClozePoolEntry
import com.copiosa.domain.cloze.ClozeReject
import com.copiosa.domain.cloze.ClozeSelector
import com.copiosa.domain.cloze.ClozeSessionItem
import com.copiosa.domain.cloze.ClozeText
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.listening.RadioLine
import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.domain.model.Cloze
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.domain.srs.SpacedRepetitionScheduler
import com.copiosa.domain.vocab.freq.FrequencyList
import com.copiosa.domain.vocab.freq.FrequencyLists
import com.copiosa.domain.vocab.freq.FunctionWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZoneId
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

/** Progress along the common-words track; see [ClozeRepository.trackProgress]. */
data class ClozeTrackProgress(val passed: Int, val banked: Int, val total: Int)

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
    private val translator: OnDeviceTranslator,
    private val bookDao: BookDao,
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
    suspend fun buildContext(
        lang: String,
        now: Long = System.currentTimeMillis(),
    ): ClozeMineContext = withContext(Dispatchers.IO) {
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
            settledCards = dao.settledCardIds(
                matureDays = ReviewWindows.MATURE_THRESHOLD_DAYS,
                dueAfter = now + ReviewWindows.HIGHLIGHT_DUE_MS,
            ).toSet(),
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

        // A full bank is not a closed one. It used to be: the first stocking run filled it from the
        // deck's example sentences and nothing from later reading could ever get in. Now the room a
        // run needs beyond the ceiling comes from sentences whose word has settled.
        val free = (ClozeMineContext.BANK_MAX_PER_LANGUAGE - ctx.bankSize).coerceAtLeast(0)
        val evictable = if (free >= sources.size * limitPerSource) {
            emptyList()
        } else {
            dao.evictable(
                lang = ctx.lang,
                matureDays = ReviewWindows.MATURE_THRESHOLD_DAYS,
                dueAfter = now + ReviewWindows.HIGHLIGHT_DUE_MS,
                limit = sources.size * limitPerSource,
            )
        }
        val room = free + evictable.size

        for (source in sources) {
            for (candidate in source.mine(ctx, limitPerSource)) {
                val lemma = candidate.lemma
                val already = (ctx.banked[lemma] ?: 0) + (addedPerLemma[lemma] ?: 0)
                if (already >= ClozeMineContext.PER_LEMMA_CAP) continue
                if (rows.size >= room) break
                // A settled word's sentences are what rotation removes; mining one would undo it.
                if (!ctx.wantsSentencesFor(candidate.cardId)) continue

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

        if (rows.isEmpty()) return@withContext ClozeMineResult(0, rejected, reasons)
        val banked = dao.insertAll(withLocalTranslations(rows, ctx.lang)).count { it != -1L }
        // Only as many out as actually went in: a run whose candidates were all duplicates of what
        // the bank holds must not shrink it.
        val over = ctx.bankSize + banked - ClozeMineContext.BANK_MAX_PER_LANGUAGE
        if (over > 0) dao.deleteAll(evictable.take(over))
        ClozeMineResult(banked = banked, rejected = rejected, reasons = reasons)
    }

    /**
     * [rows] with the on-device translator's rendering filled in wherever a source had none to give.
     *
     * The local miners bank no translation on purpose — a card's back glosses the word, not the
     * sentence — and that used to leave most of the bank unexplained until the model's trickle
     * reached it. The translator is already on the phone for tap-to-define; a readable translation
     * now beats a better one in a month. Marked [ClozeSentenceEntity.machineTranslated], so the model
     * pass still replaces it. Unavailable (no model downloaded, no network to fetch it) just means
     * the row is banked as before.
     */
    private suspend fun withLocalTranslations(rows: List<ClozeSentenceEntity>, lang: String): List<ClozeSentenceEntity> {
        val native = settingsRepo.settingsFor(lang).nativeLanguage
        var available = true
        return rows.map { row ->
            if (!available || !row.translation.isNullOrBlank()) return@map row
            when (val result = translator.translate(Cloze.answer(row.text), lang, native)) {
                is LocalTranslateResult.Success ->
                    result.text.trim().takeIf { it.isNotEmpty() }
                        ?.let { row.copy(translation = it, machineTranslated = true) } ?: row
                else -> {
                    // One failure is the model being absent, not this sentence being hard.
                    available = false
                    row
                }
            }
        }
    }

    /**
     * Back-fills up to [limit] banked sentences that still have no translation, on the device.
     * Returns how many were filled. What gave the existing bank its translations when this arrived,
     * and what catches a row banked while the translator was unavailable.
     */
    suspend fun translateLocally(lang: String, limit: Int): Int = withContext(Dispatchers.IO) {
        val pending = dao.untranslated(lang, limit)
        if (pending.isEmpty()) return@withContext 0
        val native = settingsRepo.settingsFor(lang).nativeLanguage
        var filled = 0
        for (row in pending) {
            val result = translator.translate(Cloze.answer(row.text), lang, native)
            if (result !is LocalTranslateResult.Success) break
            val text = result.text.trim()
            if (text.isEmpty()) continue
            dao.setTranslation(row.id, text, machine = true)
            filled++
        }
        filled
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
     * Up to [size] items for the words that most need practice, with options resolved.
     *
     * What goes in is [ClozeSelector]'s decision, made from the deck: today's misses, words in
     * relearning, leeches, words due. [focusCardIds] names words to put first regardless — "practise
     * these" from the end of a review session or from a word's own screen.
     *
     * An item whose options can't be filled is **dropped, never padded** — a padded option set gives
     * the answer away, which is worse than a shorter sitting. The selection is over-read to absorb that.
     */
    suspend fun session(
        lang: String,
        size: Int,
        seed: Long = System.currentTimeMillis(),
        focusCardIds: List<Long> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): List<ClozeSessionItem> = withContext(Dispatchers.IO) {
        val rows = dao.pool(lang)
        val missed = dao.notesMissedSince(ReviewWindows.startOfDay(now, ZoneId.systemDefault())).toSet()
        val focus = if (focusCardIds.isEmpty()) emptySet() else dao.noteIdsOf(focusCardIds).toSet()
        val chosen = ClozeSelector.select(
            pool = rows.map { it.toEntry(list = null) },
            size = size * OVER_READ,
            now = now,
            missedToday = missed,
            focus = focus,
            random = Random(seed),
        )
        itemsFor(chosen, rows, lang, size, seed)
    }

    /**
     * A sitting on the common-words track: the frequency list's words in rank order, carded or not,
     * skipping the ones already passed. Empty for a language that ships no list.
     */
    suspend fun trackSession(
        lang: String,
        size: Int,
        seed: Long = System.currentTimeMillis(),
        now: Long = System.currentTimeMillis(),
    ): List<ClozeSessionItem> = withContext(Dispatchers.IO) {
        val list = FrequencyLists.forTag(lang) ?: return@withContext emptyList()
        val rows = dao.pool(lang)
        val chosen = ClozeSelector.selectTrack(
            pool = rows.map { it.toEntry(list) },
            size = size * OVER_READ,
            passed = dao.passedLemmas(lang).toSet(),
            now = now,
        )
        itemsFor(chosen, rows, lang, size, seed)
    }

    /**
     * Where the learner stands on the common-words track: how many of the list's words have a banked
     * sentence answered and holding, how many have a sentence at all, and how long the list is.
     * Null when the language has no list.
     */
    suspend fun trackProgress(lang: String): ClozeTrackProgress? = withContext(Dispatchers.IO) {
        val list = FrequencyLists.forTag(lang) ?: return@withContext null
        val passed = dao.passedLemmas(lang).mapNotNullTo(HashSet()) { list.rankOfForm(it) }
        val banked = dao.countsByLemma(lang).mapNotNullTo(HashSet()) { list.rankOfForm(it.lemma) }
        ClozeTrackProgress(passed = passed.size, banked = banked.size, total = list.size)
    }

    private suspend fun itemsFor(
        chosen: List<Long>,
        rows: List<ClozePoolRow>,
        lang: String,
        size: Int,
        seed: Long,
    ): List<ClozeSessionItem> {
        if (chosen.isEmpty()) return emptyList()
        val byId = rows.associate { it.sentence.id to it.sentence }
        val deck = deckPool(lang)
        val out = ArrayList<ClozeSessionItem>(size)
        for (id in chosen) {
            if (out.size >= size) break
            val row = byId[id] ?: continue
            itemFor(row, deck, Random(seed xor row.id))?.let { out += it }
        }
        return out
    }

    private fun ClozePoolRow.toEntry(list: FrequencyList?) = ClozePoolEntry(
        sentenceId = sentence.id,
        lemma = sentence.lemma,
        // The markup adds no whitespace, so the stored text counts the same words as the sentence.
        wordCount = sentence.text.trim().split(WHITESPACE).size,
        pinned = sentence.pinned,
        sentenceDueAt = sentence.dueAt,
        sentenceRepetitions = sentence.repetitions,
        lastShownAt = sentence.lastReviewedAt,
        noteId = noteId,
        cardState = cardState,
        cardDueAt = cardDueAt,
        cardIntervalDays = cardIntervalDays,
        cardLapses = cardLapses,
        cardSuspended = cardSuspended,
        rank = list?.rankOfForm(sentence.lemma),
    )

    /**
     * Where wrong options come from, read once per sitting: the frequency list, and the learner's
     * own vocabulary beside it — every form they have a card for, and their multi-word terms.
     */
    private class DeckPool(
        val list: FrequencyList?,
        val forms: Map<String, String?>,
        val formsByCard: Map<Long, Set<String>>,
        val phrases: List<String>,
    ) {
        /** For a language with no list, where the deck is the only pool there is. */
        val fallback: List<String> get() = if (list == null) forms.keys.toList() else emptyList()
    }

    private suspend fun deckPool(lang: String): DeckPool {
        val cards = flashcardDao.allActive(lang)
        val formsByCard = highlight.formsByCard(cards)
        val forms = HashMap<String, String?>(cards.size * 4)
        val phrases = LinkedHashSet<String>()
        for (card in cards) {
            val term = card.targetTerm.trim()
            if (term.any(Char::isWhitespace)) {
                // Only a term that is a plain run of words: "callejero (papel)" is a word and a hint.
                if (term.all { it.isLetter() || it.isWhitespace() }) phrases += term.lowercase()
                continue
            }
            val pos = card.partOfSpeech?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "unknown" }
            for (form in formsByCard[card.id].orEmpty()) {
                if (form.none(Char::isWhitespace)) forms.putIfAbsent(form, pos)
            }
        }
        return DeckPool(FrequencyLists.forTag(lang), forms, formsByCard, phrases.toList())
    }

    /**
     * Up to [size] banked sentences for a radio track, whole and in random order, ones already met in
     * a sitting first — the same pool listen-and-rebuild draws on, and for the same reason: nothing
     * here is scheduled, so it must not drain the bank's due queue.
     *
     * With [withTranslation] only sentences that have one are taken; a track that says what half its
     * sentences mean and leaves the rest hanging is worse than a shorter track.
     */
    suspend fun radioLines(lang: String, size: Int, withTranslation: Boolean): List<RadioLine> =
        withContext(Dispatchers.IO) {
            dao.rebuildPool(lang, size * RADIO_OVER_READ)
                .asSequence()
                .filter { !withTranslation || !it.translation.isNullOrBlank() }
                .take(size)
                .map { RadioLine(Cloze.answer(it.text), it.translation.takeIf { withTranslation }) }
                .toList()
        }

    private suspend fun itemFor(
        row: ClozeSentenceEntity,
        deck: DeckPool,
        random: Random,
    ): ClozeSessionItem? {
        val prompt = Cloze.question(row.text, activeOrd = null)
        val plain = Cloze.answer(row.text)
        val sentenceTokens = plain.split(WHITESPACE).map(::normalizeWord).filter { it.isNotEmpty() }.toSet()

        val stored = storedOptions(row.distractors)
        val distractors = stored?.takeIf { it.size >= OPTIONS - 1 }
            ?: freshDistractors(row, deck, sentenceTokens, random)?.also {
                dao.setDistractors(row.id, storeOptions(it))
            }
            ?: return null

        val options = (distractors + row.answer).shuffled(random)
        return ClozeSessionItem(
            sentenceId = row.id,
            prompt = prompt,
            sentence = plain,
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
        deck: DeckPool,
        sentenceTokens: Set<String>,
        random: Random,
        alsoExclude: Set<String> = emptySet(),
    ): List<String>? {
        val card = row.cardId?.let { flashcardDao.getById(it) }
        val excluded = HashSet<String>()
        excluded += alsoExclude.map(::normalizeWord)
        // The answer's own word in another inflection is not a wrong option, it is a second right one.
        row.cardId?.let { deck.formsByCard[it] }?.let(excluded::addAll)
        // The card's own synonym field is the app's existing record of "these mean the same thing".
        card?.altForms?.split(SEPARATOR, ",")?.forEach { normalizeWord(it).takeIf(String::isNotEmpty)?.let(excluded::add) }

        val answerGlosses = englishGlosses(row.answer, row.targetLanguage)
        repeat(GLOSS_RETRIES) {
            val picked = ClozeDistractors.pick(
                answer = row.answer,
                answerPos = card?.partOfSpeech,
                list = deck.list,
                sentenceTokens = sentenceTokens,
                excluded = excluded,
                deckFallback = deck.fallback,
                random = random,
                count = OPTIONS - 1,
                deckForms = deck.forms,
                deckPhrases = deck.phrases,
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
            // Untranslated first, then the on-device translator's, which the model can improve on.
            dao.wantingModelTranslation(lang, limit).map { it.id to Cloze.answer(it.text) }
        }

    /**
     * Stores one translation from the model or the learner. Blank input is ignored rather than
     * stored as a gap; either way the result is no longer the on-device translator's.
     */
    suspend fun applyTranslation(sentenceId: Long, translation: String) = withContext(Dispatchers.IO) {
        translation.trim().takeIf { it.isNotEmpty() }?.let { dao.setTranslation(sentenceId, it, machine = false) }
        Unit
    }

    // --- reading passages ----------------------------------------------------

    /**
     * Up to [count] passages from the books the learner has read, each with several of their words
     * taken out — see [ClozePassage]. Recent pages first, since those are the ones still in mind.
     *
     * Nothing here is banked: a passage is made fresh from the page each time, so the same paragraph
     * comes back with different gaps.
     */
    suspend fun readingPassages(
        lang: String,
        count: Int,
        seed: Long = System.currentTimeMillis(),
    ): List<ClozePassageItem> = withContext(Dispatchers.IO) {
        val cards = flashcardDao.allActive(lang)
        val formToCard = HashMap<String, Long>(cards.size * 4)
        for ((cardId, forms) in highlight.formsByCard(cards)) {
            for (form in forms) if (form.none(Char::isWhitespace)) formToCard.putIfAbsent(form, cardId)
        }
        if (formToCard.isEmpty()) return@withContext emptyList()
        // The words that fit nowhere are headwords, not arbitrary inflections: *abriguémonos* in a
        // bank of four is ruled out before the passage is read.
        val extras = cards.map { it.targetTerm.trim() }.filter { it.none(Char::isWhitespace) }.distinct()
        val random = Random(seed)

        val out = ArrayList<ClozePassageItem>(count)
        for (book in bookDao.booksFor(lang)) {
            if (out.size >= count) break
            if (book.posParagraph <= 0) continue
            val paragraphs = bookDao.readParagraphs(book.id, book.posParagraph, PASSAGE_PARAGRAPHS).shuffled(random)
            for (paragraph in paragraphs) {
                if (out.size >= count) break
                val text = paragraph.targetText(book.direction)?.replace(EMPHASIS, "")?.trim() ?: continue
                ClozePassage.build(text, paragraph.nativeText(book.direction), formToCard, extras, random)
                    ?.let { out += it }
            }
        }
        out
    }

    /**
     * Credits the cards a finished passage practised: a quarter of a review each way, the same as a
     * single cloze, and once per card. [results] maps each blank's card to whether it was filled right.
     */
    suspend fun recordPassage(results: Map<Long, Boolean>, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            for ((cardId, correct) in results) {
                flashcardDao.getById(cardId)?.let { flashcards.clozeNudge(it, correct, now) }
            }
        }

    // --- the bank as something to look through -------------------------------

    fun observeBrowse(lang: String, query: String): Flow<List<ClozeSentenceEntity>> =
        dao.observeBrowse(lang, query.trim(), BROWSE_LIMIT)

    suspend fun setPinned(sentenceId: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        dao.setPinned(sentenceId, pinned)
    }

    /**
     * Rewrites a banked sentence by hand. The blank stays where it was — the edit is of the words
     * around it — so [sentence] must still contain the answer exactly once; anything else is refused
     * (false) rather than guessed at. The options are dropped so they are chosen again for the new
     * wording, and a translation the learner typed is theirs, not the translator's.
     */
    suspend fun edit(sentenceId: Long, sentence: String, translation: String?): Boolean =
        withContext(Dispatchers.IO) {
            val row = dao.byId(sentenceId) ?: return@withContext false
            val plain = sentence.trim().replace(WHITESPACE, " ")
            val start = plain.indexOf(row.answer)
            if (start < 0 || plain.indexOf(row.answer, start + 1) >= 0) return@withContext false
            val text = plain.substring(0, start) + "{{c1::" + row.answer + "}}" + plain.substring(start + row.answer.length)
            val newTranslation = translation?.trim()?.ifEmpty { null }
            try {
                dao.update(
                    row.copy(
                        text = text,
                        textKey = ClozeText.keyOf(plain),
                        translation = newTranslation,
                        machineTranslated = row.machineTranslated && newTranslation == row.translation,
                        distractors = if (text == row.text) row.distractors else null,
                    ),
                )
                true
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // The bank already holds this exact sentence for this word.
                false
            }
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
            val deck = deckPool(lang)
            rows.mapNotNull { itemFor(it, deck, Random(it.id)) }
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
            dao.setDistractors(item.sentenceId, storeOptions(item.options.filter { it != row.answer }))
            dao.setFlag(item.sentenceId, suspended = false, reason = null)
            return@withContext
        }

        val plain = Cloze.answer(row.text)
        val tokens = plain.split(WHITESPACE).map(::normalizeWord).filter { it.isNotEmpty() }.toSet()
        val replacement = freshDistractors(
            row = row,
            deck = deckPool(row.targetLanguage),
            sentenceTokens = tokens,
            random = Random(row.id + alsoFit.size),
            alsoExclude = alsoFit.toSet(),
        )
        if (replacement == null) {
            // Nothing else in the pool is safely wrong for this sentence, so the item can't be fixed.
            dao.setFlag(item.sentenceId, suspended = true, reason = FLAG_WRONG)
        } else {
            dao.setDistractors(item.sentenceId, storeOptions(replacement))
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
                sentence = plain,
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
        typed: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val row = dao.byId(item.sentenceId) ?: return@withContext
        val rating = if (typed) {
            ClozeGrading.gradeForTyped(correct)
        } else {
            ClozeGrading.gradeFor(correct, latencyMs, item.wordCount, row.repetitions)
        }
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

        /** Wider than [OVER_READ]: a radio track may be asked to skip every untranslated sentence. */
        private const val RADIO_OVER_READ = 4

        /** Attempts to replace a distractor that turned out to share the answer's meaning. */
        private const val GLOSS_RETRIES = 3

        /** Rows the browse list shows at once; a search narrows to what is wanted long before this. */
        private const val BROWSE_LIMIT = 300

        /** Recently read paragraphs a passage sitting looks through per book. */
        private const val PASSAGE_PARAGRAPHS = 200

        /** Markdown emphasis, which a book paragraph may carry and a passage must not show as text. */
        private val EMPHASIS = Regex("[*_]+")

        private const val SEPARATOR = "; "

        /**
         * Marks stored wrong options as chosen by the current rules. Options are kept once chosen,
         * so when the rules for choosing them change, the ones on file are the old rules' work: a
         * row whose options lack this mark has them chosen again on next sight.
         */
        private const val OPTIONS_MARK = "2|"

        private fun storeOptions(options: List<String>): String = OPTIONS_MARK + options.joinToString(SEPARATOR)

        private fun storedOptions(stored: String?): List<String>? =
            stored?.takeIf { it.startsWith(OPTIONS_MARK) }
                ?.removePrefix(OPTIONS_MARK)
                ?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }
        private val WHITESPACE = Regex("\\s+")
    }
}
