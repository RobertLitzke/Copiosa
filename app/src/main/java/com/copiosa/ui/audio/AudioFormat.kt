package com.copiosa.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R

/**
 * Two scales of duration, kept together because the audio surfaces mix them freely: a track is
 * minutes and reads as "12:04", a folder or a whole book is hours and reads as "4h 12m".
 */

/** One track's length, "m:ss". Blank for a duration that isn't known yet. */
fun trackDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** A folder's or a book's total listening time, "4h 12m" or "18 min". */
@Composable
fun listeningTime(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return if (minutes >= 60) {
        stringResource(R.string.audio_hours_minutes, minutes / 60, minutes % 60)
    } else {
        stringResource(R.string.audio_minutes, minutes.coerceAtLeast(1))
    }
}
