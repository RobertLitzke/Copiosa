package com.copiosa.ui.conjugation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R
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
 * Person names stay Spanish in both locales — "nosotros" is a word the learner will actually say,
 * and rendering it "we" would teach a label they then have to unlearn.
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
    },
)

/** The subject pronoun, as it would head a paradigm table column. */
@Composable
fun Person.label(): String = stringResource(
    when (this) {
        Person.YO -> R.string.conj_person_yo
        Person.TU -> R.string.conj_person_tu
        Person.EL -> R.string.conj_person_el
        Person.NOSOTROS -> R.string.conj_person_nosotros
        Person.VOSOTROS -> R.string.conj_person_vosotros
        Person.ELLOS -> R.string.conj_person_ellos
    },
)

@Composable
fun DrillDirection.label(): String = stringResource(
    when (this) {
        DrillDirection.PRODUCE -> R.string.conj_direction_produce
        DrillDirection.DECODE -> R.string.conj_direction_decode
        DrillDirection.TRANSFORM -> R.string.conj_direction_transform
        DrillDirection.GENERALIZE -> R.string.conj_direction_generalize
    },
)

/** What a transform card is asking for, e.g. "preterite → imperfect subjunctive". */
@Composable
fun TransformRule.label(): String = stringResource(
    when (this) {
        TransformRules.PRETERITE_TO_IMPERFECT_SUBJUNCTIVE -> R.string.conj_transform_pret_to_subimp
        TransformRules.YO_TO_PRESENT_SUBJUNCTIVE -> R.string.conj_transform_yo_to_subpres
        TransformRules.FUTURE_TO_CONDITIONAL -> R.string.conj_transform_fut_to_cond
        else -> R.string.conj_transform_pres_to_pret
    },
)
