package com.copiosa.domain.conjugation

import kotlinx.serialization.Serializable

/**
 * The mood a paradigm belongs to.
 *
 * Nothing is persisted by mood and no drill scopes itself to one, so this is not frozen. It exists
 * because a learner's mental model of the paradigms is mood-first — "the subjunctive" is one thing
 * they are learning, not three — and a reference that lists nine tenses flat hides that.
 */
enum class Mood {
    INDICATIVE,
    SUBJUNCTIVE,

    /** Carries person but no tense of its own: Portuguese's personal infinitive. */
    NON_FINITE,
}

/**
 * A tense/mood paradigm a drill may cover — the union across every language, not one language's set.
 * Which of these are actually in play is [ConjugationSpec.tenses]; iterate that, never [entries], or
 * a Portuguese-only paradigm leaks into a Spanish drill.
 *
 * The [slug] is persisted inside drill item keys and inside serialized routine plans, and the
 * constant **name** is persisted too (`ConjugationDrillConfig` serializes `Set<Tense>` by name into
 * settings), so both are **frozen** — same rule `RoutinePlan.kt` states for its `@SerialName`s. New
 * constants may be appended freely.
 *
 * Only simple (non-compound) forms are modelled. Several of these are *derived* rather than stored:
 * built from another cell, so they inherit its irregularity for free — see [DerivedTense].
 */
@Serializable
enum class Tense(val slug: String, val mood: Mood = Mood.INDICATIVE) {
    PRESENT("pres"),
    PRETERITE("pret"),
    IMPERFECT("imp"),
    FUTURE("fut"),
    CONDITIONAL("cond"),
    PRESENT_SUBJUNCTIVE("sub_pres", Mood.SUBJUNCTIVE),
    IMPERFECT_SUBJUNCTIVE("sub_imp", Mood.SUBJUNCTIVE),

    /** Portuguese: *se fizer*. Off the third-plural preterite, like the imperfect subjunctive. */
    FUTURE_SUBJUNCTIVE("sub_fut", Mood.SUBJUNCTIVE),

    /** Portuguese: *para fazermos*. Off the infinitive, so it stays regular where the future subjunctive doesn't. */
    PERSONAL_INFINITIVE("inf_pess", Mood.NON_FINITE),
    ;

    companion object {
        fun fromSlug(slug: String?): Tense? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * Grammatical person + number. [slug] is persisted in item keys — frozen.
 *
 * The constant names are Spanish for historical reasons; the slugs are not, and they are what any
 * other language reuses. The pronoun a learner actually reads comes from
 * [ConjugationSpec.pronouns] — "nosotros" for Spanish, "nós" for Portuguese — never from the name.
 */
@Serializable
enum class Person(val slug: String) {
    YO("1s"),
    TU("2s"),
    EL("3s"),
    NOSOTROS("1p"),
    VOSOTROS("2p"),
    ELLOS("3p"),
    ;

    companion object {
        fun fromSlug(slug: String?): Person? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * What the learner is asked to do with a cell. [PRODUCE] and [DECODE] are the two recall directions;
 * [TRANSFORM] and [GENERALIZE] are the two that test whether a pattern actually generalized rather
 * than being memorized as a list. [slug] is persisted in item keys — frozen.
 */
@Serializable
enum class DrillDirection(val slug: String) {
    /** verb + person + tense -> the conjugated form. */
    PRODUCE("produce"),

    /** the conjugated form -> verb + person + tense. */
    DECODE("decode"),

    /** one cell -> a derived cell (supieron -> supiera): the derivational spine. */
    TRANSFORM("transform"),

    /** an irregularity class + a verb never drilled before: rule, not list. */
    GENERALIZE("generalize"),
    ;

    companion object {
        fun fromSlug(slug: String?): DrillDirection? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * The three Romance conjugation classes, keyed by infinitive ending. Spanish and Portuguese share
 * them; the actual endings a class accepts come from [ConjugationSpec.classEndings].
 */
enum class VerbClass(val ending: String) {
    AR("ar"),
    ER("er"),
    IR("ir"),
}

/**
 * One paradigm cell — a (tense, person) coordinate. [slug] is the serialized form used inside drill
 * item keys ("pret.3p"), so it is frozen along with the two enums' slugs.
 */
@Serializable
data class Cell(val tense: Tense, val person: Person) {
    val slug: String get() = "${tense.slug}.${person.slug}"

    override fun toString(): String = slug

    companion object {
        fun fromSlug(slug: String?): Cell? {
            if (slug.isNullOrBlank()) return null
            val tense = Tense.fromSlug(slug.substringBefore('.', "")) ?: return null
            val person = Person.fromSlug(slug.substringAfter('.', "")) ?: return null
            return Cell(tense, person)
        }
    }
}
