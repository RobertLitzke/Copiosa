package com.copiosa.domain.quiz

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How spoken clock times are phrased in the pool. */
@Serializable
enum class TimeStyle {
    /** Plain hour + minutes reading ("las seis y quince"). Generated locally, no LLM needed. */
    NUMERIC,

    /** How a native would casually say it ("las seis y cuarto"). Phrased by the LLM at seed time. */
    COLLOQUIAL,

    /** Coin flip per cue — the pool mixes both styles. */
    MIXED,
}

/**
 * The user's pool setup, chosen on the quiz pool screen before generation. Serialized into
 * quiz_pools.configJson so the management screen can show (and re-offer) the pool's settings and so
 * session assembly can re-derive distractors with the same range constraints. One pool holds a single
 * [cueType] — Numbers and Times are independent top-level activities with their own pools — so only the
 * setting relevant to that type ([numberMax] for numbers, [timeStyle] for times) is meaningful.
 */
@Serializable
data class QuizPoolConfig(
    val langCode: String,
    val cueType: CueType = CueType.NUMBER,
    /** Numbers are drawn from 1..[numberMax]. */
    val numberMax: Int = 100,
    val timeStyle: TimeStyle = TimeStyle.MIXED,
    val poolSize: Int = 250,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(stored: String?): QuizPoolConfig? {
            if (stored.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), stored) }.getOrNull()
        }
    }
}
