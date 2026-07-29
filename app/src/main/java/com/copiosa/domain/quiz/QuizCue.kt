package com.copiosa.domain.quiz

/**
 * How a cue type produces its questions. [ALGORITHMIC] cues (numbers, times) sample values and
 * re-derive confusable distractors on the fly at session time; [PHRASE] cues (restaurant, …) are
 * whole LLM-generated sentences whose answer options are pre-seeded and stored on the cue row.
 */
enum class CueKind { ALGORITHMIC, PHRASE }

/**
 * One kind of rapid-fire listening cue. The [slug] is the stable proficiency-bucket key shared by
 * standalone sessions and routine attempts (prefixed "rf_" so it can't collide with the contrast
 * category slugs living in the same table); [label] is the human-readable name for config UI;
 * [kind] selects the seeding/session path (see [CueKind]). Persisted by [name] in quiz_cues rows, so
 * renames must stay backward-compatible.
 */
enum class CueType(val slug: String, val label: String, val kind: CueKind) {
    NUMBER("rf_numbers", "Numbers", CueKind.ALGORITHMIC),
    TIME("rf_times", "Times", CueKind.ALGORITHMIC),
    RESTAURANT("rf_restaurant", "Restaurant", CueKind.PHRASE),
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

    /**
     * A phrase cue's identity. Phrases have no natural ordering, so [sortKey] is a constant and the
     * session builder shuffles the options rather than sorting them. [id] is a stable hash of the
     * phrase text, used only for pool identity/dedup — the actual answer options live in stored
     * [PhraseOptions], not here.
     */
    data class Phrase(val id: String) : CueValue {
        override fun key(): String = id
        override fun sortKey(): Int = 0
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
            // Phrase cues never route through the algorithmic distractor path, so parse just round-trips
            // the id for completeness (the session builder branches before calling this).
            CueType.RESTAURANT -> Phrase(key)
        }

        private fun pad(n: Int): String = n.toString().padStart(2, '0')
    }
}

/**
 * A fully-seeded cue ready for TTS rendering. [spokenText] is what the TTS engine reads — ALWAYS
 * words for algorithmic cues ("las seis y cuarto"), never digits, or the target-language sentence for
 * phrase cues. [displayText] is the digit form the answer options render ("6:15") for algorithmic
 * cues, or the correct native translation for phrase cues. [options] is set only for phrase cues (the
 * pre-seeded correct + distractor answers); null for algorithmic cues, which derive distractors live.
 */
data class GeneratedCue(
    val type: CueType,
    val value: CueValue,
    val spokenText: String,
    val displayText: String,
    val options: PhraseOptions? = null,
)
