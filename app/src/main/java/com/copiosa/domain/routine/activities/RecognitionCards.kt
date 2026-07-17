package com.copiosa.domain.routine.activities

import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityDescriptor
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.ArousalLevel
import com.copiosa.domain.routine.RecognitionCardsContent
import com.copiosa.domain.routine.cardLimitFor

/** Reverse (L2 -> L1) cards from the SRS pool — comprehension only, no generated content. */
object RecognitionCards : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.RECOGNITION_CARDS,
        eligibleBands = setOf(ArousalBand.SETTLE),
        arousalWeight = 0.45, estMinutes = 2.5,
        supportedLanguages = null, needsLlm = false,
        arousalLevel = ArousalLevel.LOW,
    )

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent = RecognitionCardsContent(cardLimit = cardLimitFor(slot.difficultyLevel))
}
