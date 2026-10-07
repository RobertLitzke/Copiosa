package com.copiosa.domain.routine.activities

import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityDescriptor
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.FrequentWordDrillContent
import com.copiosa.domain.routine.cardLimitFor
import com.copiosa.domain.vocab.freq.FrequencyBucket
import com.copiosa.domain.vocab.freq.FrequencyLists

/**
 * Real FSRS review, narrowed to the words the bundled frequency list ranks highest — the deck's most
 * load-bearing vocabulary, drilled as a routine step rather than as a menu action.
 *
 * Only the *pool* is narrowed. The session is built by the same
 * [com.copiosa.domain.srs.ReviewOrdering] code an ordinary one is, so the grading, the note-sibling
 * rule and everything else behave identically; the drill's one deliberate difference is that
 * not-yet-due cards are fair game, which FSRS already prices in.
 *
 * Gated on the language shipping a list at all, and then on the learner's own deck holding enough
 * cards inside the band — a drill that can field four cards is not worth a slot, so it returns null
 * and the planner drops it.
 */
object FrequentWordDrill : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.FREQUENT_WORD_DRILL,
        eligibleBands = setOf(ArousalBand.ENGAGE),
        arousalWeight = 0.78, estMinutes = 2.5,
        supportedLanguages = FrequencyLists.supportedTags, needsLlm = false,
    )

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val maxRank = maxRankFor(slot.difficultyLevel)
        if (ctx.services.frequentWordCardCount(ctx.lang, maxRank) < MIN_CARDS) return null
        return FrequentWordDrillContent(cardLimit = cardLimitFor(slot.difficultyLevel), maxRank = maxRank)
    }

    /**
     * How deep into the list the drill reaches, widened by the bucket's adaptation difficulty: a
     * learner who finds the top 500 easy is asked about words they meet less often.
     */
    fun maxRankFor(difficulty: Int): Int = when {
        difficulty <= 0 -> FrequencyBucket.TOP_500.maxRank
        difficulty == 1 -> FrequencyBucket.TOP_1K.maxRank
        else -> FrequencyBucket.TOP_2K.maxRank
    }

    /** Below this many eligible cards the drill is padding, not practice — the slot is dropped. */
    private const val MIN_CARDS = 5
}
