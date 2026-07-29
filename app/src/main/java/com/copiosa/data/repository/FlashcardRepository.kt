package com.copiosa.data.repository

import android.util.Log
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.settings.SettingsRepository
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
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.domain.srs.SpacedRepetitionScheduler
import com.copiosa.domain.vocab.AltForms
import com.copiosa.domain.vocab.BackNoteSplitter
import com.copiosa.domain.vocab.EnrichmentQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FlashcardRepository @Inject constructor(
    private val dao: FlashcardDao,
    private val reviewLogDao: ReviewLogDao,
    private val scheduler: SpacedRepetitionScheduler,
    private val settingsRepo: SettingsRepository,
    private val providers: ProviderRegistry,
    private val prompts: PromptBuilder,
    private val highlightProvider: HighlightTargetProvider,
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

    /** The active profile's cards; re-emits when the user switches language profiles. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<FlashcardEntity>> =
        settingsRepo.activeProfileTag.flatMapLatest { lang -> dao.observeAll(lang) }

    fun observe(id: Long): Flow<FlashcardEntity?> = dao.observe(id)

    suspend fun getCard(id: Long): FlashcardEntity? = dao.getById(id)

    /**
     * The active profile's due cards, re-evaluated as time passes.
     *
     * `dao.observeDue` binds "now" as a query parameter, so without the ticker the cutoff would be
     * frozen at whatever moment the flow was constructed and a screen left open would never surface
     * a card that came due while the user was looking at it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDue(): Flow<List<FlashcardEntity>> =
        combine(settingsRepo.activeProfileTag, dueTicker()) { lang, _ -> lang }
            .flatMapLatest { lang -> dao.observeDue(System.currentTimeMillis(), lang) }

    /** The active profile's due-card count, on the same minute cadence as [observeDue]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDueCount(): Flow<Int> =
        combine(settingsRepo.activeProfileTag, dueTicker()) { lang, _ -> lang }
            .flatMapLatest { lang -> dao.observeCountDue(lang, System.currentTimeMillis()) }

    /** Emits immediately, then once a minute — the cadence at which the due cutoff is refreshed. */
    private fun dueTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(DUE_REFRESH_MS)
        }
    }

    /**
     * Saves a new card from a selected phrase. New cards are due immediately. App-created cards are
     * always target-front; [front] should already be the headword to store (a conjugated verb is
     * lemmatised to its infinitive by the caller before saving).
     */
    suspend fun add(
        front: String,
        back: String,
        contextSentence: String?,
        sourceConversationId: Long?,
        partOfSpeech: String? = null,
        cardKind: CardKind = CardKind.VOCAB,
        gender: String? = null,
        reviewDirection: ReviewDirection = ReviewDirection.BOTH,
    ): Long {
        val settings = settingsRepo.settings.first()
        val now = System.currentTimeMillis()
        val term = front.trim()
        // Keep the translation field clean: peel any crammed usage note into its own field.
        val (translation, note) = BackNoteSplitter.split(back)
        // Precompute highlight forms now so the saved word lights up immediately (no later lookup).
        val forms = highlightProvider.computeFormsFor(
            term,
            settings.targetLanguage,
            partOfSpeech,
            gender,
        )
        val id = dao.insert(
            FlashcardEntity(
                front = term,
                back = translation.trim(),
                usageNote = note,
                contextSentence = contextSentence?.trim()?.ifBlank { null },
                sourceConversationId = sourceConversationId,
                // Default to "unknown" rather than null so the card carries an explicit POS the user
                // can correct in the editor; the LLM "explain" step overrides it when it finds a real one.
                partOfSpeech = partOfSpeech?.trim()?.lowercase()?.ifBlank { null } ?: POS_UNKNOWN,
                cardKind = cardKind,
                gender = gender?.trim()?.uppercase()?.ifBlank { null },
                inflectedForms = HighlightTargetProvider.encode(forms),
                frontIsTarget = true,
                reviewDirection = reviewDirection,
                targetLanguage = settings.targetLanguage,
                nativeLanguage = settings.nativeLanguage,
                createdAt = now,
                dueAt = now,
                state = FlashcardState.NEW,
            ),
        )
        // Nothing local supplies gender/explanation/CEFR, so queue the card for the LLM pass that
        // does — otherwise an app-built deck stays genderless (no article tinting, empty swipe-sort).
        enrichmentQueue.enqueue(id)
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
        if (!settings.backgroundLlmTasks || !settingsRepo.hasApiKey(settings.provider)) return false
        // Re-read rather than trusting the queued snapshot: a card may have been deleted since, or
        // already enriched by the user tapping "explain" while the debounce was running.
        val cards = ids.mapNotNull { dao.getById(it) }.filter { it.explanation.isNullOrBlank() }
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

    /** Grades [card], persisting its new schedule and appending a log entry; returns the log's id. */
    suspend fun review(card: FlashcardEntity, rating: ReviewRating): Long {
        val result = scheduler.schedule(card, rating, System.currentTimeMillis(), retentionFor(card))
        dao.update(result.card)
        return reviewLogDao.insert(result.log)
    }

    /** Reverts a just-graded review: restores the pre-review [card] row and deletes its log entry. */
    suspend fun undoReview(card: FlashcardEntity, reviewLogId: Long) {
        dao.update(card)
        reviewLogDao.delete(reviewLogId)
    }

    /** Projected next-due epoch-ms per rating, for previewing each grade's consequence on the review buttons. */
    suspend fun previewDueDates(
        card: FlashcardEntity,
        now: Long = System.currentTimeMillis(),
    ): Map<ReviewRating, Long> = scheduler.previewAll(card, now, retentionFor(card))

    /**
     * Desired retention is configured per language, and is read from the card's *own* target language
     * rather than the active profile — so a session that outlives a profile switch still grades each
     * card on the curve its language is set to.
     */
    private suspend fun retentionFor(card: FlashcardEntity): Double =
        settingsRepo.settingsFor(card.targetLanguage).desiredRetention

    /** Edits a card's text in place (typo fixes); scheduling and enrichment are untouched. */
    suspend fun setText(id: Long, front: String, back: String) {
        val newFront = front.trim()
        dao.setText(id, newFront, back.trim())
        recomputeForms(id)
    }

    /**
     * Edits a card's text, alternate forms, part of speech, card type, review direction, gender, country,
     * usage note, and example sentence. When [usageNote] is null (the user left it blank) a note is peeled
     * out of a still-crammed translation; a note the user typed is kept as-is.
     */
    suspend fun updateCard(
        id: Long,
        front: String,
        back: String,
        altForms: String?,
        partOfSpeech: String?,
        cardKind: CardKind,
        reviewDirection: ReviewDirection,
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
            reviewDirection.name,
            contextSentence?.trim()?.ifBlank { null },
            gender?.trim()?.uppercase()?.ifBlank { null },
            country?.trim()?.ifBlank { null },
            typedNote ?: splitNote,
        )
        recomputeForms(id)
    }

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
     */
    suspend fun passiveReview(card: FlashcardEntity, now: Long = System.currentTimeMillis()) {
        if (card.suspended) return
        if (card.dueAt > now + ReviewWindows.PASSIVE_WINDOW_MS) return
        val pushDays = minOf(ReviewWindows.PASSIVE_MAX_PUSH_DAYS, card.intervalDays / 2.0)
        if (pushDays <= 0.0) return
        dao.setDueAt(card.id, card.dueAt + (pushDays * ReviewWindows.DAY_MS).toLong())
    }

    /** Freezes/unfreezes a card (Anki "suspend"): drops it from review and the conversation vocab pool. */
    suspend fun setSuspended(id: Long, suspended: Boolean) = dao.setSuspended(id, suspended)

    /** Sets a card's "marked" flag (Anki "marked"); used to filter the vocabulary list. */
    suspend fun setMarked(id: Long, marked: Boolean) = dao.setMarked(id, marked)

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Builds a fixed-size study session of up to [limit] cards from the active (non-frozen) deck.
     *
     * Due cards come first, with newly-saved words interleaved among due reviews so app-created cards
     * surface alongside imported Anki ones instead of sitting behind thousands of them. If fewer than
     * [limit] cards are due, the session studies ahead by appending the soonest-upcoming cards.
     *
     * An imported note's forward and reverse cards are separate rows sharing one [FlashcardEntity.ankiNoteGuid].
     * Meeting both in one sitting isn't a review — the first one hands you the answer to the second — so a
     * twin is held back and only spent once the rest of the due backlog has been used up.
     */
    suspend fun buildReviewSession(limit: Int): List<FlashcardEntity> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()
        val (due, upcoming) = dao.allActive(settingsRepo.activeProfileTag.first()).partition { it.dueAt <= now }
        // New cards (app-created + imported) shuffled together so neither source dominates; due reviews
        // kept in due-date order (most overdue first).
        val newDue = due.filter { it.state == FlashcardState.NEW }.shuffled()
        val reviewDue = due.filter { it.state != FlashcardState.NEW }.sortedBy { it.dueAt }
        // partition runs in order, so add() keeps the first card of each note and sheds the later twins.
        // App-created cards have no guid and are never treated as siblings.
        val seenNotes = HashSet<String>()
        val (once, twins) = interleave(reviewDue, newDue)
            .partition { card -> card.ankiNoteGuid?.let(seenNotes::add) ?: true }
        val session = once.toMutableList()
        if (session.size < limit) session += twins
        if (session.size < limit) {
            session += upcoming.sortedBy { it.dueAt }.take(limit - session.size)
        }
        return session.take(limit)
    }

    /** Round-robins two lists proportionally to their sizes, so the shorter one is spread throughout. */
    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ArrayList<T>(a.size + b.size)
        var ia = 0
        var ib = 0
        // Emit from whichever list is proportionally "behind", measured by fractional progress.
        while (ia < a.size || ib < b.size) {
            val takeA = ib >= b.size ||
                (ia < a.size && (ia + 1).toDouble() / a.size <= (ib + 1).toDouble() / b.size)
            if (takeA) out.add(a[ia++]) else out.add(b[ib++])
        }
        return out
    }

    /** Generates the colloquial explanation (and POS/CEFR/example) for a single card via the LLM. */
    suspend fun explain(card: FlashcardEntity) {
        applyExplanations(listOf(card), requestExplanations(listOf(card)))
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
        val pending = observeAll().first().filter { it.explanation.isNullOrBlank() }
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

    /** Builds and sends one enrichment request for [cards], returning the parsed results. */
    private suspend fun requestExplanations(cards: List<FlashcardEntity>): List<VocabExplanationDto> {
        val settings = settingsRepo.settings.first()
        val items = cards.map {
            VocabRequestItem(id = it.id, term = it.front, context = it.contextSentence?.ifBlank { null })
        }
        val system = prompts.vocabExplanationSystemPrompt(
            country = settings.defaultCountry,
            targetLang = settings.targetLanguage,
            nativeLang = settings.nativeLanguage,
            level = settings.defaultLevel,
        )
        val response = providers.active().chat(
            LlmRequest(
                systemPrompt = system,
                messages = listOf(LlmMessage(Role.USER, VocabExplanationParser.encodeRequest(items))),
                model = settings.activeModel,
                maxTokens = (cards.size * 280).coerceIn(512, 4096),
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
        dao.update(
            card.copy(
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

    companion object {
        /** Default part of speech for a freshly-saved card whose POS wasn't determined. */
        const val POS_UNKNOWN = "unknown"

        /** How often [observeDue] re-evaluates its "now" cutoff. */
        private const val DUE_REFRESH_MS = 60_000L

        /** Quiet period before a burst of saves is enriched, so consecutive taps share one call. */
        private const val ENRICH_DEBOUNCE_MS = 3_000L

        /** Cards per auto-enrichment request, matching [fillExplanations]'s default batch. */
        private const val ENRICH_BATCH = 10

        private const val TAG = "FlashcardRepository"
    }
}
