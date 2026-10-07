package com.copiosa.domain.routine.activities

import com.copiosa.domain.language.LanguageFeature
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
import com.copiosa.domain.routine.CognateHuntContent
import com.copiosa.domain.routine.CognateWord
import com.copiosa.domain.routine.PromptContext
import com.copiosa.domain.routine.RequestContext

/** Tap-to-reveal cognates and false friends — lexical noticing, lightly rewarding. */
object CognateHunt : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.COGNATE_HUNT,
        eligibleBands = setOf(ArousalBand.DRIFT),
        arousalWeight = 0.15, estMinutes = 1.5,
        supportedLanguages = null, needsLlm = true,
        requires = setOf(LanguageFeature.GRAMMAR),
        arousalLevel = ArousalLevel.LOW,
    )

    override fun systemRule(ctx: PromptContext): String =
        "cognate_hunt: a \"words\" list — mostly true ${ctx.nativeName} cognates the learner will recognize, " +
            "plus 1–2 real false friends (e.g. ${ctx.profile?.falseFriendExamples.orEmpty().joinToString(", ")}). " +
            "Each entry: \"form\" (one ${ctx.targetName} word), " +
            "\"falseFriend\" (boolean), and a ${ctx.nativeName} \"gloss\" of its real meaning (essential for false friends)."

    override fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String =
        "- id $id: cognate_hunt, ${RoutineContentPrompt.COGNATE_WORDS} words"

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val words = dto?.words.orEmpty()
            .filter { it.form.isNotBlank() }
            .map { CognateWord(it.form.trim(), it.falseFriend, it.gloss?.trim()?.ifBlank { null }) }
        return if (words.size >= MIN_WORDS) CognateHuntContent(words) else null
    }

    private const val MIN_WORDS = 4
}
