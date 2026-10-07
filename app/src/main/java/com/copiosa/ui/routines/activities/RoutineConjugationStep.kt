package com.copiosa.ui.routines.activities

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.copiosa.domain.conjugation.ConjugationSpecs
import com.copiosa.domain.conjugation.DrillItemCodec
import com.copiosa.domain.routine.ConjugationDrillContent
import com.copiosa.ui.conjugation.ConjugationDrillActivity
import com.copiosa.ui.routines.RoutinePlayerViewModel

/**
 * The conjugation drill framed by the routine player. Reuses the same runner the standalone screen
 * does — the `RoutineReviewStep` → `ReviewScreen` precedent — so in-routine practice trains exactly
 * the same per-item mastery.
 *
 * Two things are recorded per answer, deliberately split: the drill's own store (via
 * [RoutinePlayerViewModel.recordConjugation]) and the routine's attempt log plus proficiency bucket
 * (via [RoutinePlayerViewModel.recordAttempt], which takes the item's irregularity family, since one
 * block spans several).
 */
@Composable
internal fun RoutineConjugationStep(
    content: ConjugationDrillContent,
    viewModel: RoutinePlayerViewModel,
    activity: com.copiosa.domain.routine.ActivityInstance,
    onComplete: () -> Unit,
) {
    // Items whose verb the lexicon can no longer place — or whose language has no paradigm data —
    // are dropped, so an older saved plan degrades to a shorter block rather than crashing.
    val spec = remember(content) { ConjugationSpecs.forTag(content.language) }
    val items = remember(content, spec) {
        spec?.let { DrillItemCodec.decodeAll(content.items, it) }.orEmpty()
    }
    if (spec == null || items.isEmpty()) {
        LaunchedEffect(content) { onComplete() }
        return
    }

    ConjugationDrillActivity(
        items = items,
        spec = spec,
        onGrade = { item, form, grade, latencyMs ->
            viewModel.recordConjugation(item, form, grade, latencyMs)
            viewModel.recordAttempt(
                activity = activity,
                correct = grade.correct,
                latencyMs = latencyMs,
                categoryOverride = form.family.slug,
            )
        },
        onComplete = onComplete,
        // The player already draws the routine's own progress bar above framed activities.
        showProgress = false,
    )
}
