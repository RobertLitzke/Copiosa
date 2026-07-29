package com.copiosa.domain.conjugation

import kotlinx.serialization.Serializable

/**
 * A tense/mood paradigm the drill covers. The [slug] is persisted inside drill item keys and inside
 * serialized routine plans, so it is **frozen** — same rule `RoutinePlan.kt` states for its
 * `@SerialName`s.
 *
 * Only simple (non-compound) forms are modelled. [IMPERFECT_SUBJUNCTIVE] is special: it is derived
 * from the (possibly irregular) third-person-plural preterite rather than from the infinitive, so it
 * inherits preterite irregularity for free and is never stored in the lexicon — see
 * [RegularConjugator.imperfectSubjunctive].
 */
@Serializable
enum class Tense(val slug: String) {
    PRESENT("pres"),
    PRETERITE("pret"),
    IMPERFECT("imp"),
    FUTURE("fut"),
    CONDITIONAL("cond"),
    PRESENT_SUBJUNCTIVE("sub_pres"),
    IMPERFECT_SUBJUNCTIVE("sub_imp"),
    ;

    companion object {
        fun fromSlug(slug: String?): Tense? = entries.firstOrNull { it.slug == slug }
    }
}

/** Grammatical person + number. [slug] is persisted in item keys — frozen. */
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

/** The three Spanish conjugation classes, keyed by infinitive ending. */
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
