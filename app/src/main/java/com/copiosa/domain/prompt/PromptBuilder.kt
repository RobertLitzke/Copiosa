package com.copiosa.domain.prompt

import com.copiosa.domain.model.Cefr
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds system prompts for each mode. The conversation prompt is driven by a user-editable
 * template ([DEFAULT_CORE_PROMPT]) plus optional custom instructions; both support the
 * placeholders in [CORE_PLACEHOLDERS], substituted from the learner's current settings.
 */
@Singleton
class PromptBuilder @Inject constructor() {

    fun languageName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    private fun levelDescriptor(level: Cefr): String = when (level) {
        Cefr.A1, Cefr.A2 -> "beginner"
        Cefr.B1, Cefr.B2 -> "intermediate"
        Cefr.C1, Cefr.C2 -> "advanced"
    }

    /** Substitutes the [CORE_PLACEHOLDERS] in [text] from the current learner context. */
    private fun substitute(
        text: String,
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
        subject: String,
    ): String = text
        .replace("{country}", country)
        .replace("{target_language}", languageName(targetLang))
        .replace("{native_language}", languageName(nativeLang))
        .replace("{level}", level.name)
        .replace("{next_level}", level.next().name)
        .replace("{subject}", subject)

    /**
     * Builds the conversation system prompt from the editable [core] template and optional
     * [customInstructions]. Placeholders are substituted in both; the instructions are appended.
     *
     * [vocabToUse] is a sample of the learner's saved words (front to back/translation) for the model
     * to weave in where natural; [reuseWords] are distinctive words from the previous reply to recur.
     * The fixed structured-output block is always appended so every reply carries metadata.
     */
    fun buildConversationPrompt(
        core: String,
        customInstructions: String,
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
        subject: String,
        vocabToUse: List<Pair<String, String>> = emptyList(),
        reuseWords: List<String> = emptyList(),
        // Requested on a chat's opening reply so the list can show a target-language title instead of
        // the learner's native-language subject. Topics are dropped for "Explain" turns.
        includeTitle: Boolean = false,
        includeTopics: Boolean = true,
    ): String {
        val base = substitute(core, country, targetLang, nativeLang, level, subject)
        val extra = customInstructions.trim()
        val withExtra =
            if (extra.isEmpty()) base else base + "\n\n" + substitute(extra, country, targetLang, nativeLang, level, subject)
        return withExtra +
            vocabToUseSection(vocabToUse) +
            reuseWordsSection(reuseWords) +
            structuredOutput(
                distinctiveInstruction = DISTINCTIVE_FROM_REPLY,
                includeTopics = includeTopics,
                includeVocabReport = vocabToUse.isNotEmpty(),
                avoidTrailingQuestion = true,
                glossLangName = languageName(nativeLang),
                includeTitle = includeTitle,
                titleLangName = languageName(targetLang),
            )
    }

    /** The built-in conversation prompt, using [DEFAULT_CORE_PROMPT] with no custom instructions. */
    fun conversationSystemPrompt(
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
        subject: String,
    ): String = buildConversationPrompt(
        core = DEFAULT_CORE_PROMPT,
        customInstructions = "",
        country = country,
        targetLang = targetLang,
        nativeLang = nativeLang,
        level = level,
        subject = subject,
    )

    /**
     * System prompt for enriching saved vocabulary. The learner sends a JSON array of items
     * (`[{"id":…,"term":…,"context":…}]`); the model must reply with a JSON array of explanations,
     * one per id. Used for both a single "explain" tap and the batched "fill explanations" action.
     */
    fun vocabExplanationSystemPrompt(
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
    ): String {
        val target = languageName(targetLang)
        val native = languageName(nativeLang)
        return "You are a $country $target teacher helping a ${levelDescriptor(level)} " +
            "$native-speaking learner understand $target vocabulary they have saved.\n" +
            "The learner sends a JSON array of items, each {\"id\": <number>, \"term\": <$target word " +
            "or phrase>, \"context\": <sentence it appeared in, or null>}.\n" +
            "Reply with ONLY a JSON array (no prose, no code fences) of the same length, each item:\n" +
            "{\"id\": <same id>, \"pos\": <one of \"noun\",\"verb\",\"adjective\",\"adverb\",\"phrase\"," +
            "\"expression\",\"grammar\",\"other\">, \"cefr\": <one of \"A1\",\"A2\",\"B1\",\"B2\",\"C1\",\"C2\">, " +
            "\"gender\": <for a noun, one of \"M\",\"F\",\"MF\" (common/epicene),\"MPL\",\"FPL\"; else null>, " +
            "\"region\": <a short region slug like \"mx\",\"spain\",\"latam\" if the term is region-specific; " +
            "else null>, " +
            "\"explanation\": <the meaning written in $native>, \"example\": <one natural $target sentence " +
            "using the term>}.\n" +
            "Write the \"explanation\" in $native, in a colloquial, friendly tone (2–4 sentences), " +
            "covering nuance and close synonyms; the informal take matters more than a dictionary " +
            "definition. Use simple Markdown (e.g. **bold**, *italics*, '- ' bullets). Always echo each id exactly."
    }

    /**
     * System prompt for translating an imported text (or its title) into the learner's target
     * language. The learner's message carries the source text; the model returns only the
     * translation. Level is the priority, but the original's voice/style is preserved as far as the
     * level allows. For long articles the text is translated in pieces — [part]/[totalParts] keep the
     * pieces consistent.
     */
    fun textTranslationSystemPrompt(
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
        part: Int = 1,
        totalParts: Int = 1,
        // Up to this many distinctive words to extract from this part; <= 0 (e.g. the title call)
        // means a plain translation with no structured-output block.
        distinctiveBudget: Int = 0,
    ): String {
        val target = languageName(targetLang)
        val partNote = if (totalParts > 1) {
            " This is part $part of $totalParts of a longer article; translate only this part and " +
                "continue seamlessly in the same style — no part headings."
        } else {
            ""
        }
        val intro = "You are a $country $target teacher preparing reading material for a " +
            "${levelDescriptor(level)} learner. Translate the text the learner sends into $target at " +
            "approximately ${level.name} level: simplifying vocabulary and grammar to that level is the " +
            "priority, but preserve the original's tone, voice, register and style (e.g. humorous, " +
            "serious, journalistic, lyrical) as far as the level allows. Keep the author's meaning and " +
            "the paragraph structure, and use simple Markdown for any emphasis or headings. If the text " +
            "is already in $target, adapt it to ${level.name} the same way."
        if (distinctiveBudget <= 0) {
            return intro + " Output ONLY the translated text — no preamble, no notes, no invitation to discuss.$partNote"
        }
        // The metadata block follows the translation; topics are only requested on the final part.
        return intro + " Output the translated text with no preamble or invitation to discuss.$partNote" +
            structuredOutput(
                distinctiveInstruction = "up to $distinctiveBudget distinctive or difficult words from THIS part that a " +
                    "$target learner likely hasn't seen, written in the translated $target form",
                includeTopics = part >= totalParts,
                includeVocabReport = false,
                glossLangName = languageName(nativeLang),
            )
    }

    /**
     * Chat persona for discussing an imported text after reading it. The translated text already sits
     * earlier in the conversation, so this prompt does not re-embed the original — it just frames the
     * teacher to talk about "the text the learner just read", keeping discussion turns cheap.
     */
    fun textDiscussionSystemPrompt(
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
        vocabToUse: List<Pair<String, String>> = emptyList(),
        reuseWords: List<String> = emptyList(),
        // Dropped for "Explain" turns, whose reply is a one-word definition, not a conversational turn.
        includeTopics: Boolean = true,
    ): String {
        val target = languageName(targetLang)
        val native = languageName(nativeLang)
        val base = "You are a $country $target teacher discussing a text the learner has just read; it " +
            "appears earlier in this conversation, already translated into $target. Always respond in " +
            "$target at approximately ${level.name} level, even if the learner writes in $native. The " +
            "only exception: if the learner asks for an explanation of a $target word or phrase, respond " +
            "in $native. Keep paragraphs short and use simple Markdown. Answer the learner's questions " +
            "about the text, help them with its vocabulary and grammar, and keep the conversation going " +
            "naturally."
        return base +
            vocabToUseSection(vocabToUse) +
            reuseWordsSection(reuseWords) +
            structuredOutput(
                distinctiveInstruction = DISTINCTIVE_FROM_REPLY,
                includeTopics = includeTopics,
                includeVocabReport = vocabToUse.isNotEmpty(),
                avoidTrailingQuestion = true,
                glossLangName = native,
            )
    }

    /**
     * Saved words for the model to weave in where natural (front, with its back/translation in
     * parentheses for disambiguation). Empty list -> no section.
     */
    private fun vocabToUseSection(words: List<Pair<String, String>>): String {
        if (words.isEmpty()) return ""
        val list = words.joinToString("; ") { (front, back) ->
            if (back.isBlank()) front else "$front ($back)"
        }
        return "\n\nThe learner is studying these saved words: $list. Use the ones that fit naturally, " +
            "without distorting your reply, and never force them; you need not use any."
    }

    /** Distinctive words from the previous reply to recur in new contexts. Empty list -> no section. */
    private fun reuseWordsSection(words: List<String>): String {
        if (words.isEmpty()) return ""
        return "\n\nWhere it fits naturally, reuse some of these words from earlier so the learner sees " +
            "them again in fresh contexts and conjugations: ${words.joinToString(", ")}."
    }

    /**
     * The fixed trailing instruction: after the reply, emit [StructuredReplyParser.SENTINEL] then a
     * one-line JSON object. Only the requested fields are described; the parser tolerates the rest.
     */
    private fun structuredOutput(
        distinctiveInstruction: String,
        includeTopics: Boolean,
        includeVocabReport: Boolean,
        avoidTrailingQuestion: Boolean = false,
        // Language the topic glosses should be written in (the learner's native language).
        glossLangName: String = "English",
        // When set, request a short conversation title in [titleLangName] (the target language).
        includeTitle: Boolean = false,
        titleLangName: String = "the target language",
    ): String {
        val fields = buildList {
            if (includeTitle) {
                add(
                    "\"title\": <a short title for this conversation in $titleLangName (2-6 words) naming " +
                        "its subject — not a sentence>",
                )
            }
            if (includeTopics) {
                add(
                    "\"topics\": [1-3 subjects the learner could explore next from here (NOT questions); " +
                        "always provide some, even after a short follow-up reply. Each is an object " +
                        "{\"phrase\": <the subject, 1-5 words — e.g. a person, event, or theme>, " +
                        "\"gloss\": <a brief note in $glossLangName, at most ~12 words, saying what the " +
                        "phrase refers to so it stands on its own outside this chat>}]",
                )
            }
            add("\"distinctive\": [$distinctiveInstruction]")
            if (includeVocabReport) {
                add(
                    "\"vocabUsed\": [the supplied saved words you worked in], " +
                        "\"vocabUnused\": [the supplied saved words you could not fit naturally]",
                )
            }
        }.joinToString(", ")
        // For chat-style replies, keep the turn open via the topics field instead of a trailing question.
        val lead = if (avoidTrailingQuestion) {
            "Do not end your reply with a question or an invitation to keep chatting; offer any natural " +
                "ways to continue only through the \"topics\" field below. "
        } else {
            ""
        }
        return "\n\n" + lead + "After your reply, on a new line write exactly ${StructuredReplyParser.SENTINEL} " +
            "followed by a single-line JSON object and nothing else (no code fences): {$fields}. Append this " +
            "block to every reply, not only your first one. Keep it out of the reply itself, and use plain " +
            "double-quoted strings."
    }

    companion object {
        /** Distinctive-word instruction shared by conversation and text-discussion prompts. */
        private const val DISTINCTIVE_FROM_REPLY =
            "1-5 of the hardest or most distinctive words from your reply that the learner is least " +
                "likely to know"

        /** Placeholders recognised in the core prompt and custom instructions. */
        val CORE_PLACEHOLDERS = listOf(
            "{country}", "{target_language}", "{native_language}", "{level}", "{next_level}", "{subject}",
        )

        /** Default editable conversation prompt. Mirrors the original "comprehensible input" persona. */
        const val DEFAULT_CORE_PROMPT =
            "You are a {country} {target_language} teacher working with a learner. Deliver " +
                "comprehensible input at approximately {level} level, with occasional {next_level} " +
                "vocabulary. Always respond in {target_language}, even if the learner writes in " +
                "{native_language}. The only exception: if the learner asks for an explanation of a " +
                "{target_language} word or phrase, respond in {native_language}. Keep paragraphs short " +
                "and use simple Markdown. Begin by writing a short essay about \"{subject}\". After " +
                "that, continue the conversation naturally, answering the learner's questions. Do not end " +
                "your reply with a question or an invitation to keep talking; instead, the directions the " +
                "learner could explore next are offered separately as suggested topics."
    }
}
