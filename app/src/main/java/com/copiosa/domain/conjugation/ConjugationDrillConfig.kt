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
    /**
     * Whether to drill the language's optional second-person plural ([ConjugationSpec.optionalPerson]).
     * Off outside Spain, and off for Portuguese: most learners never need vosotros or vós, and either
     * doubles the plural load. The field name is the serialized key and so is frozen.
     */
    val includeVosotros: Boolean = false,
) {
    /** The persons in play for [spec], in paradigm order. */
    fun persons(spec: ConjugationSpec): List<Person> =
        spec.persons.filter { includeVosotros || it != spec.optionalPerson }

    /**
     * Empty selections would yield an empty session; fall back rather than show nothing. Tenses the
     * language doesn't have are dropped too, so a config saved under one profile can't ask another for
     * a paradigm it has no endings for.
     */
    fun sanitized(spec: ConjugationSpec): ConjugationDrillConfig = copy(
        itemCount = itemCount.coerceIn(MIN_ITEMS, MAX_ITEMS),
        tenses = tenses.filter { it in spec.tenses }.toSet().ifEmpty { defaultTenses(spec) },
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

        /** Present and preterite where the language has them, else its first two paradigms. */
        fun defaultTenses(spec: ConjugationSpec): Set<Tense> =
            DEFAULT_TENSES.filter { it in spec.tenses }.toSet().ifEmpty { spec.tenses.take(2).toSet() }

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
