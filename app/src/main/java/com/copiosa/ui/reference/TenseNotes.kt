package com.copiosa.ui.reference

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.Mood
import com.copiosa.domain.conjugation.Tense
import com.copiosa.ui.conjugation.label

/**
 * The prose half of the tense guide.
 *
 * Everything a paradigm *does* is authored here, because it cannot be derived: no amount of ending
 * tables says that the imperfect sets the scene the preterite acts in. Everything a paradigm *is* —
 * what it is built from, which persons it has, how it spells out — comes off [ConjugationSpec]
 * instead, so the two halves can never disagree the way a hand-written table would.
 *
 * The copy is deliberately shared across Spanish and Portuguese: these tenses mean the same thing in
 * both, and the two places they don't (the future subjunctive, the personal infinitive) are paradigms
 * only one of the languages has at all.
 */
@Composable
fun Tense.usage(): String = stringResource(
    when (this) {
        Tense.PRESENT -> R.string.ref_use_present
        Tense.PRETERITE -> R.string.ref_use_preterite
        Tense.IMPERFECT -> R.string.ref_use_imperfect
        Tense.FUTURE -> R.string.ref_use_future
        Tense.CONDITIONAL -> R.string.ref_use_conditional
        Tense.PRESENT_SUBJUNCTIVE -> R.string.ref_use_present_subjunctive
        Tense.IMPERFECT_SUBJUNCTIVE -> R.string.ref_use_imperfect_subjunctive
        Tense.FUTURE_SUBJUNCTIVE -> R.string.ref_use_future_subjunctive
        Tense.PERSONAL_INFINITIVE -> R.string.ref_use_personal_infinitive
    },
)

@Composable
fun Mood.label(): String = stringResource(
    when (this) {
        Mood.INDICATIVE -> R.string.ref_mood_indicative
        Mood.SUBJUNCTIVE -> R.string.ref_mood_subjunctive
        Mood.NON_FINITE -> R.string.ref_mood_nonfinite
    },
)

@Composable
fun Mood.blurb(): String = stringResource(
    when (this) {
        Mood.INDICATIVE -> R.string.ref_mood_indicative_desc
        Mood.SUBJUNCTIVE -> R.string.ref_mood_subjunctive_desc
        Mood.NON_FINITE -> R.string.ref_mood_nonfinite_desc
    },
)

/**
 * How a paradigm is put together, read straight off the spec — null for one that is simply a stem
 * plus its own ending table, which needs no explaining beyond the table itself.
 *
 * The two cases that do are the ones a learner keeps re-deriving: a *derived* tense, which inherits
 * whatever the cell it is built from does (`supieron` → `supiera` for free), and an infinitive-stem
 * tense, which is why an irregular future stem is its own kind of deviation rather than a stem change.
 */
@Composable
fun formationNote(tense: Tense, spec: ConjugationSpec): String? {
    spec.derived[tense]?.let { derived ->
        val source = derived.source ?: return stringResource(R.string.ref_tense_from_infinitive)
        return stringResource(
            R.string.ref_tense_from_cell,
            source.tense.label(),
            source.person.label(spec),
        )
    }
    if (tense in spec.infinitiveStemTenses) return stringResource(R.string.ref_tense_infinitive_stem)
    return null
}
