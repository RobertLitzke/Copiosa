package com.copiosa.ui.routines.activities

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.copiosa.domain.routine.GuessThenFlipContent
import com.copiosa.ui.components.ProgressBar
import com.copiosa.ui.flashcards.ReviewScreen

/**
 * The flashcard step: the real [ReviewScreen] (show-answer → Again/Hard/Good/Easy, edit, media, the
 * "n / total" counter), so a card studied here is a genuine review. The routine only frames it with its
 * progress bar on top, configures the card count, and turns the end-of-session screen into a "Continue".
 */
@Composable
internal fun RoutineReviewStep(
    content: GuessThenFlipContent,
    index: Int,
    total: Int,
    onExit: () -> Unit,
    onComplete: () -> Unit,
) {
    ReviewScreen(
        onBack = onExit,
        limit = content.cardLimit,
        onFinished = onComplete,
        topFrame = {
            ProgressBar(
                progress = { (index + 1f) / total },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
