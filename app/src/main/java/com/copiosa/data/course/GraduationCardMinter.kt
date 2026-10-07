package com.copiosa.data.course

import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.local.ConjugationDrillDao
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.domain.conjugation.Cell
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.course.CourseDefinition
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.ReviewDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a graduated course into flashcards, which is how the material stops depending on the course
 * screen at all: from here on FSRS decides when the learner is about to forget, and the cards ride
 * the review flow, the stats, and the Anki export like any other.
 *
 * Two kinds are minted. The authored **usage cards** are cloze sentences — `ReviewScreen` renders
 * `{{c1::…}}` for any card kind, so no review-side change was needed. The **paradigm cards** are
 * chosen from the learner's own weakest cells in the course's scope, so what gets a card is what
 * they actually struggled with rather than a fixed list.
 *
 * Due dates are staggered across [SPREAD_DAYS] so graduating doesn't drop thirty cards into one
 * morning, following the frequency seeder's precedent. Everything carries
 * `autoSource = "course:<id>"`, which is both the audit trail and the exact undo handle.
 */
@Singleton
class GraduationCardMinter @Inject constructor(
    private val flashcards: FlashcardRepository,
    private val drillRepo: ConjugationDrillRepository,
    private val drillDao: ConjugationDrillDao,
) {

    /**
     * Mints [course]'s retention cards. Returns how many were created (0 if already minted).
     *
     * [lang] is expected to be the active profile's language, which it always is in practice — a
     * sitting is created against the active profile and graduation can only follow from playing one.
     * `FlashcardRepository.add` stamps the active profile on every card it writes, so minting for a
     * background profile would file its cards under the foreground one.
     */
    suspend fun mint(
        course: CourseDefinition,
        lang: String,
        now: Long = System.currentTimeMillis(),
    ): Int = withContext(Dispatchers.IO) {
        val source = autoSourceFor(course.id)
        // Graduating twice (a re-run of the final lesson) must not double the deck.
        if (flashcards.countFromSource(source) > 0) return@withContext 0

        val spec = drillRepo.specFor(lang)
        val specs = course.graduationCards
        val paradigm = spec?.let { weakestCells(course, it, lang) }.orEmpty()
        val total = specs.size + paradigm.size
        if (total == 0) return@withContext 0

        var minted = 0
        specs.forEachIndexed { index, card ->
            flashcards.add(
                front = card.clozeText,
                back = card.back,
                contextSentence = null,
                sourceConversationId = null,
                cardKind = CardKind.GRAMMAR,
                autoSource = source,
                dueAt = stagger(now, minted, total),
                enrich = false,
            )
            minted++
        }
        for ((cell, verb) in paradigm) {
            val form = spec?.lexicon?.byInfinitive(verb)
                ?.let { spec.engine.conjugate(it, cell.tense, cell.person) } ?: continue
            flashcards.add(
                // The prompt is the coordinates; the answer is the form. The reverse direction would
                // be ambiguous (many cells share a form), hence TARGET_FIRST rather than BOTH.
                front = "$verb — ${spec.pronouns[cell.person] ?: cell.person.slug}, ${cell.tense.slug}",
                back = form.form,
                contextSentence = null,
                sourceConversationId = null,
                cardKind = CardKind.CONJUGATION,
                reviewDirection = ReviewDirection.TARGET_FIRST,
                autoSource = source,
                dueAt = stagger(now, minted, total),
                enrich = false,
            )
            minted++
        }
        minted
    }

    /**
     * The cells this learner is weakest on inside the course's scope, worst first. Read from the
     * drill's own per-item mastery, so a card is minted for what they kept missing during the
     * course rather than for whatever the author guessed would be hard.
     */
    private suspend fun weakestCells(
        course: CourseDefinition,
        spec: ConjugationSpec,
        lang: String,
    ): List<Pair<Cell, String>> {
        val scope = course.cumulativeScope() ?: return emptyList()
        val stats = drillDao.itemStats(lang)
        return stats
            .mapNotNull { stat ->
                val item = parseItemKey(stat.itemKey) ?: return@mapNotNull null
                val (cell, infinitive) = item
                if (cell.tense !in scope.tenses) return@mapNotNull null
                if (spec.lexicon.byInfinitive(infinitive) == null) return@mapNotNull null
                Triple(cell, infinitive, stat.mastery)
            }
            .sortedBy { it.third }
            .distinctBy { it.first to it.second }
            .take(PARADIGM_CARDS)
            .map { it.first to it.second }
    }

    /**
     * Reads a drill item key back into (cell, infinitive). Generalize keys name an irregularity
     * class rather than a verb, so they yield nothing here — there is no single form to card.
     */
    private fun parseItemKey(key: String): Pair<Cell, String>? {
        val parts = key.split('|')
        if (parts.size != KEY_FIELDS) return null
        if (parts[FIELD_DIRECTION] == DrillDirection.GENERALIZE.slug) return null
        val cell = Cell.fromSlug(parts[FIELD_TARGET]) ?: return null
        return cell to parts[FIELD_SUBJECT]
    }

    private fun stagger(now: Long, index: Int, total: Int): Long {
        if (total <= 1) return now
        val step = SPREAD_DAYS * DAY_MS / total
        return now + index * step
    }

    companion object {
        fun autoSourceFor(courseId: String): String = "course:$courseId"

        /** Cards drawn from the learner's own weakest cells. */
        const val PARADIGM_CARDS = 16

        /** Graduating shouldn't bury a morning's reviews. */
        const val SPREAD_DAYS = 14L
        const val DAY_MS = 24 * 60 * 60 * 1000L

        // The frozen five-field drill item key: version|direction|subject|source|target.
        private const val KEY_FIELDS = 5
        private const val FIELD_DIRECTION = 1
        private const val FIELD_SUBJECT = 2
        private const val FIELD_TARGET = 4
    }
}
