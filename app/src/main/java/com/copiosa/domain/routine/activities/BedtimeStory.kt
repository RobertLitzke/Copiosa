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
import com.copiosa.domain.routine.BedtimeStoryContent
import com.copiosa.domain.routine.PromptContext
import com.copiosa.domain.routine.RequestContext

/** The routine's closer: a short, soothing story, always last. */
object BedtimeStory : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.BEDTIME_STORY,
        eligibleBands = setOf(ArousalBand.DRIFT),
        arousalWeight = 0.05, estMinutes = 1.5,
        supportedLanguages = null, needsLlm = true, needsTts = true,
        arousalLevel = ArousalLevel.LOW, isCloser = true,
    )

    override fun systemRule(ctx: PromptContext): String =
        "bedtime_story: a \"title\" and calm \"sentences\", short and soothing, recycling the seed vocab."

    override fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String =
        "- id $id: bedtime_story, ${RoutineContentPrompt.storySentenceCount(ctx.durationMinutes)} calm sentences"

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val sentences = dto?.sentences.orEmpty().filter { it.isNotBlank() }
        return if (sentences.isNotEmpty()) BedtimeStoryContent(dto?.title?.ifBlank { null } ?: "", sentences) else null
    }
}
