package com.copiosa.domain.prompt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A suggested next-conversation subject plus a short self-contained [gloss] explaining what the
 * (often cryptic, 1-5 word) [phrase] refers to. The gloss lets a topic make sense outside the chat
 * it came from — it's stored on [com.copiosa.data.local.TopicEntity] to seed routine content.
 *
 * Serialized with a tolerant codec: a bare JSON string decodes to a glossless suggestion (older
 * stored rows, and weaker models that ignore the object shape), while an object accepts a few field
 * aliases. This keeps historical metadata readable after the field changed from a plain string list.
 */
@Serializable(with = TopicSuggestionSerializer::class)
data class TopicSuggestion(
    val phrase: String,
    val gloss: String? = null,
)

object TopicSuggestionSerializer : KSerializer<TopicSuggestion> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("TopicSuggestion") {
            element<String>("phrase")
            element<String?>("gloss")
        }

    override fun deserialize(decoder: Decoder): TopicSuggestion {
        val input = decoder as? JsonDecoder ?: error("TopicSuggestion requires JSON")
        return when (val el = input.decodeJsonElement()) {
            is JsonPrimitive -> TopicSuggestion(el.contentOrNull.orEmpty().trim())
            is JsonObject -> {
                val phrase = el.str("phrase") ?: el.str("topic") ?: el.str("subject") ?: ""
                val gloss = el.str("gloss") ?: el.str("context") ?: el.str("description") ?: el.str("note")
                TopicSuggestion(phrase.trim(), gloss?.trim()?.ifBlank { null })
            }
            else -> TopicSuggestion("")
        }
    }

    override fun serialize(encoder: Encoder, value: TopicSuggestion) {
        val out = encoder as? JsonEncoder ?: error("TopicSuggestion requires JSON")
        out.encodeJsonElement(
            buildJsonObject {
                put("phrase", value.phrase)
                value.gloss?.let { put("gloss", it) }
            },
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * Structured metadata the model emits *after* its reply, in a trailing JSON block (see
 * [StructuredReplyParser.SENTINEL]). All fields are optional so a partial/malformed block still
 * decodes to whatever the model managed to produce.
 *
 * - [topics]: 1–3 subjects that could continue the conversation, shown as tappable chips and captured
 *   into the topic/interest table; each carries a self-contained gloss (see [TopicSuggestion]).
 * - [distinctive]: 1–5 of the hardest/most distinctive words from the reply, fed back on the next
 *   turn so they recur in fresh contexts.
 * - [vocabUsed] / [vocabUnused]: which of the supplied saved words did / didn't actually appear in the
 *   reply body. The model self-reports these in its metadata block, but the repository overwrites them
 *   with a ground-truth scan (see VocabUsageDetector) before storage, and surfaces them in the
 *   per-reply details sheet.
 * - [vocabSupplied]: the target-word fronts we actually injected into the prompt for this turn — our own
 *   record, set by the repository before storage; the model never emits it. Empty for older replies.
 * - [title]: a short title for the conversation in the target language, requested only on a chat's
 *   opening reply (the list otherwise shows the learner's native-language subject verbatim).
 */
@Serializable
data class ReplyMetadata(
    val topics: List<TopicSuggestion> = emptyList(),
    val distinctive: List<String> = emptyList(),
    val vocabUsed: List<String> = emptyList(),
    val vocabUnused: List<String> = emptyList(),
    val vocabSupplied: List<String> = emptyList(),
    val title: String? = null,
) {
    val isEmpty: Boolean
        get() = topics.isEmpty() && distinctive.isEmpty() && vocabUsed.isEmpty() &&
            vocabUnused.isEmpty() && vocabSupplied.isEmpty() && title.isNullOrBlank()

    /** Just the topic phrases, for callers (e.g. the chip row) that don't need the glosses. */
    val topicPhrases: List<String> get() = topics.map { it.phrase }
}

/**
 * Splits a model reply into its visible body and the trailing [ReplyMetadata] block. The reply text
 * is the priority: parsing is deliberately forgiving (mirrors [VocabExplanationParser]) so a missing
 * or malformed block never costs the user their reply — it just yields empty metadata.
 *
 * Forgiveness matters in both directions: weaker models (notably gemini-2.5-flash-lite) tend to
 * garble the sentinel and lightly mangle the JSON. We strip the block on a best-effort basis so the
 * raw `<<<COPIOSA_META…` marker and its JSON never leak into what the user reads or hears, while
 * still salvaging the metadata when the damage is superficial.
 */
object StructuredReplyParser {
    /** Marks the start of the metadata block; the model writes its reply, this line, then the JSON. */
    const val SENTINEL = "<<<COPIOSA_META>>>"

    /**
     * Tolerant matcher for the sentinel as it actually arrives, not just the canonical [SENTINEL].
     * Weaker models drift the angle-bracket counts, slip in whitespace or newlines, swap the
     * underscore for a space, and vary the casing. We anchor on the distinctive `COPIOSA…META`
     * token (which never occurs in real reply text) and treat the brackets as optional padding, so a
     * garbled marker is still recognised and cut instead of bleeding into the visible reply.
     */
    private val SENTINEL_REGEX = Regex("""<*\s*COPIOSA[\s_]*META\s*>*""", RegexOption.IGNORE_CASE)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns the reply body (everything before the sentinel, trimmed) paired with the decoded
     * metadata. No sentinel, or a tail that won't decode, yields the whole text as the body with
     * empty metadata.
     */
    fun parse(raw: String): Pair<String, ReplyMetadata> {
        val match = SENTINEL_REGEX.find(raw) ?: return raw.trim() to ReplyMetadata()
        val body = raw.substring(0, match.range.first).trim()
        val tail = raw.substring(match.range.last + 1)
        return body to decodeTail(tail)
    }

    /** Encodes metadata for storage; null when there's nothing worth keeping. */
    fun encode(meta: ReplyMetadata): String? =
        if (meta.isEmpty) null else json.encodeToString(ReplyMetadata.serializer(), meta)

    /** Decodes stored metadata; empty on null/blank/garbage so callers never have to guard. */
    fun decode(stored: String?): ReplyMetadata {
        if (stored.isNullOrBlank()) return ReplyMetadata()
        return runCatching { json.decodeFromString(ReplyMetadata.serializer(), stored) }
            .getOrDefault(ReplyMetadata())
            .sanitized()
    }

    /**
     * Best-effort decode of the JSON object in [tail], tolerating code fences or stray prose. We try
     * a straight lenient parse first; if the block is lightly garbled we apply a couple of cheap
     * repairs ([repairJson]) and try once more. Anything still unparseable yields empty metadata —
     * dropping a broken block beats showing it.
     */
    private fun decodeTail(tail: String): ReplyMetadata {
        val start = tail.indexOf('{')
        val end = tail.lastIndexOf('}')
        if (start !in 0 until end) return ReplyMetadata()
        val candidate = tail.substring(start, end + 1)
        // Try the candidate as-is, then a repaired copy, and keep the first decode that actually
        // yields something. An empty result doesn't count: lenient parsing happily "succeeds" on
        // curly-quoted keys by treating them as unknown and dropping them, so a clean-but-empty parse
        // must still fall through to the repair pass.
        return listOf(candidate, repairJson(candidate))
            .firstNotNullOfOrNull { tryDecode(it)?.takeUnless(ReplyMetadata::isEmpty) }
            ?: ReplyMetadata()
    }

    private fun tryDecode(jsonText: String): ReplyMetadata? =
        runCatching { json.decodeFromString(ReplyMetadata.serializer(), jsonText) }.getOrNull()?.sanitized()

    /** Drops topic suggestions whose phrase didn't survive decoding, so they don't count as content. */
    private fun ReplyMetadata.sanitized(): ReplyMetadata {
        val cleaned = topics.filter { it.phrase.isNotBlank() }
        return if (cleaned.size == topics.size) this else copy(topics = cleaned)
    }

    /**
     * Cheap repairs for the light garbling weaker models produce: curly/smart quotes used as string
     * delimiters, and trailing commas before a closing brace or bracket. Only reached after a clean
     * parse already failed, so if a repair makes things worse the caller simply drops the block.
     */
    private fun repairJson(jsonText: String): String =
        jsonText
            .replace('“', '"').replace('”', '"')   // “ ” -> "
            .replace('‘', '\'').replace('’', '\'') // ‘ ’ -> '
            .replace(Regex(""",(\s*[}\]])"""), "$1")          // trailing comma before } or ]
}
