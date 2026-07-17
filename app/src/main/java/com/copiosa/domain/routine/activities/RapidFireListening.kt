package com.copiosa.domain.routine.activities

import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityDescriptor
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.BucketCategory

/** Pre-rendered listening quiz block; content comes from the seeded quiz pool, not the LLM. */
object RapidFireListening : ActivityDescriptor {
    override val definition = ActivityDefinition(
        // The most activating engage-band item (above guess-then-flip). needsLlm is false
        // because content comes from the pre-seeded quiz pool, not the routine's LLM call;
        // MEDIUM (not HIGH) arousal so the bedtime routine — the only routine type — can
        // still plan it. Availability is gated on the pool instead (see RoutinePlanner).
        type = ActivityType.RAPID_FIRE_LISTENING,
        eligibleBands = setOf(ArousalBand.ENGAGE),
        arousalWeight = 0.85, estMinutes = 2.0,
        supportedLanguages = setOf("en", "es", "fr", "de", "it", "pt"),
        needsLlm = false, needsTts = true,
    )

    override val statusCategories: List<BucketCategory> =
        CueType.entries.map { BucketCategory(it.slug, it.label) }

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? = ctx.services.buildRapidFire(ctx.lang, ctx.stats)
}
