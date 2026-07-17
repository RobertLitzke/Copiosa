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
import com.copiosa.domain.routine.PromptContext
import com.copiosa.domain.routine.RequestContext
import com.copiosa.domain.routine.SerialStoryContent

/** One chapter of a story that continues night to night via the persisted story thread. */
object SerialStory : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.SERIAL_STORY,
        eligibleBands = setOf(ArousalBand.DRIFT),
        arousalWeight = 0.20, estMinutes = 5.0,
        supportedLanguages = null, needsLlm = true, needsDict = true,
        arousalLevel = ArousalLevel.LOW,
    )

    override fun systemRule(ctx: PromptContext): String =
        "serial_story: ONE chapter of an ongoing story — a \"title\" (the story's name, kept stable) " +
            "and \"paragraphs\"; also a one-sentence \"storySummary\" recapping the story so far for the next " +
            "chapter. Continue from the recap if one is given, else begin a fresh calm story."

    override fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String = buildString {
        append("- id $id: serial_story, ${RoutineContentPrompt.STORY_CHAPTER_PARAGRAPHS} calm paragraphs")
        if (!ctx.storyContext.isNullOrBlank()) append(" — continue this story so far: \"${ctx.storyContext}\"")
    }

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val paragraphs = dto?.paragraphs.orEmpty().filter { it.isNotBlank() }
        return if (paragraphs.isNotEmpty()) {
            SerialStoryContent(ctx.nextChapter, dto?.title?.ifBlank { null } ?: "", paragraphs)
        } else {
            null
        }
    }
}
