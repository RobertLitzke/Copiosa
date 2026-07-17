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
import com.copiosa.domain.routine.MIN_ACTIVITY_ITEMS
import com.copiosa.domain.routine.PiItem
import com.copiosa.domain.routine.PromptContext
import com.copiosa.domain.routine.ReferentialPiContent
import com.copiosa.domain.routine.RequestContext
import com.copiosa.domain.routine.difficultyNote

/** Referential Processing Instruction: infer who/when from the conjugation alone. */
object ReferentialPi : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.REFERENTIAL_PI,
        eligibleBands = setOf(ArousalBand.SETTLE),
        arousalWeight = 0.55, estMinutes = 2.5,
        supportedLanguages = setOf("es"), needsLlm = true, needsDict = true,
    )

    override fun systemRule(ctx: PromptContext): String =
        "referential_pi: each item is ONE fully-conjugated, PRO-DROPPED ${ctx.targetName} sentence whose " +
            "verb form alone reveals who/when. The sentence must contain NO subject pronoun " +
            "(yo, tú, usted, él, ella, nosotros, vosotros, ellos, ellas) and NO time adverb " +
            "(ayer, hoy, mañana, ahora) — the learner must infer the referent from the conjugation. " +
            "\"options\" are the candidate referents; \"answerIndex\" is the correct one (0-based)."

    override fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String =
        "- id $id: referential_pi, ${RoutineContentPrompt.PI_ITEMS + RoutineContentPrompt.SPARES} items" +
            difficultyNote(slot.difficultyLevel)

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val items = dto?.items.orEmpty().map { PiItem(it.sentence, it.options, it.answerIndex, it.explanation) }
        val valid = ctx.validator.validReferentialItems(items, ctx.lang, RoutineContentPrompt.PI_ITEMS)
        return if (valid.size >= MIN_ACTIVITY_ITEMS) ReferentialPiContent(valid) else null
    }
}
