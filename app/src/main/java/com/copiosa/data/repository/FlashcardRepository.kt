package com.copiosa.data.repository

import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.highlight.HighlightTargetProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
) {
    /** Guards the one-time [ensureFormsBackfilled] pass so concurrent callers don't repeat it. */
    private val backfillStarted = AtomicBoolean(false)

    /** The active profile's cards; re-emits when the user switches language profiles. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<FlashcardEntity>> =
        settingsRepo.activeProfileTag.flatMapLatest { lang -> dao.observeAll(lang) }

    fun observe(id: Long): Flow<FlashcardEntity?> = dao.observe(id)

    suspend fun getCard(id: Long): FlashcardEntity? = dao.getById(id)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDue(): Flow<List<FlashcardEntity>> =
        settingsRepo.activeProfileTag.flatMapLatest { lang ->
            dao.observeDue(System.currentTimeMillis(), lang)
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
        // Precompute highlight forms now so the saved word lights up immediately (no later lookup).
        val forms = highlightProvider.computeFormsFor(term, settings.targetLanguage)
        return dao.insert(
            FlashcardEntity(
                front = term,
                back = back.trim(),
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
    }

    suspend fun review(card: FlashcardEntity, rating: ReviewRating) {
        val result = scheduler.schedule(card, rating, System.currentTimeMillis())
        dao.update(result.card)
        reviewLogDao.insert(result.log)
    }

    /** Projected next-due epoch-ms per rating, for previewing each grade's consequence on the review buttons. */
    fun previewDueDates(card: FlashcardEntity, now: Long = System.currentTimeMillis()): Map<ReviewRating, Long> =
        scheduler.previewAll(card, now)

    /** Edits a card's text in place (typo fixes); scheduling and enrichment are untouched. */
    suspend fun setText(id: Long, front: String, back: String) {
        val newFront = front.trim()
        dao.setText(id, newFront, back.trim())
        recomputeFormsIfFrontChanged(id, newFront)
    }

    /** Edits a card's text, part of speech, card type, review direction, and example sentence. */
    suspend fun updateCard(
        id: Long,
        front: String,
        back: String,
        partOfSpeech: String?,
        cardKind: CardKind,
        reviewDirection: ReviewDirection,
        contextSentence: String?,
    ) {
        val newFront = front.trim()
        dao.setDetails(
            id,
            newFront,
            back.trim(),
            partOfSpeech?.trim()?.lowercase()?.ifBlank { null },
            cardKind.name,
            reviewDirection.name,
            contextSentence?.trim()?.ifBlank { null },
        )
        recomputeFormsIfFrontChanged(id, newFront)
    }

    /** Recomputes the cached highlight forms when a card's headword changed (no-op otherwise). */
    private suspend fun recomputeFormsIfFrontChanged(id: Long, newFront: String) {
        val card = dao.getById(id) ?: return
        val forms = highlightProvider.computeFormsFor(newFront, card.targetLanguage)
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
     */
    suspend fun buildReviewSession(limit: Int): List<FlashcardEntity> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()
        val (due, upcoming) = dao.allActive(settingsRepo.activeProfileTag.first()).partition { it.dueAt <= now }
        // New cards (app-created + imported) shuffled together so neither source dominates; due reviews
        // kept in due-date order (most overdue first).
        val newDue = due.filter { it.state == FlashcardState.NEW }.shuffled()
        val reviewDue = due.filter { it.state != FlashcardState.NEW }.sortedBy { it.dueAt }
        val session = interleave(reviewDue, newDue).toMutableList()
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
     */
    suspend fun fillExplanations(batchSize: Int = 10, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val pending = observeAll().first().filter { it.explanation.isNullOrBlank() }
        if (pending.isEmpty()) {
            onProgress(0, 0)
            return
        }
        var done = 0
        onProgress(0, pending.size)
        pending.chunked(batchSize).forEach { chunk ->
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
                region = card.region ?: dto.region?.trim()?.lowercase()?.ifBlank { null },
                // Keep a captured original sentence; otherwise fall back to the generated example.
                contextSentence = card.contextSentence?.ifBlank { null } ?: dto.example?.trim()?.ifBlank { null },
            ),
        )
    }

    companion object {
        /** Default part of speech for a freshly-saved card whose POS wasn't determined. */
        const val POS_UNKNOWN = "unknown"
    }
}
