package com.copiosa.domain.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One thing the learner typed into the bulk-add box, sent to the model to be turned into a card. */
@Serializable
data class BulkCardRequestItem(
    val id: Int,
    val text: String,
)

/** One drafted card parsed back from the model. [id] echoes the request item. */
@Serializable
data class BulkCardDto(
    val id: Int? = null,
    /** The target-language headword or phrase — the card's front. */
    val term: String = "",
    /** The native-language gloss — the card's back. */
    val translation: String? = null,
    val pos: String? = null,
    /** Noun gender: one of M/F/MF/MPL/FPL, or null when not a gendered noun. */
    val gender: String? = null,
    /** One natural target-language sentence using [term]. */
    val example: String? = null,
)

/**
 * The bulk-add box's text handling and its one model call: splitting what was typed into items,
 * asking for a card per item, and reading the reply.
 *
 * The reply is plain text — there is no JSON mode — so parsing follows the house pattern of locating
 * the JSON array inside whatever prose or code fence surrounds it and never throwing.
 */
object BulkCards {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The separate items in [raw]: split on commas and line breaks, trimmed, blanks dropped, and
     * repeats (ignoring case) collapsed onto their first appearance so the order typed is the order
     * shown.
     */
    fun splitInput(raw: String): List<String> =
        raw.split(',', '\n', '\r')
            .map { it.trim().replace(WHITESPACE, " ") }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    fun encodeRequest(items: List<BulkCardRequestItem>): String =
        json.encodeToString(ListSerializer(BulkCardRequestItem.serializer()), items)

    /** Best-effort decode of the model reply; empty on total failure. */
    fun parse(raw: String): List<BulkCardDto> {
        val text = raw.trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start !in 0 until end) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(BulkCardDto.serializer()), text.substring(start, end + 1))
        }.getOrDefault(emptyList())
    }

    /**
     * Lines the reply up with the request: by echoed id where the model echoed any, else by position
     * when the counts agree. An item with no usable term comes back null rather than as a blank card —
     * the caller reports those instead of offering them.
     */
    fun align(items: List<BulkCardRequestItem>, dtos: List<BulkCardDto>): List<BulkCardDto?> {
        val usable = dtos.filter { it.term.isNotBlank() }
        val byId = usable.mapNotNull { dto -> dto.id?.let { it to dto } }.toMap()
        return when {
            items.any { byId.containsKey(it.id) } -> items.map { byId[it.id] }
            dtos.size == items.size -> dtos.map { dto -> dto.takeIf { it.term.isNotBlank() } }
            else -> items.map { null }
        }
    }

    /**
     * Asks for one card per item.
     *
     * The learner types in their own language, but a word they heard and half-remember is as likely
     * to arrive in the target one, so the prompt takes either rather than translating a target word
     * into itself. The headword rules mirror how the app saves a tapped word — bare lemma, no
     * article — because the article is drawn from `gender` and a stored "el mantel" would neither
     * highlight in a text nor match the copy already in the deck.
     */
    fun systemPrompt(
        country: String,
        targetLanguage: String,
        nativeLanguage: String,
        level: String,
    ): String = buildString {
        append("You are a $country $targetLanguage teacher making flashcards for a $level ")
        append("$nativeLanguage-speaking learner.\n\n")
        append("The learner sends a JSON array of items, each {\"id\": <number>, \"text\": <a word or ")
        append("phrase>}. The text is normally in $nativeLanguage; make a card for the most natural, ")
        append("most common $targetLanguage equivalent, as it is said in $country. If the text is ")
        append("already in $targetLanguage, keep it as the term and supply the $nativeLanguage meaning.\n\n")
        append("Reply with ONLY a JSON array (no prose, no code fences) of the same length, each item:\n")
        append("{\"id\": <same id>, \"term\": <the $targetLanguage word or phrase>, \"translation\": ")
        append("<a short $nativeLanguage gloss for the back of the card>, \"pos\": <one of \"noun\",")
        append("\"verb\",\"adjective\",\"adverb\",\"phrase\",\"expression\",\"other\">, \"gender\": ")
        append("<for a noun, one of \"M\",\"F\",\"MF\" (common/epicene),\"MPL\",\"FPL\"; else null>, ")
        append("\"example\": <one short, natural $targetLanguage sentence using the term>}.\n\n")
        append("Rules, all required:\n")
        append("- \"term\" is the dictionary form: nouns singular and WITHOUT an article, verbs in the ")
        append("infinitive, adjectives masculine singular; lower case unless it is a proper noun.\n")
        append("- \"translation\" is normally the learner's own wording, tidied. When their word is ")
        append("ambiguous, pick the most common sense and say which in a brief parenthetical.\n")
        append("- Keep \"translation\" under about 60 characters.\n")
        append("- One card per item, in the same order. Always echo each id exactly.")
    }

    private val WHITESPACE = Regex("\\s+")
}
