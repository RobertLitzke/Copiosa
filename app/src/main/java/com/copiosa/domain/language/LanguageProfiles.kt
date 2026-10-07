package com.copiosa.domain.language

import com.copiosa.domain.routine.ContrastCategory

/** The target languages the grammar activities have data for. */
object LanguageProfiles {

    val SPANISH = LanguageProfile(
        tag = "es",
        contrasts = listOf(
            ContrastCategory.SER_ESTAR,
            ContrastCategory.POR_PARA,
            ContrastCategory.PRETERITE_IMPERFECT,
            ContrastCategory.SUBJUNCTIVE_TRIGGER,
            ContrastCategory.GENDER_AGREEMENT,
        ),
        // Only unambiguous / accented forms: bare "tu" is "your" and bare "el" is "the".
        subjectPronouns = listOf(
            "yo", "tú", "usted", "ustedes", "él", "ella", "ellos", "ellas",
            "nosotros", "nosotras", "vosotros", "vosotras",
        ),
        timeAdverbs = listOf("ayer", "hoy", "mañana", "manana", "ahora", "anoche"),
        falseFriendExamples = listOf("embarazada", "éxito", "sensible"),
        genderBins = listOf(GenderBin("M", "el (m.)"), GenderBin("F", "la (f.)")),
    )

    /**
     * European Portuguese. It shares every one of Spanish's contrasts — the Iberian Romance grammar
     * points are the same grammar points — and adds three of its own, of which the future
     * subjunctive is the one a Spanish speaker most reliably gets wrong.
     */
    val PORTUGUESE = LanguageProfile(
        tag = "pt",
        contrasts = listOf(
            ContrastCategory.SER_ESTAR,
            ContrastCategory.POR_PARA,
            ContrastCategory.PRETERITE_IMPERFECT,
            ContrastCategory.SUBJUNCTIVE_TRIGGER,
            ContrastCategory.GENDER_AGREEMENT,
            ContrastCategory.FUTURE_SUBJUNCTIVE,
            ContrastCategory.PERSONAL_INFINITIVE,
            ContrastCategory.PREP_CONTRACTION,
        ),
        // "a gente" is a pronoun in everything but agreement, and gives the referent away just as
        // plainly as "nós" does, so referential PI has to ban it too.
        subjectPronouns = listOf(
            "eu", "tu", "você", "voce", "vocês", "voces", "ele", "ela", "eles", "elas",
            "nós", "nos", "vós", "vos", "a gente",
        ),
        timeAdverbs = listOf("ontem", "hoje", "amanhã", "amanha", "agora", "logo"),
        // puxar = to pull, constipado = having a cold, esquisito = weird.
        falseFriendExamples = listOf("puxar", "constipado", "esquisito"),
        genderBins = listOf(GenderBin("M", "o (m.)"), GenderBin("F", "a (f.)")),
    )

    val all = listOf(SPANISH, PORTUGUESE)

    /**
     * The profile for a BCP-47 tag, or null when the app has no grammar data for it. The region
     * subtag is ignored, matching how [com.copiosa.domain.conjugation.ConjugationSpecs] resolves.
     */
    fun forTag(tag: String): LanguageProfile? {
        val base = tag.substringBefore('-').lowercase()
        return all.firstOrNull { it.tag == base }
    }
}
