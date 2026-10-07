package com.copiosa.domain.routine

import com.copiosa.domain.language.LanguageFeature
import com.copiosa.domain.language.LanguageProfiles

/**
 * A contrast category for [ActivityType.CONTRAST_CLOZE] — the item bank swapped behind one reusable
 * UI skin. The [slug] is the stable key used in proficiency buckets and serialized plans, so it is
 * frozen; append only.
 *
 * This is the catalog across every language, not one language's set. Which of these a learner
 * actually meets is `LanguageProfile.contrasts`. A category may be shared — ser/estar is a real
 * contrast in Spanish and Portuguese alike — because proficiency rows are unique on
 * (bucketKey, targetLanguage), so the two languages' mastery never mixes.
 *
 * The [label] is interpolated verbatim into the generation request alongside the target-language
 * name, so it must read as the name of a grammar point rather than as a Spanish example.
 */
enum class ContrastCategory(val slug: String, val label: String) {
    SER_ESTAR("ser_estar", "ser vs estar"),
    POR_PARA("por_para", "por vs para"),
    PRETERITE_IMPERFECT("preterite_imperfect", "preterite vs imperfect"),
    SUBJUNCTIVE_TRIGGER("subjunctive_trigger", "subjunctive triggers"),
    GENDER_AGREEMENT("gender_agreement", "gender agreement"),

    /** Portuguese: *se fizer* vs *fazer*, where a Spanish speaker's instinct is reliably wrong. */
    FUTURE_SUBJUNCTIVE("future_subjunctive", "future subjunctive vs infinitive"),

    /** Portuguese: *para fazermos* vs *para que façamos*. */
    PERSONAL_INFINITIVE("personal_infinitive", "personal infinitive vs subjunctive"),

    /** Portuguese: de + o = do, em + a = na, a + a = à. */
    PREP_CONTRACTION("prep_contraction", "preposition + article contractions"),
    ;

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
    /**
     * Null = every target language; otherwise the BCP-47 tags this activity supports. Use this only
     * where the constraint really is a fixed list of languages (rapid-fire, gated by which languages
     * `WordNumberSpeller` can spell). Anything gated on *grammar data* declares [requires] instead,
     * so adding a language doesn't mean editing every activity.
     */
    val supportedLanguages: Set<String>?,
    val needsLlm: Boolean,
    val needsTts: Boolean = false,
    val needsDict: Boolean = false,
    /**
     * Activation tier. A routine type draws freely up to its [RoutineType.maxArousal] and admits only
     * a small quota from above it, confined to one band — see [RoutineType].
     */
    val arousalLevel: ArousalLevel = ArousalLevel.MEDIUM,
    /**
     * Null = every routine type; otherwise the routine types this activity may appear in. The escape
     * hatch for an activity that only makes sense in one kind of session, so the gate lives on the
     * activity rather than as a special case in the planner.
     */
    val routineTypes: Set<RoutineType>? = null,
    /** What this activity needs the target language's [LanguageProfile] to provide. */
    val requires: Set<LanguageFeature> = emptySet(),
) {
    /**
     * Whether this activity can run for a target language: its tag list must admit it (when it has
     * one), and the language's profile must supply everything in [requires]. A language with no
     * profile at all satisfies nothing, which is what keeps the grammar activities out of a routine
     * for a language whose grammar we haven't described.
     */
    fun supports(language: String): Boolean {
        // Matched on the base tag: a list is per language, not per region, so an "es-MX" profile has
        // to satisfy a tag list that says "es" — the same way LanguageProfiles resolves.
        if (supportedLanguages?.contains(language.substringBefore('-').lowercase()) == false) return false
        if (requires.isEmpty()) return true
        val profile = LanguageProfiles.forTag(language) ?: return false
        return requires.all { profile.supports(it) }
    }

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
