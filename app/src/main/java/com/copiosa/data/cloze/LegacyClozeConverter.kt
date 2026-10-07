package com.copiosa.data.cloze

import androidx.room.withTransaction
import com.copiosa.data.local.AppDatabase
import com.copiosa.data.local.ClozeDao
import com.copiosa.data.local.ClozeReviewEntity
import com.copiosa.data.local.ClozeSentenceEntity
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.domain.cloze.ClozeConversionPlan
import com.copiosa.domain.cloze.ClozeText
import com.copiosa.domain.cloze.LegacyClozePlan
import com.copiosa.domain.cloze.PlannedCloze
import com.copiosa.domain.model.Cloze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves hand-made cloze flashcards into the cloze bank and deletes the originals.
 *
 * The deletion is why this is a two-step operation the learner confirms rather than a migration that
 * fires on upgrade: [preview] reports exactly what would happen, [convert] does it.
 *
 * Nothing is lost. The card's FSRS schedule moves onto the new row, and its `reviews` rows are
 * copied into `cloze_reviews` **before** the card is deleted — without that copy the `ON DELETE
 * CASCADE` on `reviews` would take the entire per-review history with it, and the learner's all-time
 * review count would silently drop.
 */
@Singleton
class LegacyClozeConverter @Inject constructor(
    private val db: AppDatabase,
    private val dao: ClozeDao,
    private val flashcardDao: FlashcardDao,
    private val reviewLogDao: ReviewLogDao,
    private val cloze: ClozeRepository,
) {

    /** What converting [lang]'s hand-made cloze cards would do. Reads only. */
    suspend fun preview(lang: String): ClozeConversionPlan = withContext(Dispatchers.IO) {
        val cards = flashcardDao.legacyClozeCards(lang)
        val ctx = cloze.buildContext(lang)
        LegacyClozePlan.plan(
            cards = cards,
            formToCard = ctx.formToCard,
            skippedMedia = flashcardDao.countLegacyClozeWithMedia(lang),
        )
    }

    /**
     * Applies [plan]: banks every row, carries the schedule and history across, then deletes the
     * cards. Returns how many sentences were created.
     *
     * One transaction per card rather than one for the whole run: a conversion of several hundred
     * cards that failed halfway would otherwise roll back work the learner had already been told
     * about, and per-card atomicity is all that is actually needed — a card is either fully moved or
     * still there.
     *
     * Re-running is safe: the unique index rejects rows already banked, and cards already deleted
     * are no longer returned by the query that built the plan.
     */
    suspend fun convert(
        plan: ClozeConversionPlan,
        lang: String,
        now: Long = System.currentTimeMillis(),
    ): Int = withContext(Dispatchers.IO) {
        var created = 0
        val rowsByCard = plan.rows.groupBy { it.card.id }

        for (card in plan.cards) {
            val rows = rowsByCard[card.id].orEmpty()
            if (rows.isEmpty()) continue
            db.withTransaction {
                val history = reviewLogDao.forCard(card.id)
                rows.forEachIndexed { index, planned ->
                    val id = dao.insert(planned.toRow(lang, now, index))
                    // -1 means the bank already held this sentence; the card should still go.
                    if (id == -1L) return@forEachIndexed
                    created++
                    if (planned.carriesHistory && history.isNotEmpty()) {
                        dao.insertReviews(
                            history.map { log ->
                                ClozeReviewEntity(
                                    clozeId = id,
                                    reviewedAt = log.reviewedAt,
                                    rating = log.rating,
                                    lastIntervalDays = log.lastIntervalDays,
                                    intervalDays = log.intervalDays,
                                    stabilityAfter = log.stabilityAfter,
                                    difficultyAfter = log.difficultyAfter,
                                    state = log.state,
                                    // The flashcard log records a duration, not the answer latency a
                                    // cloze grade is chosen from; leaving it null says "unknown".
                                    latencyMs = null,
                                )
                            },
                        )
                    }
                }
                flashcardDao.delete(card.id)
            }
        }
        created
    }

    /**
     * [index] staggers the due dates of a multi-deletion split by a day each, so a two-gap sentence
     * doesn't put both of its clozes in the same sitting — following the graduation minter's
     * precedent for not dropping a batch into one morning.
     */
    private fun PlannedCloze.toRow(lang: String, now: Long, index: Int) = ClozeSentenceEntity(
        targetLanguage = lang,
        text = text,
        answer = answer,
        lemma = lemma,
        textKey = ClozeText.keyOf(Cloze.answer(text)),
        cardId = cardId,
        translation = translation,
        source = "legacy:${card.id}",
        // The schedule the learner has already built on this material, kept exactly.
        dueAt = if (carriesHistory) card.dueAt else card.dueAt + index * DAY_MS,
        intervalDays = if (carriesHistory) card.intervalDays else 0.0,
        stability = if (carriesHistory) card.stability else null,
        difficulty = if (carriesHistory) card.difficulty else null,
        fsrsStep = if (carriesHistory) card.fsrsStep else null,
        repetitions = if (carriesHistory) card.repetitions else 0,
        lapses = if (carriesHistory) card.lapses else 0,
        lastReviewedAt = if (carriesHistory) card.lastReviewedAt else null,
        state = if (carriesHistory) card.state else com.copiosa.domain.model.FlashcardState.NEW,
        // Carried so a later re-import of the same .apkg recognises this card as already converted
        // instead of recreating the flashcard this deleted.
        ankiNoteGuid = card.ankiNoteGuid,
        ankiOrd = card.ankiOrd,
        createdAt = now,
    )

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
