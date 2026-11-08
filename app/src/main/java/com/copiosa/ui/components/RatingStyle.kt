package com.copiosa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.domain.model.ReviewRating

/**
 * Fixed, semantic grade hues (fail→ace), independent of the profile's accent palette — under a
 * red-accent country theme, deriving these from the scheme collides Again/Good (both red) and
 * Hard/Easy (both gold). These mid-tones stay legible on the card surface in light and dark.
 */
fun ratingColor(rating: ReviewRating): Color = when (rating) {
    ReviewRating.AGAIN -> Color(0xFFD64545) // red
    ReviewRating.HARD -> Color(0xFFE0952B) // amber
    ReviewRating.GOOD -> Color(0xFF3FA45B) // green
    ReviewRating.EASY -> Color(0xFF3E7CB1) // blue
}

@Composable
fun ratingLabel(rating: ReviewRating): String = stringResource(
    when (rating) {
        ReviewRating.AGAIN -> R.string.rating_again
        ReviewRating.HARD -> R.string.rating_hard
        ReviewRating.GOOD -> R.string.rating_good
        ReviewRating.EASY -> R.string.rating_easy
    },
)
