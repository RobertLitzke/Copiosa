package com.copiosa.ui.routines.activities

import androidx.compose.runtime.Composable
import com.copiosa.ui.vocab.ReviewScreen

/**
 * The flashcard step: the real [ReviewScreen] (show-answer → Again/Hard/Good/Easy, edit, media, the
 * "n / total" counter), so a card studied here is a genuine review. The routine only frames it with its
 * [header] on top, configures the card count, and turns the end-of-session screen into a "Continue".
 *
 * The frequent-word drill is the same step with [freqTop] set: identical screen, identical grading,
 * only the pool the session is drawn from is narrowed to words inside that frequency rank.
 */
@Composable
internal fun RoutineReviewStep(
    cardLimit: Int,
    onExit: () -> Unit,
    onComplete: () -> Unit,
    header: @Composable () -> Unit,
    freqTop: Int? = null,
) {
    ReviewScreen(
        onBack = onExit,
        limit = cardLimit,
        freqTop = freqTop,
        onFinished = onComplete,
        topFrame = header,
    )
}
