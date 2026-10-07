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
import com.copiosa.domain.routine.RoutineType
import com.copiosa.domain.routine.ShortTextContent
import com.copiosa.domain.routine.TextFlavor

/**
 * The routine's one prose read, reshaped by the session it belongs to (see [TextFlavor]): the bedtime
 * closer's soothing story, the chapter that continues it night to night, or the daytime activation's
 * opening dispatch. One activity rather than three near-identical ones, because the only real
 * differences are the prompt and the length.
 *
 * The flavour is resolved at prompt/build time from the [RequestContext], never at plan time, so the
 * planner stays flavour-blind and this activity keeps a single [estMinutes] for its Drift budget.
 */
object ShortText : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.SHORT_TEXT,
        eligibleBands = setOf(ArousalBand.DRIFT),
        // Sized to fit the Drift budget at every offered duration: the old serial story asked for 5.0
        // minutes against a 1.5-4.5 minute cap, so it could never actually be planned.
        arousalWeight = 0.10, estMinutes = 2.0,
        supportedLanguages = null, needsLlm = true, needsDict = true,
        arousalLevel = ArousalLevel.LOW,
    )

    /** Daytime always motivates; bedtime continues a thread when there is one, else starts a story. */
    fun flavorFor(routineType: RoutineType, storyContext: String?): TextFlavor = when {
        routineType == RoutineType.DAYTIME -> TextFlavor.DAYTIME_MOTIVATION
        !storyContext.isNullOrBlank() -> TextFlavor.SERIAL_CHAPTER
        else -> TextFlavor.BEDTIME_STORY
    }

    override fun systemRule(ctx: PromptContext): String =
        "short_text: a \"title\" and the prose the request asks for. As \"sentences\" when sentences are " +
            "requested, as \"paragraphs\" when paragraphs are. A chapter request also needs a one-sentence " +
            "\"storySummary\" recapping the story so far for the next chapter — continue from the recap " +
            "given, or begin the story if none is. Always recycle the seed vocab."

    override fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String {
        val flavor = flavorFor(ctx.routineType, ctx.storyContext)
        return when (flavor) {
            TextFlavor.BEDTIME_STORY ->
                "- id $id: short_text, a calm bedtime story in " +
                    "${RoutineContentPrompt.storySentenceCount(ctx.durationMinutes)} soothing \"sentences\""
            TextFlavor.SERIAL_CHAPTER ->
                "- id $id: short_text, ONE chapter of an ongoing story in " +
                    "${RoutineContentPrompt.STORY_CHAPTER_PARAGRAPHS} calm \"paragraphs\" — keep the story's " +
                    "title stable and continue this story so far: \"${ctx.storyContext}\""
            TextFlavor.DAYTIME_MOTIVATION ->
                "- id $id: short_text, a brief second-person warm-up in " +
                    "${RoutineContentPrompt.MOTIVATION_SENTENCES} \"sentences\" — speak directly to the " +
                    "learner about something concrete they could do or notice today using the seed words. " +
                    "Not a story, and no generic encouragement"
        }
    }

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val flavor = flavorFor(ctx.routineType, ctx.storyContext)
        // Chapters come back as paragraphs and the other flavours as sentences, but a model that mixes
        // the two shouldn't cost the learner the whole slot — take whichever field arrived.
        val blocks = (dto?.paragraphs.orEmpty() + dto?.sentences.orEmpty()).filter { it.isNotBlank() }
        if (blocks.isEmpty()) return null
        return ShortTextContent(
            flavor = flavor,
            title = dto?.title?.ifBlank { null } ?: "",
            blocks = blocks,
            chapter = if (flavor == TextFlavor.SERIAL_CHAPTER) ctx.nextChapter else null,
        )
    }
}
