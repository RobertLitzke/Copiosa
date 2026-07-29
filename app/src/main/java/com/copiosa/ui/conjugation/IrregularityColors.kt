package com.copiosa.ui.conjugation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.domain.conjugation.IrregularityClass
import com.copiosa.domain.conjugation.IrregularityFamily

/**
 * The colour a family of irregularity is highlighted in, following the [com.copiosa.ui.flashcards]
 * `CardStatus` precedent: a small hand-picked set, tuned to read against both the cream and dark
 * surfaces.
 *
 * Six distinguishable hues, deliberately avoiding the greens and amber the card-status dots already
 * own so the two never read as the same signal. The point is that a learner starts to recognise the
 * *colour* of a deviation before they can name it.
 */
@Composable
fun IrregularityFamily.color(): Color = when (this) {
    // Regular material is never highlighted; this is the fallback for a legend row.
    IrregularityFamily.REGULAR -> MaterialTheme.colorScheme.onSurfaceVariant
    IrregularityFamily.STEM_CHANGE -> Color(0xFF7B4FC4) // violet — the vowel breaking apart
    IrregularityFamily.YO_FORM -> Color(0xFF0F7C8A) // teal — first person only
    IrregularityFamily.STRONG_PRETERITE -> Color(0xFFB5423B) // brick — the heaviest irregularity
    IrregularityFamily.FUTURE_STEM -> Color(0xFFA85E00) // ochre — future/conditional stem
    IrregularityFamily.ORTHOGRAPHIC -> Color(0xFF3E6BB8) // blue — spelling, not sound
    IrregularityFamily.SUPPLETIVE -> Color(0xFF8A2E6B) // magenta — a different verb entirely
}

/** Localized name of a family, used on legends, the status screen and the reference tables. */
@Composable
fun IrregularityFamily.label(): String = stringResource(
    when (this) {
        IrregularityFamily.REGULAR -> R.string.conj_family_regular
        IrregularityFamily.STEM_CHANGE -> R.string.conj_family_stem_change
        IrregularityFamily.YO_FORM -> R.string.conj_family_yo
        IrregularityFamily.STRONG_PRETERITE -> R.string.conj_family_strong_pret
        IrregularityFamily.FUTURE_STEM -> R.string.conj_family_fut_stem
        IrregularityFamily.ORTHOGRAPHIC -> R.string.conj_family_orth
        IrregularityFamily.SUPPLETIVE -> R.string.conj_family_suppletive
    },
)

/** Localized name of a specific class — the finer label shown on reveal and in the reference tables. */
@Composable
fun IrregularityClass.label(): String = stringResource(
    when (this) {
        IrregularityClass.STEM_E_IE -> R.string.conj_class_stem_e_ie
        IrregularityClass.STEM_O_UE -> R.string.conj_class_stem_o_ue
        IrregularityClass.STEM_E_I -> R.string.conj_class_stem_e_i
        IrregularityClass.STEM_U_UE -> R.string.conj_class_stem_u_ue
        IrregularityClass.STEM_I_IE -> R.string.conj_class_stem_i_ie
        IrregularityClass.STEM_RAISE_PRET -> R.string.conj_class_stem_raise_pret
        IrregularityClass.YO_GO -> R.string.conj_class_yo_go
        IrregularityClass.YO_ZCO -> R.string.conj_class_yo_zco
        IrregularityClass.YO_OY -> R.string.conj_class_yo_oy
        IrregularityClass.YO_OTHER -> R.string.conj_class_yo_other
        IrregularityClass.SUBJ_YO_STEM -> R.string.conj_class_subj_yo_stem
        IrregularityClass.STRONG_PRET_U -> R.string.conj_class_strong_pret_u
        IrregularityClass.STRONG_PRET_I -> R.string.conj_class_strong_pret_i
        IrregularityClass.STRONG_PRET_J -> R.string.conj_class_strong_pret_j
        IrregularityClass.STRONG_PRET_ENDING -> R.string.conj_class_strong_pret_ending
        IrregularityClass.FUT_DR -> R.string.conj_class_fut_dr
        IrregularityClass.FUT_DROP -> R.string.conj_class_fut_drop
        IrregularityClass.FUT_IRREGULAR -> R.string.conj_class_fut_irregular
        IrregularityClass.ORTH_CAR_GAR_ZAR -> R.string.conj_class_orth_car_gar_zar
        IrregularityClass.ORTH_GER_GIR_GUIR -> R.string.conj_class_orth_ger_gir_guir
        IrregularityClass.ORTH_Y_HIATUS -> R.string.conj_class_orth_y_hiatus
        IrregularityClass.ORTH_I_LOSS -> R.string.conj_class_orth_i_loss
        IrregularityClass.ACCENT_SHIFT -> R.string.conj_class_accent_shift
        IrregularityClass.SUPPLETIVE -> R.string.conj_class_suppletive
        IrregularityClass.IMPERFECT_IRREGULAR -> R.string.conj_class_imperfect_irregular
        IrregularityClass.SUBJ_IRREGULAR -> R.string.conj_class_subj_irregular
        IrregularityClass.OTHER -> R.string.conj_class_other
    },
)
