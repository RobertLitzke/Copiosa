package com.copiosa.domain.conjugation

/**
 * The rule half of conjugation: what a verb *would* look like if it never deviated. Every regular cell
 * in the drill comes from here, and every irregular cell is displayed against the form this produces —
 * the naive counterpart shown struck through on reveal is literally [regular]'s output.
 *
 * All language data lives in the [spec]; this class is only the arithmetic. Pure and allocation-light;
 * no lexicon, no lookups beyond the spec's ending tables.
 */
class Conjugator(private val spec: ConjugationSpec) {

    /**
     * The infinitive ending that marks this verb's class, longest match first so an accented "ír"
     * beats a plain "ir". Null when [infinitive] doesn't end in a recognisable one.
     */
    fun classEndingOf(infinitive: String): String? =
        spec.classEndings.keys.sortedByDescending { it.length }.firstOrNull { infinitive.endsWith(it) }

    /** Null when [infinitive] doesn't end in one of the spec's conjugation-class endings. */
    fun classOf(infinitive: String): VerbClass? = classEndingOf(infinitive)?.let { spec.classEndings[it] }

    /**
     * The infinitive minus its class ending: "hablar" -> "habl", "oír" -> "o". An unrecognised
     * infinitive is returned whole — the caller will already have got null from [classOf].
     */
    fun stem(infinitive: String): String =
        classEndingOf(infinitive)?.let { infinitive.dropLast(it.length) } ?: infinitive

    /**
     * The regular form for a cell, or null when [infinitive] isn't a conjugable verb.
     *
     * A tense the spec marks as derived is built off another cell's *regular* form, so the naive
     * counterpart of an irregular derived cell is still a well-formed rule-following word.
     */
    fun regular(infinitive: String, tense: Tense, person: Person): String? {
        val cls = classOf(infinitive) ?: return null
        spec.derived[tense]?.let { derived ->
            val source = derived.source?.let { regular(infinitive, it.tense, it.person) } ?: infinitive
            return derive(source, derived, person)
        }
        val base = if (tense in spec.infinitiveStemTenses) infinitive else stem(infinitive)
        return base + ending(cls, tense, person)
    }

    /**
     * The bare ending for a cell — for a derived tense, the ending alone, since the base it attaches to
     * comes from the source cell rather than the infinitive.
     */
    fun ending(cls: VerbClass, tense: Tense, person: Person): String {
        spec.derived[tense]?.let { return it.endings[person.ordinal] }
        val table = spec.endings[tense]?.get(cls) ?: return ""
        return table[person.ordinal]
    }

    /**
     * Applies a derivation to whatever [sourceForm] is handed in — the *actual* source cell for a real
     * form, the *regular* one for the naive counterpart. Null when the source doesn't have the shape
     * the derivation strips (so a caller passing the wrong cell fails loudly rather than silently).
     */
    fun derive(sourceForm: String, derived: DerivedTense, person: Person): String? {
        if (!sourceForm.endsWith(derived.strip)) return null
        val base = sourceForm.dropLast(derived.strip.length)
        if (base.isEmpty()) return null
        val stem = if (person in derived.accentPersons) spec.accentFinalVowel(base) else base
        return stem + derived.endings[person.ordinal]
    }
}
