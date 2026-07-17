package com.copiosa.domain.routine.activities

import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.prompt.RoutineContentPrompt
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityDescriptor
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.ArousalLevel
import com.copiosa.domain.routine.ClozeItem
import com.copiosa.domain.routine.MIN_ACTIVITY_ITEMS
import com.copiosa.domain.routine.MultipleChoiceClozeContent
import com.copiosa.domain.routine.PromptContext
import com.copiosa.domain.routine.RequestContext
import com.copiosa.domain.routine.difficultyNote

/** Three-option cloze: the correct word plus two meaning-distinct distractors. */
object MultipleChoiceCloze : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.MULTIPLE_CHOICE_CLOZE,
        eligibleBands = setOf(ArousalBand.SETTLE),
        arousalWeight = 0.50, estMinutes = 2.5,
        supportedLanguages = setOf("es"), needsLlm = true, needsDict = true,
        arousalLevel = ArousalLevel.MEDIUM,
    )

    override fun systemRule(ctx: PromptContext): String =
        "multiple_choice_cloze: like contrast_cloze but each item has exactly THREE \"options\" — " +
            "the correct word plus two plausible, MEANING-DISTINCT distractors — and one \"${ClozeItem.BLANK}\" " +
            "blank; vary which position holds the answer."

    override fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String =
        "- id $id: multiple_choice_cloze, " +
            "${RoutineContentPrompt.MC_CLOZE_ITEMS + RoutineContentPrompt.SPARES} items" +
            difficultyNote(slot.difficultyLevel)

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val items = dto?.items.orEmpty().map { ClozeItem(it.sentence, it.options, it.answerIndex, it.explanation) }
        val valid = ctx.validator.validMcClozeItems(items, ctx.lang, RoutineContentPrompt.MC_CLOZE_ITEMS)
        return if (valid.size >= MIN_ACTIVITY_ITEMS) MultipleChoiceClozeContent(valid) else null
    }
}
