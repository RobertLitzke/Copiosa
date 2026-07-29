package com.copiosa.domain.routine.activities

import com.copiosa.domain.conjugation.IrregularityFamily
import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityDescriptor
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.ArousalLevel
import com.copiosa.domain.routine.BucketCategory

/**
 * Conjugate in your head, reveal, self-grade. Content comes from the bundled verb lexicon, so this
 * needs neither the LLM nor a pre-rendered pool — only the generated `VerbLexiconData`, which is
 * always present.
 *
 * The library's first [ArousalLevel.HIGH] activity: retrieval-into-production is genuinely
 * activating, so the bedtime routine admits at most one of these and only in [ArousalBand.ENGAGE]
 * (see `RoutinePlanner`), where it has the rest of the wind-down to decay.
 */
object ConjugationDrill : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.CONJUGATION_DRILL,
        eligibleBands = setOf(ArousalBand.ENGAGE),
        // Above rapid-fire (0.85) and guess-then-flip (0.80), and far above the settle band's
        // maximum, so it sorts first inside Engage and the plan's arousal curve stays monotonically
        // non-increasing — asserted by RoutinePlannerTest.
        arousalWeight = 0.90, estMinutes = 2.0,
        supportedLanguages = setOf("es"),
        needsLlm = false,
        arousalLevel = ArousalLevel.HIGH,
    )

    /** One bucket per irregularity family, so "strong preterites" adapts apart from "stem changes". */
    override val statusCategories: List<BucketCategory> =
        IrregularityFamily.entries.map { BucketCategory(it.slug, it.label) }

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? = ctx.services.buildConjugationDrill(ctx.lang, slot)
}
