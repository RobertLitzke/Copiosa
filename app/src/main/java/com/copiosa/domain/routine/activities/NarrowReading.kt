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
import com.copiosa.domain.routine.NarrowReadingContent
import com.copiosa.domain.routine.PromptContext
import com.copiosa.domain.routine.RequestContext
import com.copiosa.domain.routine.difficultyNote

/** A short titled passage recycling the seed vocab, read in the shared selectable reader. */
object NarrowReading : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.NARROW_READING,
        eligibleBands = setOf(ArousalBand.SETTLE, ArousalBand.DRIFT),
        arousalWeight = 0.30, estMinutes = 4.0,
        supportedLanguages = null, needsLlm = true, needsDict = true,
        arousalLevel = ArousalLevel.LOW,
    )

    override fun systemRule(ctx: PromptContext): String =
        "narrow_reading: a short titled passage (\"paragraphs\") recycling the seed vocab."

    override fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String =
        "- id $id: narrow_reading, ${RoutineContentPrompt.READING_PARAGRAPHS} short paragraphs" +
            difficultyNote(slot.difficultyLevel)

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val paragraphs = dto?.paragraphs.orEmpty().filter { it.isNotBlank() }
        return if (paragraphs.isNotEmpty()) NarrowReadingContent(dto?.title?.ifBlank { null } ?: "", paragraphs) else null
    }
}
