package com.copiosa.ui.conjugation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.conjugation.TransformRule
import com.copiosa.domain.conjugation.TransformRules

/**
 * Localized names for the paradigm vocabulary.
 *
 * Tense names follow the interface language: a grammar term is metadata about the exercise, and
 * reading "imperfecto" in an English interface is a translation task the drill never asked for.
 *
 * Person names do not. A subject pronoun is a word the learner will actually say, so rendering
 * "nosotros" as "we" would teach a label they then have to unlearn — and it is *target-language*
 * data, not interface copy, which is why it comes off the [ConjugationSpec] and not `R.string`.
 * That is also what lets a Portuguese profile show "nós" without a second set of resources.
 */
@Composable
fun Tense.label(): String = stringResource(
    when (this) {
        Tense.PRESENT -> R.string.conj_tense_present
        Tense.PRETERITE -> R.string.conj_tense_preterite
        Tense.IMPERFECT -> R.string.conj_tense_imperfect
        Tense.FUTURE -> R.string.conj_tense_future
        Tense.CONDITIONAL -> R.string.conj_tense_conditional
        Tense.PRESENT_SUBJUNCTIVE -> R.string.conj_tense_present_subjunctive
        Tense.IMPERFECT_SUBJUNCTIVE -> R.string.conj_tense_imperfect_subjunctive
        Tense.FUTURE_SUBJUNCTIVE -> R.string.conj_tense_future_subjunctive
        Tense.PERSONAL_INFINITIVE -> R.string.conj_tense_personal_infinitive
    },
)

/** The subject pronoun, as it would head a paradigm table column. */
fun Person.label(spec: ConjugationSpec): String = spec.pronouns[this] ?: slug

@Composable
fun DrillDirection.label(): String = stringResource(
    when (this) {
        DrillDirection.PRODUCE -> R.string.conj_direction_produce
        DrillDirection.DECODE -> R.string.conj_direction_decode
        DrillDirection.TRANSFORM -> R.string.conj_direction_transform
        DrillDirection.GENERALIZE -> R.string.conj_direction_generalize
    },
)

/**
 * What a transform card is asking for, e.g. "preterite → imperfect subjunctive". Keyed on the slug
 * with the language prefix dropped, so the same derivation reads the same in every language that
 * has it.
 */
@Composable
fun TransformRule.label(): String = stringResource(
    when (slug.removePrefix("pt_")) {
        "pret3p_to_subimp" -> R.string.conj_transform_pret_to_subimp
        "pret3p_to_subfut" -> R.string.conj_transform_pret_to_subfut
        "pres1s_to_subpres" -> R.string.conj_transform_yo_to_subpres
        "fut1s_to_cond1s" -> R.string.conj_transform_fut_to_cond
        else -> R.string.conj_transform_pres_to_pret
    },
)
