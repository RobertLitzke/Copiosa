package com.copiosa.domain.course

import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.routine.ClozeItem
import com.copiosa.domain.routine.ContrastCategory
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the *single* LLM request that fills a lesson's practice slots, mirroring
 * `RoutineContentPrompt`'s contract: one system prompt fixing the persona and a strict JSON schema,
 * one user request enumerating slots by id (the slot's index within the lesson's authored segments,
 * so duplicates are unambiguous).
 *
 * What is asked for is deliberately narrow. The teaching itself is authored and the paradigm tables
 * come off the engine; the model supplies only *contextual* practice — sentences in which the taught
 * form has to be chosen — because that is the part that benefits from being fresh each sitting.
 */
@Singleton
class CourseContentPrompt @Inject constructor() {

    private fun languageName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    fun systemPrompt(
        targetLang: String,
        nativeLang: String,
        level: Cefr,
        country: String?,
    ): String {
        val target = languageName(targetLang)
        val native = languageName(nativeLang)
        return buildString {
            append("You are a ")
            if (!country.isNullOrBlank()) append("$country ")
            append("$target teacher writing practice items for one grammar lesson, for a ")
            append("$native-speaking learner at CEFR ${level.name}. Generate every requested slot in one go.\n\n")
            append("Write every $target sentence at approximately ${level.name}, in plain everyday register. ")
            append("Write explanations in $native, one short sentence each.\n\n")
            append("Rules:\n")
            append("- Each item has exactly one defensible answer. The sentence's own context must decide it — ")
            append("never leave two options equally correct.\n")
            append("- Cloze items: put \"${ClozeItem.BLANK}\" where the answer goes, exactly once. ")
            append("The options are the candidate forms; \"answerIndex\" is 0-based into that list.\n")
            append("- Vary which option index is correct across items, and vary the grammatical person. ")
            append("A learner must not be able to score by always picking the same slot.\n")
            append("- Stay inside the lesson's stated scope: do not use tenses the lesson has not covered.\n")
            append("- Distractors must be wrong for a *reason a learner would fall for* — the over-regularised ")
            append("form, or the neighbouring tense — never a random word.\n")
            append("- Keep sentences short and concrete. No proper nouns the learner would not know.\n\n")
            append("Output ONLY a single JSON object, no prose and no code fences: ")
            append("{\"slots\":[{\"id\":<int>,\"items\":[{\"sentence\":..,\"options\":[..],")
            append("\"answerIndex\":<int>,\"explanation\":..}]}]}. ")
            append("Echo each requested id exactly. Use plain double-quoted strings.")
        }
    }

    /**
     * The request body: what this lesson teaches, then one line per slot. [spec] supplies the model
     * verbs a scope names so the request can be concrete about the forms in play.
     */
    fun userRequest(
        course: CourseDefinition,
        lesson: LessonDefinition,
        slots: List<IndexedValue<AuthoredSegment.LlmSlot>>,
        spec: ConjugationSpec?,
        vocab: List<String>,
    ): String = buildString {
        append("Course: ${course.title} — ${course.blurb}\n")
        append("Lesson: ${lesson.title}.\n")
        append("Scope: ${describeScope(lesson.scope, spec)}\n")
        if (vocab.isNotEmpty()) {
            append("Recycle these recently-studied words where natural: ${vocab.joinToString(", ")}.\n")
        }
        append("\nGenerate these slots:\n")
        for ((index, slot) in slots) {
            val count = CourseContentValidator.requestCount(slot.itemCount)
            append("- id $index: $count ")
            append(
                when (slot.kind) {
                    LlmSlotKind.CLOZE -> "cloze items"
                    LlmSlotKind.PI -> "referential items (a fully conjugated sentence with the subject " +
                        "pronoun omitted; the options are candidate subjects and the verb ending alone must reveal which)"
                },
            )
            append(". ").append(slot.brief.replace('\n', ' ')).append("\n")
        }
        append("\nGenerate more items than asked only where stated; extras let me drop any that don't fit.")
    }

    /** The optional second call: one short diagnosis of the patterns behind a sitting's mistakes. */
    fun feedbackRequest(lesson: LessonDefinition, missed: List<String>): String = buildString {
        append("The learner just finished the lesson \"${lesson.title}\" and got these wrong:\n")
        missed.take(MAX_FEEDBACK_ITEMS).forEach { append("- ").append(it).append("\n") }
        append("\nIn at most three sentences, name the single pattern behind these mistakes and how to ")
        append("avoid it next time. Be specific and encouraging; do not list the items back. ")
        append("Output ONLY {\"feedback\":\"...\"}.")
    }

    /** A plain-language rendering of what the lesson covers, for the request header. */
    private fun describeScope(scope: LessonScope, spec: ConjugationSpec?): String = buildString {
        if (scope.isMorphological) {
            append(scope.tenses.joinToString(" and ") { it.name.lowercase().replace('_', ' ') })
            append(" tense")
            if (scope.tenses.size > 1) append("s")
        } else {
            // No paradigm to name, so the contrast is the whole of what the lesson is about. Saying
            // "tense" here anyway would send the model looking for a conjugation to blank.
            append(ContrastCategory.fromSlug(scope.contrastCategory)?.label ?: "this grammar point")
        }
        scope.verbClasses?.let {
            append("; ").append(it.joinToString("/") { cls -> "-${cls.ending}" }).append(" verbs")
        }
        if (scope.regularOnly) append("; regular verbs only")
        scope.verbs?.let { append("; the verbs ").append(it.joinToString(", ")) }
        // A lesson that has only taught two endings must not be handed sentences using the other
        // four — this line is the only thing standing between a gradual course and a model that
        // reaches for the whole paradigm because it knows it.
        scope.persons?.let { wanted ->
            val labels = spec?.persons?.filter { it in wanted }?.mapNotNull { spec.pronouns[it] }.orEmpty()
            if (labels.isNotEmpty()) {
                append("; ONLY these grammatical persons, no others: ").append(labels.joinToString(", "))
            }
        }
        scope.irregularityClasses?.let { classes ->
            append("; the pattern(s) ").append(classes.joinToString(", ") { it.label })
            // Concrete exemplars keep the model from inventing its own idea of the pattern.
            val examples = spec?.let { s ->
                classes.flatMap { s.lexicon.verbsInClass(it) }.take(EXEMPLARS).map { it.infinitive }
            }.orEmpty()
            if (examples.isNotEmpty()) append(" (e.g. ").append(examples.joinToString(", ")).append(")")
        }
        if (scope.contrastCategory != null) {
            append("; the learner must choose between them, so both must be genuinely available")
        }
        append(".")
    }

    private companion object {
        const val EXEMPLARS = 5
        const val MAX_FEEDBACK_ITEMS = 8
    }
}
