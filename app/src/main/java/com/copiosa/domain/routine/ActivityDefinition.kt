package com.copiosa.domain.routine

/**
 * A contrast category for [ActivityType.CONTRAST_CLOZE] — the item bank swapped behind one reusable
 * UI skin. The [slug] is the stable key used in proficiency buckets and serialized plans.
 */
enum class ContrastCategory(val slug: String, val label: String) {
    SER_ESTAR("ser_estar", "ser vs estar"),
    POR_PARA("por_para", "por vs para"),
    PRETERITE_IMPERFECT("preterite_imperfect", "preterite vs imperfect"),
    SUBJUNCTIVE_TRIGGER("subjunctive_trigger", "subjunctive triggers"),
    GENDER_AGREEMENT("gender_agreement", "gender agreement");

    companion object {
        fun fromSlug(slug: String?): ContrastCategory? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * Static metadata for one [ActivityType]: which arousal bands it can fill, how stimulating it is
 * ([arousalWeight], higher = more), its nominal length ([estMinutes]), which languages it supports,
 * and which subsystems it needs. The [RoutinePlanner] reads this to build the arousal curve; the
 * synthesizer reads the `needs*` flags to know what to generate and how to degrade gracefully.
 */
data class ActivityDefinition(
    val type: ActivityType,
    val eligibleBands: Set<ArousalBand>,
    val arousalWeight: Double,
    val estMinutes: Double,
    /** Null = every target language; otherwise the BCP-47 tags this activity supports. */
    val supportedLanguages: Set<String>?,
    val needsLlm: Boolean,
    val needsTts: Boolean = false,
    val needsDict: Boolean = false,
    /**
     * Activation tier. A routine type draws freely up to its [RoutineType.maxArousal] and admits only
     * a small quota from above it, confined to one band — see [RoutineType].
     */
    val arousalLevel: ArousalLevel = ArousalLevel.MEDIUM,
    /** A closer is forced to the very end of its band/routine (the bedtime story). */
    val isCloser: Boolean = false,
) {
    fun supports(language: String): Boolean = supportedLanguages?.contains(language) ?: true

    /**
     * The proficiency-bucket key for this activity: the category slug for the categorized types
     * (a contrast pair like "ser_estar", a rapid-fire cue type like "rf_numbers"), else the type name.
     */
    fun bucketKey(category: String? = null): String =
        if (type in CATEGORIZED_TYPES && category != null) category else type.name

    companion object {
        // The definitions themselves live on each ActivityDescriptor; these delegate to the registry
        // (lazily, so descriptor <-> definition static init can't deadlock) for the many existing
        // call sites that think in terms of the metadata alone.

        /** Types whose proficiency buckets are per-category rather than per-type. */
        private val CATEGORIZED_TYPES: Set<ActivityType> by lazy {
            ActivityRegistry.all.filter { it.statusCategories.isNotEmpty() }.map { it.type }.toSet()
        }

        val ALL: List<ActivityDefinition> by lazy { ActivityRegistry.all.map { it.definition } }

        val BY_TYPE: Map<ActivityType, ActivityDefinition> by lazy { ALL.associateBy { it.type } }

        operator fun get(type: ActivityType): ActivityDefinition = BY_TYPE.getValue(type)

        fun forLanguage(language: String): List<ActivityDefinition> = ALL.filter { it.supports(language) }
    }
}
