package com.copiosa.domain.routine.activities

import com.copiosa.domain.language.LanguageFeature
import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityDescriptor
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.ArousalLevel
import com.copiosa.domain.routine.SortDimension
import com.copiosa.domain.routine.SwipeSortContent

/** Swipe words into gender bins — built offline from the learner's own cards, no LLM. */
object SwipeSort : ActivityDescriptor {
    override val definition = ActivityDefinition(
        // Dual-band like narrow_reading; must share its boundary weight (0.30) so the planner's
        // monotonic de-escalation holds no matter which side of the Settle→Drift line each lands.
        type = ActivityType.SWIPE_SORT,
        eligibleBands = setOf(ArousalBand.SETTLE, ArousalBand.DRIFT),
        arousalWeight = 0.30, estMinutes = 1.5,
        supportedLanguages = null, needsLlm = false,
        requires = setOf(LanguageFeature.GENDER),
        arousalLevel = ArousalLevel.LOW,
    )

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val items = ctx.services.swipeSortItems(ctx.lang)
        return if (items.size >= MIN_WORDS) SwipeSortContent(SortDimension.GENDER, items) else null
    }

    private const val MIN_WORDS = 4
}
