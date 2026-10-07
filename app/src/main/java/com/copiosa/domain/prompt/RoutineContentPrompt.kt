package com.copiosa.domain.prompt

import com.copiosa.domain.language.LanguageProfiles
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.routine.ActivityRegistry
import com.copiosa.domain.routine.PromptContext
import com.copiosa.domain.routine.RequestContext
import com.copiosa.domain.routine.RoutineBlueprint
import com.copiosa.domain.routine.RoutineType
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the *single* LLM request that generates a whole routine's content (the ≤5-calls-per-routine,
 * ideally-1 budget). The system prompt fixes the teacher persona and the strict JSON schema; the user
 * request enumerates exactly the slots the [RoutineBlueprint] needs (by id, so duplicate types are
 * unambiguous), seeded with the chosen topic and the learner's recently-seen vocab to recycle.
 *
 * The response is one JSON object `{"activities":[{"id":…, …}]}` parsed by [RoutineContentParser].
 */
@Singleton
class RoutineContentPrompt @Inject constructor() {

    private fun languageName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    fun systemPrompt(
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
        routineType: RoutineType,
    ): String {
        val target = languageName(targetLang)
        val native = languageName(nativeLang)
        return buildString {
            append("You are a $country $target teacher preparing ${routineType.personaLine} for a ")
            append("$native-speaking learner at CEFR ${level.name}. Generate all requested activities in one go.\n\n")
            append("Write every $target sentence at approximately ${level.name}: ${routineType.registerLine}. ")
            append("Write all explanations and glosses in $native.\n\n")
            append("Activity rules:\n")
            // Each descriptor contributes its own rule block; registry order fixes the emission order.
            val ctx = PromptContext(target, native, routineType, LanguageProfiles.forTag(targetLang))
            ActivityRegistry.all.forEach { descriptor ->
                descriptor.systemRule(ctx)?.let { append("- ").append(it).append("\n") }
            }
            append("\n")
            append("Output ONLY a single JSON object, no prose and no code fences: ")
            append("{\"activities\":[{\"id\":<int>,\"items\":[{\"sentence\":..,\"options\":[..],\"answerIndex\":<int>,")
            append("\"explanation\":..}],\"title\":..,\"paragraphs\":[..],\"prompt\":..,\"statements\":[..],")
            append("\"sentences\":[..],\"words\":[{\"form\":..,\"falseFriend\":..,\"gloss\":..}],\"storySummary\":..}]}. ")
            append("Include for each id only the fields its activity type needs. ")
            append("Echo each requested id exactly. Use plain double-quoted strings.")
        }
    }

    fun userRequest(
        blueprint: RoutineBlueprint,
        topic: String?,
        topicContext: String?,
        vocab: List<String>,
        storyContext: String? = null,
    ): String = buildString {
        val routineType = blueprint.routineType
        if (!topic.isNullOrBlank()) {
            append("${routineType.themeLabel}: \"$topic\".")
            if (!topicContext.isNullOrBlank()) append(" (Context: $topicContext)")
            append(" Weave it through the activities where natural.\n")
        } else {
            append("${routineType.themeFallback}\n")
        }
        if (vocab.isNotEmpty()) {
            append("Recycle these recently-studied words where natural: ${vocab.joinToString(", ")}.\n")
        }
        append("\nGenerate these activities:\n")
        // Each descriptor writes its own request line; types with no generated content return null.
        val ctx = RequestContext(
            routineType,
            blueprint.durationMinutes,
            storyContext,
            LanguageProfiles.forTag(blueprint.targetLanguage),
        )
        blueprint.slots.forEachIndexed { id, slot ->
            ActivityRegistry[slot.type].requestLine(id, slot, ctx)?.let { append(it).append("\n") }
        }
    }

    companion object {
        const val PI_ITEMS = 4
        const val CLOZE_ITEMS = 4
        const val MC_CLOZE_ITEMS = 4
        const val COGNATE_WORDS = 7
        const val STORY_CHAPTER_PARAGRAPHS = 4
        const val READING_PARAGRAPHS = 3
        const val SPARES = 2

        /** The daytime dispatch stays short whatever the routine length — it is a doorway, not a read. */
        const val MOTIVATION_SENTENCES = 4

        /** Story length scales gently with the routine length (the spec's 6–12 sentences). */
        fun storySentenceCount(durationMinutes: Int): Int = when {
            durationMinutes <= 5 -> 6
            durationMinutes <= 10 -> 8
            else -> 12
        }
    }
}
