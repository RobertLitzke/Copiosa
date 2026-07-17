package com.copiosa.ui.flashcards

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.FlashcardState

/**
 * A glanceable spaced-repetition status for a card, derived purely from its existing schedule (no new
 * tracking). Drives the leading dot on the vocab list and the status line on the detail screen.
 *
 * [MATURE_THRESHOLD_DAYS] mirrors Anki's "mature card" cutoff (interval >= 21 days).
 */
enum class CardStatus {
    /** Set aside by the user — excluded from review and from conversation vocab. */
    FROZEN,
    /** Never reviewed. */
    NEW,
    /** In (re)learning or a sub-day interval — seen very recently, comes back soon. */
    LEARNING,
    /** Reviewed, but the interval is still short (< 21 days). */
    YOUNG,
    /** Long, stable interval (>= 21 days). */
    MATURE,
}

/** Classifies a card for display. Frozen wins over everything; otherwise by lifecycle + interval. */
fun statusOf(card: FlashcardEntity): CardStatus = when {
    card.suspended -> CardStatus.FROZEN
    card.state == FlashcardState.NEW -> CardStatus.NEW
    card.state == FlashcardState.LEARNING || card.state == FlashcardState.RELEARNING -> CardStatus.LEARNING
    card.intervalDays < 1.0 -> CardStatus.LEARNING
    card.intervalDays < MATURE_THRESHOLD_DAYS -> CardStatus.YOUNG
    else -> CardStatus.MATURE
}

private const val MATURE_THRESHOLD_DAYS = 21.0

/** Accent color for a status dot/label. Tuned to read against both the cream and dark surfaces. */
@Composable
fun CardStatus.color(): Color = when (this) {
    CardStatus.FROZEN -> MaterialTheme.colorScheme.outline
    CardStatus.NEW -> MaterialTheme.colorScheme.primary
    CardStatus.LEARNING -> Color(0xFFE0833B) // warm amber — "coming back soon"
    CardStatus.YOUNG -> Color(0xFF6FA86A) // light green — taking root
    CardStatus.MATURE -> Color(0xFF2E7D32) // deep green — well established
}

/**
 * Due-oriented indicator for the vocab list — answers "when does this come up again?" rather than the
 * maturity that [statusOf] tracks. Both the dot color and the [dueLabel] text derive from the same
 * `dueAt - now` proximity, so a card reads as: gray = frozen, blue = never studied, amber = due or
 * overdue, greens = comfortably scheduled ahead.
 */
@Composable
fun dueColor(card: FlashcardEntity, now: Long): Color = when {
    card.suspended -> MaterialTheme.colorScheme.outline
    card.state == FlashcardState.NEW -> MaterialTheme.colorScheme.primary
    card.dueAt <= now -> Color(0xFFE0833B) // warm amber — due / overdue, needs attention
    card.dueAt - now < WEEK_MILLIS -> Color(0xFF6FA86A) // light green — coming up this week
    else -> Color(0xFF2E7D32) // deep green — comfortably scheduled ahead
}

/** Compact due text for a list row, or null when there's no meaningful schedule (frozen). */
@Composable
fun dueLabel(card: FlashcardEntity, now: Long): String? = when {
    card.suspended -> null
    card.state == FlashcardState.NEW -> stringResource(R.string.due_short_new)
    else -> formatDueShort(card.dueAt - now)
}

private const val WEEK_MILLIS = 7L * 86_400_000L

/** Localized one-word label for the status. */
@Composable
fun CardStatus.label(): String = stringResource(
    when (this) {
        CardStatus.FROZEN -> R.string.card_status_frozen
        CardStatus.NEW -> R.string.card_status_new
        CardStatus.LEARNING -> R.string.card_status_learning
        CardStatus.YOUNG -> R.string.card_status_young
        CardStatus.MATURE -> R.string.card_status_mature
    },
)
