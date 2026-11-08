package com.copiosa.data.repository

import android.util.Log
import com.copiosa.data.local.CardEntity
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.cardDirection
import com.copiosa.data.local.noteKey
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.local.ReviewLogEntity
import com.copiosa.data.remote.JsonShape
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.translate.LocalDictionary
import com.copiosa.di.AppScope
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.model.Role
import com.copiosa.domain.prompt.PromptBuilder
import com.copiosa.domain.prompt.VocabExplanationDto
import com.copiosa.domain.prompt.VocabExplanationParser
import com.copiosa.domain.prompt.VocabRequestItem
import com.copiosa.domain.srs.DueToday
import com.copiosa.domain.srs.NewCardLimit
import com.copiosa.domain.srs.ReviewOrdering
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.domain.srs.SpacedRepetitionScheduler
import com.copiosa.domain.usage.LlmFeature
import com.copiosa.domain.vocab.AltForms
import com.copiosa.domain.vocab.BackNoteSplitter
import com.copiosa.domain.vocab.EnrichmentQueue
import com.copiosa.domain.vocab.WordSource
import com.copiosa.domain.vocab.freq.FrequencyLists
import com.copiosa.domain.vocab.freq.VocabCoverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class FlashcardRepository @Inject constructor(
    private val dao: FlashcardDao,
    private val reviewLogDao: ReviewLogDao,
    private val scheduler: SpacedRepetitionScheduler,
    private val settingsRepo: SettingsRepository,
    private val providers: ProviderRegistry,
    private val prompts: PromptBuilder,
    private val highlightProvider: HighlightTargetProvider,
    private val coverage: VocabCoverage,
    private val dictionary: LocalDictionary,
    /** App-lifetime scope: auto-enrichment must outlive the screen the card was saved from. */
    @AppScope private val scope: CoroutineScope,
) {
    /** Guards the one-time [ensureFormsBackfilled] pass so concurrent callers don't repeat it. */
    private val backfillStarted = AtomicBoolean(false)

    /** Guards the one-time [ensureNotesBackfilled] pass so concurrent callers don't repeat it. */
    private val noteBackfillStarted = AtomicBoolean(false)

    /** Coalesces freshly-saved cards into batched enrichment calls (see [enrichBatch]). */
    private val enrichmentQueue =
        EnrichmentQueue(scope, ENRICH_DEBOUNCE_MS, ENRICH_BATCH, ::enrichBatch)

    /**
     * Sibling cards a review pushed to tomorrow, by the review's log id, so undoing the review can
     * put them back. In memory only: an undo is offered within the session that made the grade.
     */
    private val deferredSiblings = ConcurrentHashMap<Long, List<CardEntity>>()

    /**
     * The active profile's cards; re-emits when the user switches language profiles. One row per
     * *card*: a word studied both ways appears twice, its two rows adjacent and sharing a
     * [FlashcardEntity.noteId].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<FlashcardEntity>> =
        settingsRepo.activeProfileTag.flatMapLatest { lang -> dao.observeAll(lang) }

    fun observe(id: Long): Flow<FlashcardEntity?> = dao.observe(id)

    /** The words saved from [source], newest first, one card each — what a reader lists as "saved from this". */
    fun observeSavedFrom(source: WordSource): Flow<List<FlashcardEntity>> =
        dao.observeSavedFrom(source.conversationId, source.bookId, source.routineRunId)

    /** Every card of the note card [id] belongs to — the card itself and its other direction, if any. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeNoteCards(id: Long): Flow<List<FlashcardEntity>> =
        dao.observe(id).map { it?.noteId }.flatMapLatest { noteId ->
            if (noteId == null) flow { emit(emptyList()) } else dao.observeCardsOfNote(noteId)
        }

    suspend fun getCard(id: Long): FlashcardEntity? = dao.getById(id)

    /**
     * What the active profile has to review *today*, re-evaluated as time passes: every word due
     * for a review, plus as many never-studied words as the daily new-card limit still admits.
     * Counted in words — when both directions of one are due, answering either sends the other to
     * tomorrow, so the pair is one review on offer, not two.
     *
     * The queries bind "now" as a parameter, so without the ticker the cutoff would be frozen at
     * whatever moment the flow was constructed and a screen left open would never surface a card
     * that came due while the user was looking at it. The same tick is what rolls the allowance
     * over at midnight.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDueToday(): Flow<DueToday> =
        combine(
            settingsRepo.activeProfileTag,
            settingsRepo.settings.map { it.newCardsPerDay }.distinctUntilChanged(),
            dueTicker(),
        ) { lang, perDay, _ -> lang to perDay }
            .flatMapLatest { (lang, perDay) ->
                val now = System.currentTimeMillis()
                combine(
                    dao.observeDueSplit(lang, now),
                    reviewLogDao.observeCountFirstReviewedSince(lang, startOfDay(now)),
                ) { split, introduced ->
                    DueToday.of(split.reviewNotes, split.newNotes, NewCardLimit.remaining(perDay, introduced))
                }
            }
            .distinctUntilChanged()

    /** [observeDueToday] as the single number most screens want. */
    fun observeDueCount(): Flow<Int> = observeDueToday().map { it.total }

    /**
     * [observeDueToday] for an arbitrary profile, read once — for background work, which runs for
     * profiles that are not the active one.
     */
    suspend fun dueToday(lang: String, now: Long = System.currentTimeMillis()): DueToday {
        val split = dao.dueSplit(lang, now)
        return DueToday.of(split.reviewNotes, split.newNotes, newAllowance(lang, now))
    }

    /** How many more never-studied cards [lang]'s limit admits today; [Int.MAX_VALUE] when it has none. */
    private suspend fun newAllowance(lang: String, now: Long): Int =
        NewCardLimit.remaining(
            perDay = settingsRepo.settingsFor(lang).newCardsPerDay,
            introducedToday = reviewLogDao.countFirstReviewedSince(lang, startOfDay(now)),
        )

    private fun startOfDay(now: Long): Long = ReviewWindows.startOfDay(now, ZoneId.systemDefault())

    /** Emits immediately, then once a minute — the cadence at which the due cutoff is refreshed. */
    private fun dueTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(DUE_REFRESH_MS)
        }
    }

    /**
     * Saves a new note from a selected phrase, with its cards due immediately. [front] is the
     * target-language side and should already be the headword to store (a conjugated verb is
     * lemmatised to its infinitive by the caller before saving).
     *
     * Returns a *card* id — the term-first card's, or the only card's — which is the handle the
     * rest of the app holds a note by.
     */
    suspend fun add(
        front: String,
        back: String,
        contextSentence: String?,
        sourceConversationId: Long?,
        partOfSpeech: String? = null,
        cardKind: CardKind = CardKind.VOCAB,
        gender: String? = null,
        /** Set instead of [sourceConversationId] when the word came from a book or a routine run. */
        sourceBookId: Long? = null,
        sourceRoutineRunId: Long? = null,
        /**
         * Which way the note is asked. Null takes the kind's own default: a word, phrase or sentence
         * is learned both ways, one card each; a cloze, grammar or conjugation card has only the
         * direction it was written in, and a second card would ask for its prompt as an answer.
         */
        reviewDirection: ReviewDirection? = null,
        /** Which generator made this card, if any — see [FlashcardEntity.autoSource]. */
        autoSource: String? = null,
        /**
         * When the card first comes up, defaulting to immediately. Bulk generators pass a staggered
         * date so a few hundred new words arrive over weeks instead of burying the review queue.
         */
        dueAt: Long? = null,
        /**
         * Whether to queue the card for the LLM enrichment pass. Bulk generators pass false: the
         * queue would turn one tap into hundreds of model calls, and the background
         * [com.copiosa.data.maintenance.tasks.EnrichVocabTask] already works such a backlog off a
         * bounded batch at a time.
         */
        enrich: Boolean = true,
    ): Long {
        val settings = settingsRepo.settings.first()
        val now = System.currentTimeMillis()
        val term = front.trim()
        // Keep the translation field clean: peel any crammed usage note into its own field.
        val (translation, note) = BackNoteSplitter.split(back)
        // The dictionary pack knows most nouns' gender, so a new noun gets its article tinting and a
        // place in the swipe-sort now, offline — rather than after an enrichment call that a bulk
        // generator never queues and that doesn't run at all with background LLM tasks off.
        val cardGender = gender?.trim()?.uppercase()?.ifBlank { null }
            ?: if (cardKind == CardKind.VOCAB) dictionary.nounGender(term, settings.targetLanguage, partOfSpeech) else null
        // Precompute highlight forms now so the saved word lights up immediately (no later lookup).
        val forms = highlightProvider.computeFormsFor(
            term,
            settings.targetLanguage,
            partOfSpeech,
            cardGender,
        )
        val id = dao.insert(
            FlashcardEntity(
                front = term,
                back = translation.trim(),
                usageNote = note,
                contextSentence = contextSentence?.trim()?.ifBlank { null },
                sourceConversationId = sourceConversationId,
                sourceBookId = sourceBookId,
                sourceRoutineRunId = sourceRoutineRunId,
                // Default to "unknown" rather than null so the card carries an explicit POS the user
                // can correct in the editor; the LLM "explain" step overrides it when it finds a real one.
                partOfSpeech = partOfSpeech?.trim()?.lowercase()?.ifBlank { null } ?: POS_UNKNOWN,
                cardKind = cardKind,
                gender = cardGender,
                inflectedForms = HighlightTargetProvider.encode(forms),
                frontIsTarget = true,
                reviewDirection = reviewDirection
                    ?: if (cardKind in LEARNED_BOTH_WAYS) ReviewDirection.BOTH else ReviewDirection.TARGET_FIRST,
                targetLanguage = settings.targetLanguage,
                nativeLanguage = settings.nativeLanguage,
                createdAt = now,
                dueAt = dueAt ?: now,
                state = FlashcardState.NEW,
                autoSource = autoSource,
            ),
        )
        // Nothing local supplies an explanation or CEFR level (nor gender, for a noun the pack lacks),
        // so queue the card for the LLM pass that does.
        if (enrich) enrichmentQueue.enqueue(id)
        return id
    }

    /**
     * Enriches one batch of just-saved cards; false abandons the rest of the queue.
     *
     * Failures are swallowed rather than surfaced: the save itself already succeeded and the user
     * saw it confirmed, so a dead network must not turn into an error afterwards. Those cards stay
     * bare until [EnrichVocabTask][com.copiosa.data.maintenance.tasks.EnrichVocabTask] retries them.
     */
    private suspend fun enrichBatch(ids: List<Long>): Boolean {
        val settings = settingsRepo.settings.first()
        // The same budget gate the background maintenance runner applies before spending a call.
        if (!settings.backgroundLlmTasks ||
            !settingsRepo.hasApiKey(settingsRepo.backgroundTarget(settings).provider)
        ) {
            return false
        }
        // Re-read rather than trusting the queued snapshot: a card may have been deleted since, or
        // already enriched by the user tapping "explain" while the debounce was running.
        val cards = ids.mapNotNull { dao.getById(it) }.filter { it.explanation.isNullOrBlank() }
            .distinctBy { it.noteKey }
        if (cards.isEmpty()) return true
        return try {
            applyExplanations(cards, requestExplanations(cards))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Auto-enrichment failed for ${cards.size} card(s)", e)
            false
        }
    }

    /**
     * Grades [card], persisting its new schedule and appending a log entry; returns the log's id.
     *
     * A note is reviewed at most once a day. Seeing one direction of a word hands you the answer to
     * the other, so any sibling card that was due — or would come due later today — is moved to the
     * start of tomorrow. Frozen siblings are left alone: their schedule is parked, not pending.
     */
    suspend fun review(card: FlashcardEntity, rating: ReviewRating): Long {
        val now = System.currentTimeMillis()
        val result = scheduler.schedule(card, rating, now, retentionFor(card))
        dao.updateSchedule(result.card)
        val logId = reviewLogDao.insert(result.log)
        val tomorrow = ReviewWindows.startOfNextDay(now, ZoneId.systemDefault())
        val deferred = dao.siblingsDueBefore(card.id, tomorrow).filterNot { it.suspended }
        if (deferred.isNotEmpty()) {
            deferred.forEach { dao.setDueAt(it.id, tomorrow) }
            deferredSiblings[logId] = deferred
        }
        return logId
    }

    /**
     * Reverts a just-graded review: restores the pre-review [card]'s schedule, deletes its log entry,
     * and puts back any sibling the review had moved to tomorrow.
     */
    suspend fun undoReview(card: FlashcardEntity, reviewLogId: Long) {
        dao.updateSchedule(card)
        reviewLogDao.delete(reviewLogId)
        deferredSiblings.remove(reviewLogId)?.forEach { dao.setDueAt(it.id, it.dueAt) }
    }

    /** Where each rating would send [card], for previewing the grades on the review buttons. */
    suspend fun previewDueDates(
        card: FlashcardEntity,
        now: Long = System.currentTimeMillis(),
    ): Map<ReviewRating, SpacedRepetitionScheduler.Preview> =
        scheduler.previewAll(card, now, retentionFor(card))

    /**
     * Desired retention is configured per language, and is read from the card's *own* target language
     * rather than the active profile — so a session that outlives a profile switch still grades each
     * card on the curve its language is set to.
     */
    private suspend fun retentionFor(card: FlashcardEntity): Double =
        settingsRepo.settingsFor(card.targetLanguage).desiredRetention

    /** Edits a note's text in place (typo fixes); scheduling and enrichment are untouched. */
    suspend fun setText(id: Long, front: String, back: String) {
        val newFront = front.trim()
        dao.setText(id, newFront, back.trim())
        recomputeForms(id)
    }

    /**
     * Edits the note card [id] belongs to: its text, alternate forms, part of speech, card type,
     * gender, country, usage note, and example sentence — seen from every direction of it. When
     * [usageNote] is null (the user left it blank) a note is peeled out of a still-crammed
     * translation; a note the user typed is kept as-is.
     *
     * [reviewDirection] is which directions to study, applied through [setDirections]; null leaves
     * the note's cards as they are.
     */
    suspend fun updateCard(
        id: Long,
        front: String,
        back: String,
        altForms: String?,
        partOfSpeech: String?,
        cardKind: CardKind,
        reviewDirection: ReviewDirection?,
        contextSentence: String?,
        gender: String?,
        country: String?,
        usageNote: String?,
    ) {
        val newFront = front.trim()
        val typedNote = usageNote?.trim()?.ifBlank { null }
        val (translation, splitNote) = if (typedNote == null) BackNoteSplitter.split(back) else back.trim() to null
        dao.setDetails(
            id,
            newFront,
            translation.trim(),
            AltForms.normalize(altForms),
            partOfSpeech?.trim()?.lowercase()?.ifBlank { null },
            cardKind.name,
            contextSentence?.trim()?.ifBlank { null },
            gender?.trim()?.uppercase()?.ifBlank { null },
            country?.trim()?.ifBlank { null },
            typedNote ?: splitNote,
        )
        recomputeForms(id)
        reviewDirection?.let { setDirections(id, it) }
    }

    /**
     * Chooses which directions of card [id]'s note are studied. `BOTH` leaves a card each way
     * active; a single direction keeps that card and **freezes** the other. Freezing rather than
     * deleting, because a direction is set aside per card and comes back with its schedule and
     * history intact. A direction the note has no card for yet gets a new one, due now.
     */
    suspend fun setDirections(id: Long, directions: ReviewDirection) {
        val noteId = dao.getById(id)?.noteId ?: return
        val cards = dao.cardsOfNote(noteId)
        for (direction in listOf(ReviewDirection.TARGET_FIRST, ReviewDirection.NATIVE_FIRST)) {
            val wanted = directions == ReviewDirection.BOTH || directions == direction
            val existing = cards.firstOrNull { it.cardDirection == direction }
            when {
                existing != null -> if (existing.suspended == wanted) dao.setSuspended(existing.id, !wanted)
                wanted -> addDirection(id, direction)
            }
        }
    }

    /**
     * Starts studying card [id]'s note in [direction], as a new card due now. Returns the new card's
     * id, or null when the note already has a card that way (or [id] no longer exists).
     */
    suspend fun addDirection(id: Long, direction: ReviewDirection): Long? {
        val noteId = dao.getById(id)?.noteId ?: return null
        if (direction == ReviewDirection.BOTH) return null
        if (dao.cardsOfNote(noteId).any { it.cardDirection == direction }) return null
        return dao.insertCard(CardEntity(noteId = noteId, direction = direction, dueAt = System.currentTimeMillis()))
    }

    /**
     * Writes back a card whose content was edited outside the app (the JSON import), refreshing its
     * cached highlight forms as an in-app edit would. [card] is the stored row with only the edited
     * fields changed — the caller merges, so media and enrichment ride through untouched. Only the
     * note is written: every card of it sees the edit, and no schedule moves.
     *
     * [refreshForms] is false when the edit can't have changed what the word inflects to (a new
     * gloss, a flag, a direction). The expansion is a dictionary lookup per card, and a file that
     * re-glosses five hundred cards should not pay for five hundred of them.
     */
    suspend fun replaceContent(card: FlashcardEntity, refreshForms: Boolean = true) {
        dao.updateContent(card)
        if (refreshForms) recomputeForms(card.id)
    }

    /** Every card of one note, the term-first card leading. */
    suspend fun cardsOfNote(noteId: Long): List<FlashcardEntity> = dao.cardsOfNote(noteId)

    /**
     * Recomputes a card's cached highlight forms after an edit. Reads the row back so the forms come
     * from whichever side is the target one (a reverse import's headword lives on its back) and so the
     * part of speech the edit just set is the one the expansion is filtered by.
     */
    private suspend fun recomputeForms(id: Long) {
        val card = dao.getById(id) ?: return
        val forms = highlightProvider.computeFormsFor(
            card.targetTerm,
            card.targetLanguage,
            card.partOfSpeech,
            card.gender,
        )
        dao.setInflectedForms(id, HighlightTargetProvider.encode(forms))
    }

    /**
     * One-time, idempotent backfill of [FlashcardEntity.inflectedForms] for cards saved before the
     * column existed. Computes each card's plural/conjugated forms once (batched per language) and
     * caches them, so in-text highlighting never touches the dictionary on the render path afterward.
     * Safe to call from several screens — only the first caller does the work.
     */
    suspend fun ensureFormsBackfilled() {
        if (!backfillStarted.compareAndSet(false, true)) return
        withContext(Dispatchers.Default) {
            val pending = dao.cardsMissingInflectedForms()
            if (pending.isEmpty()) return@withContext
            val formsByCard = highlightProvider.computeForms(pending)
            for (card in pending) {
                dao.setInflectedForms(card.id, HighlightTargetProvider.encode(formsByCard[card.id].orEmpty()))
            }
        }
    }

    /**
     * One-time, idempotent backfill that peels a crammed usage note out of the translation field for
     * cards saved before [FlashcardEntity.usageNote] existed. Only cards whose `back` carries Markdown
     * markup are scanned (see [FlashcardDao.cardsWithMarkdownBack]). It reconstructs the original blob
     * (`back` + any previously-split note) and re-splits it, so an earlier over-eager split is repaired
     * rather than fossilized; the conservative [BackNoteSplitter] and a write-only-on-change guard keep
     * it safe to re-run and leave clean glosses untouched.
     */
    suspend fun ensureNotesBackfilled() {
        if (!noteBackfillStarted.compareAndSet(false, true)) return
        withContext(Dispatchers.Default) {
            val pending = dao.cardsWithMarkdownBack()
            for (card in pending) {
                val full = card.back + (card.usageNote?.let { "\n\n$it" } ?: "")
                val (translation, note) = BackNoteSplitter.split(full)
                if (translation != card.back || note != card.usageNote) {
                    dao.setBackAndNote(card.id, translation, note)
                }
            }
        }
    }

    /** Sets (or clears) a card's attached image filename. */
    suspend fun setImageFile(id: Long, file: String?) = dao.setImageFile(id, file)

    /** Active lexical cards with no image yet, newest first — the "add images" backlog. */
    suspend fun imagelessCards(): List<FlashcardEntity> =
        dao.imagelessCards(settingsRepo.activeProfileTag.first())

    /**
     * Treats a card as passively reviewed because the word turned up in a text or chat. Only cards
     * due within [ReviewWindows.PASSIVE_WINDOW_MS] move; the due date is pushed back by the lesser of
     * a month and half the card's current interval. FSRS memory state and the review log are left
     * alone (this isn't a graded review), so an Anki export still round-trips cleanly.
     *
     * Only the term-first card earns it. Meeting a word in a text is recognising it; it says nothing
     * about whether the word could have been produced, so the native-first card stays where it was.
     */
    suspend fun passiveReview(card: FlashcardEntity, now: Long = System.currentTimeMillis()) {
        if (card.suspended || card.cardDirection != ReviewDirection.TARGET_FIRST) return
        ReviewWindows.passiveNudgeDueAt(card.dueAt, card.intervalDays, now)
            ?.let { dao.setDueAt(card.id, it) }
    }

    /**
     * Credits a card for a multiple-choice cloze answered on a sentence it teaches — worth a quarter
     * of a review, in whichever direction the answer went (see [ReviewWindows.clozeNudgeDueAt]).
     *
     * Like [passiveReview] this moves only the due date: FSRS memory state and the review log are
     * untouched, so the Anki export round-trips and the review-accuracy figures still describe
     * actual reviews. The magnitude is bounded by the caller, which nudges each card at most once
     * per session — without that, three cloze sentences for one lemma would move it three times in
     * a sitting.
     */
    suspend fun clozeNudge(
        card: FlashcardEntity,
        correct: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (card.suspended) return
        ReviewWindows.clozeNudgeDueAt(card.dueAt, card.intervalDays, now, correct)
            ?.let { dao.setDueAt(card.id, it) }
    }

    /**
     * Freezes/unfreezes one card (Anki "suspend") — a single direction of its word. A frozen card
     * drops out of review; the word's other direction carries on.
     */
    suspend fun setSuspended(id: Long, suspended: Boolean) = dao.setSuspended(id, suspended)

    /** Every graded review of card [id], newest first. */
    fun observeReviews(id: Long): Flow<List<ReviewLogEntity>> = reviewLogDao.observeForCard(id)

    /**
     * Starts one direction of a word over, as a new card due now (Anki's "forget"). The lapse and
     * repetition counts go back to zero with the schedule — a card started over that still counted
     * as a leech would have been started over in name only. The review log is kept: what happened
     * happened, and it is the log an export and a future parameter fit read.
     */
    suspend fun resetSchedule(id: Long, now: Long = System.currentTimeMillis()) {
        val card = dao.getById(id) ?: return
        dao.updateSchedule(
            card.copy(
                state = FlashcardState.NEW,
                stability = null,
                difficulty = null,
                fsrsStep = null,
                intervalDays = 0.0,
                repetitions = 0,
                lapses = 0,
                lastReviewedAt = null,
                dueAt = now,
            ),
        )
    }

    /**
     * Moves one direction's next review to [dueAt] and changes nothing else. FSRS grades the next
     * answer on the time actually elapsed since the last review, so the interval needs no fixing up
     * to match.
     */
    suspend fun setDueDate(id: Long, dueAt: Long) = dao.setDueAt(id, dueAt)

    /** Freezes/unfreezes every direction of the note card [id] belongs to: the word set aside whole. */
    suspend fun setNoteSuspended(id: Long, suspended: Boolean) = dao.setNoteSuspended(id, suspended)

    /** Sets the "marked" flag (Anki "marked") on card [id]'s note; used to filter the vocabulary list. */
    suspend fun setMarked(id: Long, marked: Boolean) = dao.setMarked(id, marked)

    /** Deletes the note card [id] belongs to, with every card of it and their review history. */
    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Deletes every note the [source] generator produced for the active profile, returning how many
     * went. Notes the user saved themselves are matched by nothing and are left alone.
     *
     * Destructive and unguarded: a generated card the user has since studied is deleted along with
     * the rest, taking its review history with it. Callers confirm first.
     */
    suspend fun removeAutoAdded(source: String): Int =
        dao.deleteByAutoSource(settingsRepo.activeProfileTag.first(), source)

    /** How many cards [source] has already produced for the active profile. */
    suspend fun countFromSource(source: String): Int =
        dao.countByAutoSource(settingsRepo.activeProfileTag.first(), source)

    /**
     * Builds a fixed-size study session of up to [limit] cards from the active (non-frozen) deck.
     *
     * Reads the active deck and each card's frequency rank, then hands both to [ReviewOrdering],
     * which owns the ordering rules. A [freqTop] above zero asks for the frequent-word drill instead
     * of an ordinary session.
     */
    suspend fun buildReviewSession(
        limit: Int,
        freqTop: Int = 0,
        random: Random = Random.Default,
    ): List<FlashcardEntity> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()
        val lang = settingsRepo.activeProfileTag.first()
        // A note reviewed today is done for today, whichever direction it was — and study-ahead
        // would otherwise reach straight for the sibling that review just moved to tomorrow.
        val active = ReviewOrdering.withoutSiblingsOfReviewed(
            dao.allActive(lang),
            ReviewWindows.startOfDay(now, ZoneId.systemDefault()),
        )
        // Empty for a language with no bundled list, which leaves the ordering unweighted.
        val ranks = FrequencyLists.forTag(lang)?.let { coverage.ranksByCard(active, it) }.orEmpty()
        return if (freqTop > 0) {
            // The drill is asked for by name and studies ahead on purpose, so the daily new-card
            // limit — which governs what ordinary reviews take on — does not apply to it.
            ReviewOrdering.buildFrequencySession(active, ranks, now, limit, freqTop)
        } else {
            ReviewOrdering.buildSession(active, ranks, now, limit, random, newAllowance(lang, now))
        }
    }

    /** Generates the colloquial explanation (and POS/CEFR/example) for a single card via the LLM. */
    suspend fun explain(card: FlashcardEntity) {
        // Asked for by name and read straight away, so it gets the main model; the batched passes
        // below are the background slot's work.
        applyExplanations(listOf(card), requestExplanations(listOf(card), background = false))
    }

    /**
     * Enriches every card still missing an explanation, ~[batchSize] per LLM call, dispersing each
     * batch's results before the next. [onProgress] reports (done, total). A failed batch throws
     * (surfacing the error) but leaves already-enriched cards saved, so the action is retryable.
     *
     * [maxBatches] caps how many calls one invocation may spend — unbounded for the user-initiated
     * action (they asked for it and watch the progress), but bounded for the background backstop so
     * a large backlog is worked off over several days instead of in one burst.
     */
    suspend fun fillExplanations(
        batchSize: Int = 10,
        maxBatches: Int = Int.MAX_VALUE,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        // One card per note: the explanation is the note's, and asking for it twice pays twice.
        val pending = observeAll().first().filter { it.explanation.isNullOrBlank() }.distinctBy { it.noteKey }
        if (pending.isEmpty()) {
            onProgress(0, 0)
            return
        }
        var done = 0
        onProgress(0, pending.size)
        pending.chunked(batchSize).take(maxBatches).forEach { chunk ->
            applyExplanations(chunk, requestExplanations(chunk))
            done += chunk.size
            onProgress(done, pending.size)
        }
    }

    /**
     * Builds and sends one enrichment request for [cards], returning the parsed results.
     * [background] sends it to the profile's background model — and that model's provider — rather
     * than its main one.
     */
    private suspend fun requestExplanations(
        cards: List<FlashcardEntity>,
        background: Boolean = true,
    ): List<VocabExplanationDto> {
        val settings = settingsRepo.settings.first()
        val target = if (background) settingsRepo.backgroundTarget(settings) else settings.main
        val items = cards.map {
            VocabRequestItem(
                id = it.id,
                term = it.front,
                context = it.contextSentence?.ifBlank { null },
                pos = it.partOfSpeech?.takeIf { pos -> pos != POS_UNKNOWN },
            )
        }
        val system = prompts.vocabExplanationSystemPrompt(
            country = settings.defaultCountry,
            targetLang = settings.targetLanguage,
            nativeLang = settings.nativeLanguage,
            level = settings.defaultLevel,
        )
        val response = providers.forId(target.provider).chat(
            LlmRequest(
                systemPrompt = system,
                messages = listOf(LlmMessage(Role.USER, VocabExplanationParser.encodeRequest(items))),
                model = target.model,
                feature = LlmFeature.VOCAB,
                maxTokens = (cards.size * 280).coerceIn(512, 4096),
                json = JsonShape.ARRAY,
            ),
        )
        return VocabExplanationParser.parse(response.text)
    }

    /**
     * Writes [results] back onto [cards]. Matches by echoed id; falls back to positional order when
     * the model didn't echo ids (and to the sole result for a single-card request).
     */
    private suspend fun applyExplanations(cards: List<FlashcardEntity>, results: List<VocabExplanationDto>) {
        if (results.isEmpty()) return
        val byId = results.mapNotNull { dto -> dto.id?.let { it to dto } }.toMap()
        when {
            cards.size == 1 -> update(cards[0], byId[cards[0].id] ?: results.first())
            cards.any { byId.containsKey(it.id) } ->
                cards.forEach { card -> byId[card.id]?.let { update(card, it) } }
            results.size == cards.size ->
                cards.zip(results).forEach { (card, dto) -> update(card, dto) }
        }
    }

    private suspend fun update(card: FlashcardEntity, dto: VocabExplanationDto) {
        dao.updateContent(
            card.copy(
                back = improvedBack(card, dto) ?: card.back,
                explanation = dto.explanation.trim().ifBlank { card.explanation },
                partOfSpeech = dto.pos?.trim()?.lowercase()?.ifBlank { null } ?: card.partOfSpeech,
                cefrLevel = dto.cefr?.trim()?.uppercase()?.ifBlank { null } ?: card.cefrLevel,
                // Keep any value set by the cleaned deck import; only fill from the LLM when absent.
                gender = card.gender ?: dto.gender?.trim()?.uppercase()?.ifBlank { null },
                // Keep a captured original sentence; otherwise fall back to the generated example.
                contextSentence = card.contextSentence?.ifBlank { null } ?: dto.example?.trim()?.ifBlank { null },
            ),
        )
    }

    /**
     * The model's gloss, when it should replace the card's back — otherwise null, and the back stands.
     *
     * A back the learner chose (or an Anki deck shipped) is theirs and is never rewritten. The one
     * exception is a card seeded from the frequency list: its back is a bare on-device translation of
     * a bare lemma, which is how *tener* and *haber* both end up saying nothing but "to have". The
     * model sees the part of speech and is asked for a distinguishing gloss, so this is the moment
     * that gets fixed.
     *
     * Gated on the card never having been enriched, which is also the queue's own filter — so a card
     * repaired once is not rewritten on some later pass, and an edit made after enrichment sticks.
     */
    private fun improvedBack(card: FlashcardEntity, dto: VocabExplanationDto): String? {
        if (card.autoSource != AUTO_SOURCE_FREQ || !card.explanation.isNullOrBlank()) return null
        return dto.translation?.trim()?.ifBlank { null }
    }

    companion object {
        /** Default part of speech for a freshly-saved card whose POS wasn't determined. */
        const val POS_UNKNOWN = "unknown"

        /** The [FlashcardEntity.autoSource] stamped on cards generated from the frequency list. */
        const val AUTO_SOURCE_FREQ = "freq"

        /** The kinds whose notes get a card in each direction unless the caller says otherwise. */
        val LEARNED_BOTH_WAYS = setOf(CardKind.VOCAB, CardKind.PHRASE, CardKind.SENTENCE)

        /** How often [observeDueToday] re-evaluates its "now" cutoff. */
        private const val DUE_REFRESH_MS = 60_000L


        /** Quiet period before a burst of saves is enriched, so consecutive taps share one call. */
        private const val ENRICH_DEBOUNCE_MS = 3_000L

        /** Cards per auto-enrichment request, matching [fillExplanations]'s default batch. */
        private const val ENRICH_BATCH = 10

        private const val TAG = "FlashcardRepository"
    }
}
