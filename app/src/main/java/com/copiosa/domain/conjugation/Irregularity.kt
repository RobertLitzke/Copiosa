package com.copiosa.domain.conjugation

/**
 * The coarse grouping of irregularity, and the unit the adaptation engine tracks. These slugs become
 * `activity_proficiency.bucketKey` values and `BucketCategory` slugs, so they are **frozen** — and
 * they carry a `cj_` prefix (mirroring the quiz's `rf_`) so they can never collide with a
 * `ContrastCategory` or `CueType` slug, which `ActivityRegistryTest` asserts globally.
 */
enum class IrregularityFamily(val slug: String, val label: String) {
    REGULAR("cj_regular", "regular"),
    STEM_CHANGE("cj_stem_change", "stem changes"),
    YO_FORM("cj_yo", "irregular yo"),
    STRONG_PRETERITE("cj_strong_pret", "strong preterites"),
    FUTURE_STEM("cj_fut_stem", "irregular future stems"),
    ORTHOGRAPHIC("cj_orth", "spelling changes"),
    SUPPLETIVE("cj_suppletive", "wholly irregular"),
    ;

    companion object {
        fun fromSlug(slug: String?): IrregularityFamily? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * How much of the form a class of irregularity claims once the correct/naive diff has located the
 * deviation. Most classes take the diff as-is; two need to override it.
 */
enum class SpanPolicy {
    /** The diff span, extended across contiguous vowels when the span is itself all vowels. */
    DIFF,

    /**
     * Peel the unstressed strong-preterite ending off the span so the strong stem and the borrowed
     * ending are coloured separately: tuve -> t + [uv] + [e].
     */
    SPLIT_STEM_ENDING,

    /** The whole form is the irregularity; diffing suppletive forms produces noise, not insight. */
    WHOLE_FORM,
}

/**
 * The fine-grained class of a single deviation. This is what gets colour-coded on reveal, what each
 * reference table is built around, and what a `generalize` card is keyed on — "u-stem preterite" is
 * a teachable pattern in a way that [IrregularityFamily.STRONG_PRETERITE] is not.
 *
 * Slugs are persisted (generalize item keys, the golden dataset) — frozen.
 */
enum class IrregularityClass(
    val slug: String,
    val family: IrregularityFamily,
    val label: String,
    val spanPolicy: SpanPolicy = SpanPolicy.DIFF,
) {
    // Stem changes — the stressed syllable breaks into a diphthong or raises.
    STEM_E_IE("stem_e_ie", IrregularityFamily.STEM_CHANGE, "e -> ie"),
    STEM_O_UE("stem_o_ue", IrregularityFamily.STEM_CHANGE, "o -> ue"),
    STEM_E_I("stem_e_i", IrregularityFamily.STEM_CHANGE, "e -> i"),
    STEM_U_UE("stem_u_ue", IrregularityFamily.STEM_CHANGE, "u -> ue"),
    STEM_I_IE("stem_i_ie", IrregularityFamily.STEM_CHANGE, "i -> ie"),

    /** The third-person preterite raise that -ir stem-changers take: pidió, durmió. */
    STEM_RAISE_PRET("stem_raise_pret", IrregularityFamily.STEM_CHANGE, "preterite stem raise"),

    // First-person-singular irregularities, and the subjunctive that is built off them.
    YO_GO("yo_go", IrregularityFamily.YO_FORM, "-go yo"),
    YO_ZCO("yo_zco", IrregularityFamily.YO_FORM, "-zco yo"),
    YO_OY("yo_oy", IrregularityFamily.YO_FORM, "-oy yo"),
    YO_OTHER("yo_other", IrregularityFamily.YO_FORM, "other irregular yo"),
    SUBJ_YO_STEM("subj_yo_stem", IrregularityFamily.YO_FORM, "subjunctive off the yo stem"),

    // Strong (unstressed-ending) preterites, split by the shape of the stem they take.
    STRONG_PRET_U("strong_pret_u", IrregularityFamily.STRONG_PRETERITE, "u-stem preterite", SpanPolicy.SPLIT_STEM_ENDING),
    STRONG_PRET_I("strong_pret_i", IrregularityFamily.STRONG_PRETERITE, "i-stem preterite", SpanPolicy.SPLIT_STEM_ENDING),
    STRONG_PRET_J("strong_pret_j", IrregularityFamily.STRONG_PRETERITE, "j-stem preterite", SpanPolicy.SPLIT_STEM_ENDING),

    /** The unstressed -e/-o a strong preterite takes in place of -é/-ió. */
    STRONG_PRET_ENDING("strong_pret_ending", IrregularityFamily.STRONG_PRETERITE, "unstressed preterite ending"),

    // Future/conditional stems. Both tenses share one stem, which is why they share one class.
    FUT_DR("fut_dr", IrregularityFamily.FUTURE_STEM, "-dr- future stem"),
    FUT_DROP("fut_drop", IrregularityFamily.FUTURE_STEM, "vowel-drop future stem"),
    FUT_IRREGULAR("fut_irregular", IrregularityFamily.FUTURE_STEM, "contracted future stem"),

    // Spelling changes that keep the sound constant.
    ORTH_CAR_GAR_ZAR("orth_car_gar_zar", IrregularityFamily.ORTHOGRAPHIC, "-car/-gar/-zar spelling"),
    ORTH_GER_GIR_GUIR("orth_ger_gir_guir", IrregularityFamily.ORTHOGRAPHIC, "-ger/-gir/-guir spelling"),
    ORTH_Y_HIATUS("orth_y_hiatus", IrregularityFamily.ORTHOGRAPHIC, "i -> y between vowels"),
    ORTH_I_LOSS("orth_i_loss", IrregularityFamily.ORTHOGRAPHIC, "dropped unstressed i"),
    ACCENT_SHIFT("accent_shift", IrregularityFamily.ORTHOGRAPHIC, "written accent"),

    // Wholly irregular. Diffing these against a regular form teaches nothing, so they take the form whole.
    SUPPLETIVE("suppletive", IrregularityFamily.SUPPLETIVE, "suppletive", SpanPolicy.WHOLE_FORM),
    IMPERFECT_IRREGULAR("imperfect_irregular", IrregularityFamily.SUPPLETIVE, "irregular imperfect", SpanPolicy.WHOLE_FORM),
    SUBJ_IRREGULAR("subj_irregular", IrregularityFamily.SUPPLETIVE, "irregular subjunctive", SpanPolicy.WHOLE_FORM),

    /**
     * The generator's escape hatch for a deviation its cascade couldn't place. Emitting one is a
     * build warning, not a silent success — the long tail stays visible instead of being mislabelled.
     */
    OTHER("other_irregular", IrregularityFamily.SUPPLETIVE, "irregular", SpanPolicy.WHOLE_FORM),
    ;

    companion object {
        fun fromSlug(slug: String?): IrregularityClass? = entries.firstOrNull { it.slug == slug }

        /** The unstressed endings a strong preterite takes, indexed by [Person.ordinal]. */
        val STRONG_PRETERITE_ENDINGS = arrayOf("e", "iste", "o", "imos", "isteis", "ieron")
    }
}
