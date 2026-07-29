package com.copiosa.ui.components

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's determinate progress bar: Material 3's, minus the dot it parks at the end of the track.
 * That marker is meant to show where a track ends when the bar is inset in a wider surface; on a
 * full-width bar it has nothing to disambiguate and just reads as a stray mark.
 *
 * Indeterminate bars have no stop indicator, so they keep using [LinearProgressIndicator] directly.
 */
@Composable
fun ProgressBar(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        drawStopIndicator = {},
    )
}
