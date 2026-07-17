package com.copiosa.ui.flashcards

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R

/**
 * A compact "time until next review" label from a due-minus-now delta in milliseconds — e.g. "due",
 * "45m", "5h", "3d", "2w", "4mo", "1y". Kept terse (single unit, no "in …") so it reads as a quiet
 * cue: on the vocab list row and under each review rating button. Non-positive deltas mean the card
 * is due (or overdue) right now.
 */
@Composable
fun formatDueShort(deltaMs: Long): String {
    if (deltaMs <= 0L) return stringResource(R.string.due_short_now)
    val minutes = deltaMs / 60_000L
    if (minutes < 60L) return stringResource(R.string.due_short_minutes, minutes.coerceAtLeast(1L).toInt())
    val hours = deltaMs / 3_600_000L
    if (hours < 24L) return stringResource(R.string.due_short_hours, hours.toInt())
    val days = deltaMs / 86_400_000L
    return when {
        days < 7L -> stringResource(R.string.due_short_days, days.toInt())
        days < 56L -> stringResource(R.string.due_short_weeks, (days / 7L).toInt())
        days < 365L -> stringResource(R.string.due_short_months, (days / 30L).toInt())
        else -> stringResource(R.string.due_short_years, (days / 365L).toInt())
    }
}
