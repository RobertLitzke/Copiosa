package com.copiosa.domain.prompt

import com.copiosa.domain.model.BookDirection
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
     * Nothing here changes from one turn of a chat to the next, and that is deliberate: providers
     * discount a request whose opening matches an earlier one, and the system prompt is the opening.
     * What does change every turn — which saved words to weave in, which of the last reply's words
     * to recur — travels in [turnNote] at the far end of the request instead, so the prompt and the
     * whole history ahead of it stay a reusable prefix.
     *
     * [includeVocabReport] asks for the used/unused report on the priority words a [turnNote]
     * supplies. The fixed structured-output block is always appended so every reply carries metadata.
     */
    fun buildConversationPrompt(
        core: String,
        customInstructions: String,
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
        subject: String,
        includeVocabReport: Boolean = false,
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
            TURN_NOTE_RULE +
            structuredOutput(
                distinctiveInstruction = DISTINCTIVE_FROM_REPLY,
                includeTopics = includeTopics,
                includeVocabReport = includeVocabReport,
                avoidTrailingQuestion = true,
                glossLangName = languageName(nativeLang),
                includeTitle = includeTitle,
                titleLangName = languageName(targetLang),
            )
    }

    /**
     * The per-turn half of a chat's guidance, appended to the learner's latest message rather than
     * placed in the system prompt (see [buildConversationPrompt] for why). [vocabToUse] is a sample
     * of the learner's saved words (front to back/translation) to weave in where natural;
     * [reuseWords] are distinctive words from the previous reply to recur. Empty when there is
     * nothing to say, so a learner with no saved words sends their message untouched.
     *
     * It is added to the request only — the stored message stays exactly what the learner typed,
     * which is also what later turns send as history.
     */
    fun turnNote(
        vocabToUse: List<Pair<String, String>> = emptyList(),
        reuseWords: List<String> = emptyList(),
    ): String {
        val body = (vocabToUseSection(vocabToUse) + reuseWordsSection(reuseWords)).trim()
        return if (body.isEmpty()) "" else TURN_NOTE_OPEN + body + TURN_NOTE_CLOSE
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
            "or phrase>, \"context\": <sentence it appeared in, or null>, \"pos\": <its part of speech, " +
            "or null>}.\n" +
            "Reply with ONLY a JSON array (no prose, no code fences) of the same length, each item:\n" +
            "{\"id\": <same id>, \"pos\": <one of \"noun\",\"verb\",\"adjective\",\"adverb\",\"phrase\"," +
            "\"expression\",\"grammar\",\"other\">, \"cefr\": <one of \"A1\",\"A2\",\"B1\",\"B2\",\"C1\",\"C2\">, " +
            "\"gender\": <for a noun, one of \"M\",\"F\",\"MF\" (common/epicene),\"MPL\",\"FPL\"; else null>, " +
            "\"region\": <a short region slug like \"mx\",\"spain\",\"latam\" if the term is region-specific; " +
            "else null>, " +
            "\"translation\": <a short $native gloss for the back of a flashcard>, " +
            "\"explanation\": <the meaning written in $native>, \"example\": <one natural $target sentence " +
            "using the term>}.\n" +
            "Keep \"translation\" under about 60 characters, but make it *distinguishing*: when the " +
            "obvious one-word gloss is shared with other common $target words, add the brief cue that " +
            "tells them apart — the sense, the typical use, or a parenthetical — so two such words never " +
            "carry the same back. Give the most common sense first, separating further senses with a " +
            "semicolon.\n" +
            "Write the \"explanation\" in $native, in a colloquial, friendly tone (2–4 sentences), " +
            "covering nuance and close synonyms; the informal take matters more than a dictionary " +
            "definition. Use simple Markdown (e.g. **bold**, *italics*, '- ' bullets). Always echo each id exactly."
    }

    /**
     * System prompt for the vocabulary screen's bulk add: the learner sends a JSON array of words or
     * phrases, normally in their own language, and the model drafts one card for each. The wording
     * lives beside the DTOs it describes, in [BulkCards].
     */
    fun bulkCardSystemPrompt(
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
    ): String = BulkCards.systemPrompt(
        country = country,
        targetLanguage = languageName(targetLang),
        nativeLanguage = languageName(nativeLang),
        level = levelDescriptor(level),
    )

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
     * System prompt for translating one section (~2 pages) of an imported book. The section arrives
     * as `[[P<n>]]`-marked paragraphs the model must echo, so original and translated paragraphs
     * stay aligned row-for-row (the reader's toggle depends on it). No structured-metadata block —
     * the marker contract is the whole output format.
     *
     * [BookDirection.FORWARD] is the same level-adapted contract as [textTranslationSystemPrompt].
     * [BookDirection.REVERSE] instead translates the target-language original *into* the learner's
     * own language as a gloss, so [level] and [country] are unused — nothing grades a native-language
     * crutch, and simplifying it would only blunt the original it sits beside.
     *
     * The previous section rides along as an earlier user/assistant exchange (see
     * BookRepository.translateSection) purely for continuity — names and recurring vocabulary keep
     * the translations they already got — hence the "translate only the latest message" guard.
     */
    fun bookSectionSystemPrompt(
        country: String,
        targetLang: String,
        nativeLang: String,
        level: Cefr,
        direction: BookDirection,
        bookTitle: String,
        part: Int,
        totalParts: Int,
    ): String {
        val target = languageName(targetLang)
        val native = languageName(nativeLang)
        val intro = when (direction) {
            BookDirection.FORWARD ->
                "You are a $country $target teacher preparing reading material for a " +
                    "${levelDescriptor(level)} learner. You are translating the book \"$bookTitle\" into " +
                    "$target at approximately ${level.name} level, section by section; this is section $part " +
                    "of $totalParts. Simplifying vocabulary and grammar to that level is the priority, but " +
                    "preserve the original's tone, voice and style as far as the level allows, and continue " +
                    "seamlessly from earlier sections — keep the same translations for names and recurring " +
                    "words. If the text is already in $target, adapt it to ${level.name} the same way."
            // Nothing is graded here: the output is the learner's own language, and it exists only as
            // a crutch beside the $target original they are actually reading. Hence no level, no
            // teacher persona, and meaning over polish.
            BookDirection.REVERSE ->
                "You are translating the book \"$bookTitle\" from $target into $native, section by " +
                    "section; this is section $part of $totalParts. The reader is a learner reading the " +
                    "$target original, and your translation sits beside it as a reading aid — so render " +
                    "the meaning faithfully and idiomatically, preserving the original's tone, voice and " +
                    "style. Do not simplify, summarise, explain or annotate. Continue seamlessly from " +
                    "earlier sections — keep the same translations for names and recurring words."
        }
        return intro + "\n\n" + MARKER_CONTRACT
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
        // As in [buildConversationPrompt]: the words themselves arrive in a [turnNote].
        includeVocabReport: Boolean = false,
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
            TURN_NOTE_RULE +
            structuredOutput(
                distinctiveInstruction = DISTINCTIVE_FROM_REPLY,
                includeTopics = includeTopics,
                includeVocabReport = includeVocabReport,
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
        // Opt-in with a soft floor (capped at however many words we actually supplied): usage is the
        // default, not optional flavour, but the anti-distortion guard is kept so prose stays natural.
        val target = minOf(VOCAB_MIN_TO_USE, words.size)
        return "\n\nPRIORITY VOCABULARY FOR THIS REPLY — the learner is actively studying these saved " +
            "words: $list. Work as many of them as read naturally into your reply — aim for at least " +
            "$target — choosing examples, sentences, or phrasing that let them fit. Prefer rephrasing to " +
            "include a word over leaving it out, but never distort your meaning or force a word that " +
            "truly cannot fit."
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
                        "always provide some, even after a short follow-up reply. Each one must be " +
                        "SPECIFIC to this exchange: name a particular person, place, work, event, " +
                        "practice or detail that came up in what you just wrote, or something " +
                        "directly adjacent to one — a suggestion nobody could have made without " +
                        "having read this conversation. Two tests before you write one down: it " +
                        "would NOT fit unchanged in another conversation about this general subject, " +
                        "and it has not already been covered here. So no bare category headings like " +
                        "\"the history\", \"the culture\", \"famous people\", \"traditional food\", " +
                        "\"daily life\" — narrow into something you actually mentioned, or step " +
                        "sideways to its neighbour, rather than widening back out to the theme. " +
                        "Each is an object " +
                        "{\"phrase\": <the subject, 1-5 words — a concrete, named thing wherever one " +
                        "will do>, " +
                        "\"gloss\": <a brief note in $glossLangName, at most ~12 words, saying what the " +
                        "phrase refers to so it stands on its own outside this chat>}]",
                )
            }
            add("\"distinctive\": [$distinctiveInstruction]")
            if (includeVocabReport) {
                add(
                    "\"vocabUsed\": [the priority saved words you actually worked into this reply], " +
                        "\"vocabUnused\": [any priority saved words you genuinely could not fit]",
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
        /**
         * Soft floor for how many supplied saved words a reply should work in (capped at however many
         * were actually supplied). Not enforced on the model — usage is measured after the fact
         * (see VocabUsageDetector) — but it turns the vocab section from opt-out into a concrete target.
         */
        const val VOCAB_MIN_TO_USE = 4

        /**
         * The `[[P<n>]]` output contract for book sections, shared by both translation directions —
         * it is what keeps original and translated paragraphs on the same row, which the reader's
         * language toggle and the table of contents both depend on.
         */
        private const val MARKER_CONTRACT =
            "The section is split into numbered paragraphs, each preceded by a marker line like " +
                "[[P214]]. Translate every paragraph. Your output MUST contain exactly the same markers, " +
                "in the same order, each on its own line, followed by that paragraph's translation. " +
                "Never merge, split, drop, add, or reorder paragraphs — one output paragraph per marker, " +
                "even for one-word paragraphs and ## headings (keep the ##). Keep *italic* and **bold** " +
                "where the original has them. Some paragraphs carry structure, which must survive " +
                "intact: keep a list item's leading spaces and its \"- \" or \"1. \" marker; a paragraph " +
                "may be a table written with | pipes, and must come back with the same number of rows " +
                "and columns, the same | layout and its |---| separator row, with only the cell text " +
                "translated; keep footnote markers like [^3] exactly where they appear in the sentence, " +
                "and keep a \"[^3]: \" prefix at the start of the paragraph that has one. Earlier " +
                "sections may appear in this conversation for context — translate ONLY the paragraphs " +
                "in the latest message. Output nothing before the first marker and nothing after the " +
                "last translation."

        /**
         * The standing explanation of a [turnNote], so the model reads one as direction from the app
         * and not as something the learner said. Fixed text: it is part of the cacheable prefix.
         */
        private const val TURN_NOTE_RULE =
            "\n\nThe learner's latest message may end with a teacher's note in square brackets. The " +
                "app adds it; the learner did not write it and cannot see it. Follow it for that " +
                "reply, and never mention it, quote it or respond to it."

        private const val TURN_NOTE_OPEN = "[Teacher's note, added by the app — not written by the learner. "
        private const val TURN_NOTE_CLOSE = "]"

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
