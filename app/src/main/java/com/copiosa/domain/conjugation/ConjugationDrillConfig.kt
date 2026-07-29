package com.copiosa.domain.conjugation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The learner's drill setup, chosen on the drill screen. Serialized into settings so the screen can
 * re-offer it and so a session can be rebuilt with the same constraints.
 */
@Serializable
data class ConjugationDrillConfig(
    val itemCount: Int = DEFAULT_ITEMS,
    val tenses: Set<Tense> = DEFAULT_TENSES,
    val directions: Set<DrillDirection> = DEFAULT_DIRECTIONS,
    /**
     * How hard to bias toward cells that actually deviate. 0.0 draws purely on frequency; 1.0 draws
     * irregulars only. The default leans irregular because that is what the drill is for.
     */
    val irregularEmphasis: Double = 0.6,
    /** Off outside Spain: most learners never need vosotros, and it doubles the plural load. */
    val includeVosotros: Boolean = false,
) {
    /** The persons in play, in paradigm order. */
    val persons: List<Person>
        get() = Person.entries.filter { includeVosotros || it != Person.VOSOTROS }

    /** Empty selections would yield an empty session; fall back rather than show nothing. */
    fun sanitized(): ConjugationDrillConfig = copy(
        itemCount = itemCount.coerceIn(MIN_ITEMS, MAX_ITEMS),
        tenses = tenses.ifEmpty { DEFAULT_TENSES },
        directions = directions.ifEmpty { DEFAULT_DIRECTIONS },
        irregularEmphasis = irregularEmphasis.coerceIn(0.0, 1.0),
    )

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MIN_ITEMS = 5
        const val MAX_ITEMS = 50
        const val DEFAULT_ITEMS = 20

        val ITEM_COUNTS = listOf(10, 20, 30, 50)

        val DEFAULT_TENSES = setOf(Tense.PRESENT, Tense.PRETERITE)
        val DEFAULT_DIRECTIONS = setOf(DrillDirection.PRODUCE, DrillDirection.DECODE)

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(stored: String?): ConjugationDrillConfig? {
            if (stored.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), stored) }.getOrNull()
        }

        /** The default for a profile: vosotros only where it's actually used. */
        fun forCountry(country: String?): ConjugationDrillConfig =
            ConjugationDrillConfig(includeVosotros = country.equals("Spain", ignoreCase = true))
    }
}
