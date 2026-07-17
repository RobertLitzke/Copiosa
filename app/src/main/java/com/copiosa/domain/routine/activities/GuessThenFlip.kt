package com.copiosa.domain.routine.activities

import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityDescriptor
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.GuessThenFlipContent
import com.copiosa.domain.routine.cardLimitFor

/** Real FSRS flashcard review, framed by the routine — no generated content. */
object GuessThenFlip : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.GUESS_THEN_FLIP,
        eligibleBands = setOf(ArousalBand.ENGAGE),
        arousalWeight = 0.80, estMinutes = 2.5,
        supportedLanguages = null, needsLlm = false,
    )

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent = GuessThenFlipContent(cardLimit = cardLimitFor(slot.difficultyLevel))
}
