package com.copiosa.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One OpenRouter model the learner has configured. [id] is what goes on the wire (`vendor/model`);
 * [name] is an optional nickname, because a roster of raw ids reads as noise once there is more than
 * one of them — "Cheap" and "Good prose" are the distinctions actually being made.
 *
 * All of a profile's models share the one OpenRouter API key: this is a list of *models*, not of
 * accounts.
 */
@Serializable
data class OpenRouterModel(
    val id: String,
    val name: String = "",
) {
    /** What to show for this model: the nickname if there is one, else the raw id. */
    val label: String get() = name.ifBlank { id }
}

/** Codec + invariants for the stored OpenRouter roster (a single JSON string preference). */
object OpenRouterModels {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(OpenRouterModel.serializer())

    fun encode(models: List<OpenRouterModel>): String =
        json.encodeToString(serializer, models)

    /** Null when nothing has been stored yet, so the caller can seed from the single-model setting. */
    fun decode(stored: String?): List<OpenRouterModel>? {
        if (stored.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(serializer, stored) }.getOrNull()
    }

    /**
     * The roster as the rest of the app is entitled to assume it: entries trimmed, blanks and
     * duplicate ids dropped, and [active] present. Keeping the active model in the list is what stops
     * a half-finished edit in Settings from leaving a book or chat pointing at a model the picker no
     * longer offers.
     */
    fun normalize(models: List<OpenRouterModel>, active: String): List<OpenRouterModel> {
        val cleaned = models
            .map { OpenRouterModel(it.id.trim(), it.name.trim()) }
            .filter { it.id.isNotEmpty() }
            .distinctBy { it.id }
        val activeId = active.trim()
        return when {
            activeId.isEmpty() -> cleaned
            cleaned.any { it.id == activeId } -> cleaned
            else -> listOf(OpenRouterModel(activeId)) + cleaned
        }
    }
}
