package com.copiosa.domain.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One word the model is asked to write a practice sentence for. */
@Serializable
data class ClozeRequestItem(
    val id: Long,
    val term: String,
    val pos: String? = null,
    /** The card's gloss, so the sentence uses the sense the learner is actually studying. */
    val meaning: String? = null,
)

/** One generated sentence parsed back from the model. [id] echoes the request item. */
@Serializable
data class ClozeGenerationDto(
    val id: Long? = null,
    /** The sentence in the target language, with [term] spelled out (no blank, no markup). */
    val sentence: String = "",
    /** The exact surface form of the requested word as it appears in [sentence]. */
    val form: String? = null,
    /** The sentence in the learner's own language. */
    val translation: String? = null,
)

/** One item put to the model to adjudicate: does more than one option fit? */
@Serializable
data class ClozeVerdictRequestItem(
    val id: Long,
    val sentence: String,
    val answer: String,
    val options: List<String>,
)

/**
 * The model's verdict on one item. [alsoFit] lists options other than the answer that would also
 * produce a natural, correct sentence; [underdetermined] says the *sentence* constrains nothing, so
 * no set of options could rescue it.
 */
@Serializable
data class ClozeVerdictDto(
    val id: Long? = null,
    val alsoFit: List<String> = emptyList(),
    val underdetermined: Boolean = false,
)

/** One banked sentence sent off for a translation it was stored without. */
@Serializable
data class ClozeTranslationRequestItem(
    val id: Long,
    /** The sentence in the target language, blank filled in — the model translates real prose. */
    val sentence: String,
)

/** The learner's-language rendering of one sentence. [id] echoes the request item. */
@Serializable
data class ClozeTranslationDto(
    val id: Long? = null,
    val translation: String = "",
)

/**
 * Prompts and forgiving parsing for the three things the cloze bank asks a model to do: write a
 * sentence for a word nothing local could serve, judge whether an item has more than one right
 * answer, and translate a sentence that was banked without one.
 *
 * Both are plain-text replies — there is no JSON mode here — so parsing follows the house pattern of
 * locating the JSON array inside whatever prose or code fence surrounds it and never throwing.
 */
object ClozeGenerationParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encodeGenerationRequest(items: List<ClozeRequestItem>): String =
        json.encodeToString(ListSerializer(ClozeRequestItem.serializer()), items)

    fun encodeVerdictRequest(items: List<ClozeVerdictRequestItem>): String =
        json.encodeToString(ListSerializer(ClozeVerdictRequestItem.serializer()), items)

    fun encodeTranslationRequest(items: List<ClozeTranslationRequestItem>): String =
        json.encodeToString(ListSerializer(ClozeTranslationRequestItem.serializer()), items)

    fun parseGeneration(raw: String): List<ClozeGenerationDto> =
        decode(raw, ListSerializer(ClozeGenerationDto.serializer()))

    fun parseVerdicts(raw: String): List<ClozeVerdictDto> =
        decode(raw, ListSerializer(ClozeVerdictDto.serializer()))

    fun parseTranslations(raw: String): List<ClozeTranslationDto> =
        decode(raw, ListSerializer(ClozeTranslationDto.serializer()))

    private fun <T> decode(raw: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> {
        val text = raw.trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start !in 0 until end) return emptyList()
        return runCatching { json.decodeFromString(serializer, text.substring(start, end + 1)) }
            .getOrDefault(emptyList())
    }
}

/** The system prompts, kept beside the DTOs they describe. */
object ClozePrompts {

    /**
     * Asks for one sentence per word.
     *
     * The constraints mirror `ClozeGate` deliberately: anything the gate would reject is a wasted
     * generation, and the gate runs on the result regardless, so asking for what will pass is the
     * cheapest way to raise the yield.
     */
    fun generation(targetLanguage: String, nativeLanguage: String): String = buildString {
        append("You write short example sentences for a language learner studying $targetLanguage. ")
        append("Their own language is $nativeLanguage.\n\n")
        append("For each item you are given, write ONE natural sentence in $targetLanguage that uses ")
        append("the given word in the given sense.\n\n")
        append("Rules, all required:\n")
        append("- Between 6 and 12 words.\n")
        append("- The word must appear EXACTLY ONCE, and must NOT be the first word of the sentence.\n")
        append("- The sentence must make the word's meaning inferable — someone who knows the rest of ")
        append("the sentence should be able to work out that this word, and no other, belongs there.\n")
        append("- Use common, everyday vocabulary for everything else in the sentence.\n")
        append("- No names of people or places, no quotation marks, no markup.\n")
        append("- \"form\" must be the word exactly as it appears in your sentence (it may be inflected).\n\n")
        append("Output ONLY a JSON array, no prose and no code fences: ")
        append("[{\"id\":<int>,\"sentence\":\"…\",\"form\":\"…\",\"translation\":\"…\"}]")
    }

    /**
     * Asks whether an item has more than one right answer.
     *
     * Phrased to make "yes, another one fits" the easy answer rather than the awkward one: the whole
     * value of this pass is catching items the local checks let through, and a model that defaults to
     * approving would add nothing.
     */
    fun verdict(targetLanguage: String): String = buildString {
        append("You are checking multiple-choice fill-in-the-blank questions in $targetLanguage ")
        append("for a language learner.\n\n")
        append("Each item has a sentence with one blank, the intended answer, and the options shown. ")
        append("Your job is to catch questions that have more than one right answer.\n\n")
        append("For each item report:\n")
        append("- \"alsoFit\": every option OTHER than the intended answer that would also produce a ")
        append("natural, grammatical, meaningful sentence. Be strict: if a fluent speaker could accept ")
        append("it, it also fits. Usually this is empty.\n")
        append("- \"underdetermined\": true when the sentence gives so little context that no set of ")
        append("options could have a single right answer.\n\n")
        append("Output ONLY a JSON array, no prose and no code fences: ")
        append("[{\"id\":<int>,\"alsoFit\":[\"…\"],\"underdetermined\":false}]")
    }

    /**
     * Asks for a plain rendering of each sentence.
     *
     * The translation is shown *after* the learner has answered, as a check on what the sentence
     * meant — so it must read naturally rather than gloss word for word, and it must not turn into a
     * commentary. The blank is filled in before sending: a model handed "{{c1::…}}" translates the
     * markup as often as the sentence.
     */
    fun translation(targetLanguage: String, nativeLanguage: String): String = buildString {
        append("You translate $targetLanguage sentences into $nativeLanguage for a language learner.\n\n")
        append("For each item, translate the sentence into natural $nativeLanguage.\n\n")
        append("Rules, all required:\n")
        append("- Translate the whole sentence and nothing else: no notes, no alternatives, ")
        append("no explanation of grammar or word choice.\n")
        append("- Write what a fluent speaker would actually say, not a word-for-word gloss.\n")
        append("- Keep it one sentence, and keep the register of the original.\n")
        append("- No quotation marks around the translation, and no markup.\n\n")
        append("Output ONLY a JSON array, no prose and no code fences: ")
        append("[{\"id\":<int>,\"translation\":\"…\"}]")
    }
}
