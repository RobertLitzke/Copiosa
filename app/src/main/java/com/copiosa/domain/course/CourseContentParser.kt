package com.copiosa.domain.course

import com.copiosa.domain.prompt.LenientJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses the single consolidated lesson-content response into per-slot DTOs keyed by the slot index
 * that was requested. Forgiving in exactly the way `RoutineContentParser` is — fences and stray prose
 * stripped, unknown keys tolerated, light garbling repaired — and it never throws: an unparseable
 * response yields an empty map, and the builder falls back to engine drills.
 */
object CourseContentParser {

    @Serializable
    data class ItemDto(
        val sentence: String = "",
        val options: List<String> = emptyList(),
        val answerIndex: Int = -1,
        val explanation: String? = null,
    )

    @Serializable
    data class SlotDto(
        val id: Int = -1,
        val items: List<ItemDto> = emptyList(),
    )

    @Serializable
    private data class ConsolidatedDto(
        val slots: List<SlotDto> = emptyList(),
        /** The end-of-lesson note, when the request asked for one. */
        val feedback: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Slot index -> its generated items. Empty when nothing usable could be parsed. */
    fun parse(raw: String): Map<Int, SlotDto> {
        val dto = decode(raw) ?: return emptyMap()
        return dto.slots.filter { it.id >= 0 }.associateBy { it.id }
    }

    /** The optional end-of-lesson feedback note, or null. */
    fun parseFeedback(raw: String): String? = decode(raw)?.feedback?.takeIf { it.isNotBlank() }

    /**
     * Lenient mode will happily decode a smart-quoted object into an *empty* DTO — the quote
     * characters become part of the key name, and `ignoreUnknownKeys` then discards it — so a
     * decode is only accepted once it has actually yielded content. Without that check the repair
     * pass would never run on the responses that most need it.
     */
    private fun decode(raw: String): ConsolidatedDto? {
        val obj = LenientJson.isolateObject(raw) ?: return null
        return listOf(obj, LenientJson.repair(obj)).firstNotNullOfOrNull { text ->
            runCatching { json.decodeFromString(ConsolidatedDto.serializer(), text) }
                .getOrNull()
                ?.takeIf { it.slots.isNotEmpty() || !it.feedback.isNullOrBlank() }
        }
    }
}
