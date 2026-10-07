package com.copiosa.domain.routine.activities

import com.copiosa.domain.language.LanguageFeature
import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.language.LanguageProfiles
import com.copiosa.domain.prompt.RoutineContentPrompt
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityDescriptor
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.BucketCategory
import com.copiosa.domain.routine.ClozeItem
import com.copiosa.domain.routine.ContrastCategory
import com.copiosa.domain.routine.ContrastClozeContent
import com.copiosa.domain.routine.MIN_ACTIVITY_ITEMS
import com.copiosa.domain.routine.PromptContext
import com.copiosa.domain.routine.RequestContext
import com.copiosa.domain.routine.difficultyNote

/** Two-option cloze over one grammatical contrast (ser/estar, por/para, ...), a category per slot. */
object ContrastCloze : ActivityDescriptor {
    override val definition = ActivityDefinition(
        type = ActivityType.CONTRAST_CLOZE,
        eligibleBands = setOf(ArousalBand.SETTLE),
        arousalWeight = 0.50, estMinutes = 2.5,
        supportedLanguages = null, needsLlm = true, needsDict = true,
        requires = setOf(LanguageFeature.GRAMMAR),
    )

    override val statusCategories: List<BucketCategory> =
        ContrastCategory.entries.map { BucketCategory(it.slug, it.label) }

    override fun systemRule(ctx: PromptContext): String =
        "contrast_cloze: each item is a ${ctx.targetName} sentence containing exactly one \"${ClozeItem.BLANK}\" " +
            "blank, with exactly two \"options\" (the contrast pair) and the correct \"answerIndex\". " +
            "Crucially, vary the answer across the items so neither option is correct every time: mix the " +
            "contexts — for a trigger-based contrast like the subjunctive, include sentences whose context " +
            "does NOT trigger it (so the indicative is the answer) — and vary which position (0 or 1) holds " +
            "the correct option. Aim for a roughly even split."

    override fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String? {
        val cat = ContrastCategory.fromSlug(slot.category)?.label
            ?: ctx.profile?.contrasts?.firstOrNull()?.label
            ?: return null
        return "- id $id: contrast_cloze on $cat, " +
            "${RoutineContentPrompt.CLOZE_ITEMS + RoutineContentPrompt.SPARES} items" +
            difficultyNote(slot.difficultyLevel)
    }

    override suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent? {
        val items = dto?.items.orEmpty().map { ClozeItem(it.sentence, it.options, it.answerIndex, it.explanation) }
        val valid = ctx.validator.validClozeItems(items, ctx.lang, RoutineContentPrompt.CLOZE_ITEMS)
        val category = slot.category
            ?: LanguageProfiles.forTag(ctx.lang)?.contrasts?.firstOrNull()?.slug
            ?: return null
        return if (valid.size >= MIN_ACTIVITY_ITEMS) ContrastClozeContent(category, valid) else null
    }
}
