package com.copiosa.domain.routine.activities

import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityDescriptor
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.AffectivePiContent
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.ArousalLevel
import com.copiosa.domain.routine.PromptContext
import com.copiosa.domain.routine.RequestContext

/** Affective PI: pick the pre-conjugated statement that's true for you — no right answer. */
object AffectivePi : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.AFFECTIVE_PI,
        eligibleBands = setOf(ArousalBand.DRIFT),
        arousalWeight = 0.15, estMinutes = 1.5,
        supportedLanguages = setOf("es"), needsLlm = true,
        arousalLevel = ArousalLevel.LOW,
    )

    override fun systemRule(ctx: PromptContext): String =
        "affective_pi: a \"prompt\" plus 3 first-person ${ctx.targetName} \"statements\"; NO right answer " +
            "(the learner just picks what's true for them) — but each must be pre-conjugated so it " +
            "still trains form→meaning."

    override fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String =
        "- id $id: affective_pi, 3 statements"

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val statements = dto?.statements.orEmpty().filter { it.isNotBlank() }
        return if (statements.size >= 2) AffectivePiContent(dto?.prompt?.ifBlank { null } ?: "", statements) else null
    }
}
