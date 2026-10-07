package com.copiosa.domain.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One vocabulary item sent to the model for enrichment. [pos] is the part of speech already recorded
 * on the card, when there is one: it is the difference between asking about *haber* in the abstract
 * and asking about the auxiliary verb, and near-synonyms are exactly where the bare word is ambiguous.
 */
@Serializable
data class VocabRequestItem(
    val id: Long,
    val term: String,
    val context: String? = null,
    val pos: String? = null,
)

/** One enrichment result parsed back from the model. [id] echoes the request item. */
@Serializable
data class VocabExplanationDto(
    val id: Long? = null,
    val pos: String? = null,
    val cefr: String? = null,
    /**
     * A short card-back gloss that distinguishes the term from its near-synonyms. Only ever applied
     * to a machine-generated card back — see `FlashcardRepository.update`.
     */
    val translation: String? = null,
    val explanation: String = "",
    val example: String? = null,
    /** Noun gender: one of M/F/MF/MPL/FPL, or null when not a gendered noun. */
    val gender: String? = null,
    /** Dialect/region slug (e.g. "mx") when the term is region-specific, else null. */
    val region: String? = null,
)

/**
 * Serializes the request items and parses the model's reply. The reply is plain text (no JSON
 * mode), so parsing is deliberately forgiving: it locates the JSON array/object inside any
 * surrounding prose or ```` ``` ```` fences before decoding.
 */
object VocabExplanationParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val listSerializer = ListSerializer(VocabExplanationDto.serializer())

    /** The user-message payload: a JSON array of [VocabRequestItem]. */
    fun encodeRequest(items: List<VocabRequestItem>): String =
        json.encodeToString(ListSerializer(VocabRequestItem.serializer()), items)

    /** Best-effort decode of the model reply into a list of explanations; empty on total failure. */
    fun parse(raw: String): List<VocabExplanationDto> {
        val text = raw.trim()

        val arrStart = text.indexOf('[')
        val arrEnd = text.lastIndexOf(']')
        if (arrStart in 0 until arrEnd) {
            runCatching { json.decodeFromString(listSerializer, text.substring(arrStart, arrEnd + 1)) }
                .getOrNull()?.let { return it }
        }

        val objStart = text.indexOf('{')
        val objEnd = text.lastIndexOf('}')
        if (objStart in 0 until objEnd) {
            runCatching { json.decodeFromString(VocabExplanationDto.serializer(), text.substring(objStart, objEnd + 1)) }
                .getOrNull()?.let { return listOf(it) }
        }

        return emptyList()
    }
}
