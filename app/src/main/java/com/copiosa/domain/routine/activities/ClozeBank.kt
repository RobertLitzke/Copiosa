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

/**
 * Fill the blank, from the learner's own banked sentences.
 *
 * The library's cheapest settle-band activity: the content is already on disk, mined from the
 * learner's cards, dictionary and reading, so unlike [MultipleChoiceCloze] this costs no part of the
 * routine's LLM budget and needs no grammar profile. Adding it to a routine makes that routine
 * *cheaper*, not dearer.
 *
 * It returns null when the bank can't field a block, and the planner drops the slot — which is the
 * right behaviour for a learner who has never opened the standalone screen and has nothing banked
 * yet.
 */
object ClozeBank : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.CLOZE_BANK,
        eligibleBands = setOf(ArousalBand.SETTLE),
        // Sits just under the generated multiple-choice cloze (0.50): the same shape of task, but on
        // familiar sentences, which asks slightly less of the learner.
        arousalWeight = 0.48, estMinutes = 2.0,
        supportedLanguages = null,
        needsLlm = false,
        arousalLevel = ArousalLevel.MEDIUM,
    )

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? = ctx.services.buildClozeBank(ctx.lang, slot)
}
