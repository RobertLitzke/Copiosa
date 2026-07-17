package com.copiosa.domain.quiz

/**
 * One kind of rapid-fire listening cue. The [slug] is the stable proficiency-bucket key shared by
 * standalone sessions and routine attempts (prefixed "rf_" so it can't collide with the contrast
 * category slugs living in the same table); [label] is the human-readable name for config UI.
 * Persisted by [name] in quiz_cues rows, so renames must stay backward-compatible.
 */
enum class CueType(val slug: String, val label: String) {
    NUMBER("rf_numbers", "Numbers"),
    TIME("rf_times", "Times"),
}

/**
 * The value a cue speaks. [key] is the canonical string persisted in quiz_cues.valueKey ("73" /
 * "06:15") — stable across sessions so distractors can be re-derived from a stored cue.
 */
sealed interface CueValue {
    fun key(): String

    /** A natural ordering within a cue type, so answer options can be shown sorted, not shuffled. */
    fun sortKey(): Int

    data class Number(val value: Int) : CueValue {
        override fun key(): String = value.toString()
        override fun sortKey(): Int = value
    }

    data class Time(val hour: Int, val minute: Int) : CueValue {
        override fun key(): String = "${pad(hour)}:${pad(minute)}"
        override fun sortKey(): Int = hour * 60 + minute
    }

    companion object {
        fun parse(type: CueType, key: String): CueValue? = when (type) {
            CueType.NUMBER -> key.toIntOrNull()?.let { Number(it) }
            CueType.TIME -> {
                val parts = key.split(':')
                val h = parts.getOrNull(0)?.toIntOrNull()
                val m = parts.getOrNull(1)?.toIntOrNull()
                if (h != null && m != null) Time(h, m) else null
            }
        }

        private fun pad(n: Int): String = n.toString().padStart(2, '0')
    }
}

/**
 * A fully-seeded cue ready for TTS rendering. [spokenText] is ALWAYS words ("las seis y cuarto"),
 * never digits — the TTS engine is never asked to normalize "6:15"; [displayText] is the digit form
 * the answer options render ("6:15").
 */
data class GeneratedCue(
    val type: CueType,
    val value: CueValue,
    val spokenText: String,
    val displayText: String,
)
