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
 * Hear a banked sentence, tap its words back into order.
 *
 * Every other audio feature in the app plays speech *alongside* text or as background; here the
 * sentence is withheld until it has been rebuilt, so the ear has to do the work. Content comes from
 * the cloze bank and the on-device voice, so like [ClozeBank] it spends none of the routine's LLM
 * budget. Null (slot dropped) when the voice model isn't installed or the bank is too thin.
 */
object ListenAndRebuild : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.LISTEN_REBUILD,
        eligibleBands = setOf(ArousalBand.SETTLE),
        // Just above the banked cloze (0.48): the same familiar sentences, but heard rather than
        // read, and assembled rather than picked — a little more effortful, still unhurried.
        arousalWeight = 0.53, estMinutes = 2.5,
        supportedLanguages = null,
        needsLlm = false, needsTts = true,
        arousalLevel = ArousalLevel.MEDIUM,
    )

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? = ctx.services.buildListenRebuild(ctx.lang)
}
