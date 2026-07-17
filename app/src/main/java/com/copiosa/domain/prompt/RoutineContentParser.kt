package com.copiosa.domain.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses the single consolidated routine-content response into per-slot DTOs keyed by the requested
 * id. Forgiving in the same spirit as [StructuredReplyParser]: strips code fences / stray prose,
 * isolates the outer JSON object, tolerates unknown keys and light JSON garbling, and never throws —
 * an unparseable response yields an empty map, leaving the synthesizer to degrade gracefully.
 */
object RoutineContentParser {

    @Serializable
    data class ItemDto(
        val sentence: String = "",
        val options: List<String> = emptyList(),
        val answerIndex: Int = -1,
        val explanation: String? = null,
    )

    @Serializable
    data class WordDto(
        val form: String = "",
        val falseFriend: Boolean = false,
        val gloss: String? = null,
    )

    @Serializable
    data class ActivityDto(
        val id: Int = -1,
        val type: String? = null,
        val category: String? = null,
        val items: List<ItemDto> = emptyList(),
        val title: String? = null,
        val paragraphs: List<String> = emptyList(),
        val prompt: String? = null,
        val statements: List<String> = emptyList(),
        val sentences: List<String> = emptyList(),
        /** cognate_hunt: the word set (true cognates + a few false friends). */
        val words: List<WordDto> = emptyList(),
        /** serial_story: a compact recap the next chapter continues from. */
        val storySummary: String? = null,
    )

    @Serializable
    private data class ConsolidatedDto(val activities: List<ActivityDto> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Slot id -> its generated content DTO. Empty when nothing usable could be parsed. */
    fun parse(raw: String): Map<Int, ActivityDto> {
        val obj = isolateObject(raw) ?: return emptyMap()
        val dto = listOf(obj, repairJson(obj)).firstNotNullOfOrNull { tryDecode(it) } ?: return emptyMap()
        return dto.activities.filter { it.id >= 0 }.associateBy { it.id }
    }

    private fun isolateObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    private fun tryDecode(jsonText: String): ConsolidatedDto? =
        runCatching { json.decodeFromString(ConsolidatedDto.serializer(), jsonText) }
            .getOrNull()
            ?.takeIf { it.activities.isNotEmpty() }

    private fun repairJson(jsonText: String): String =
        jsonText
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
            .replace(Regex(""",(\s*[}\]])"""), "$1")
}
