package com.copiosa.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.stats.DayMinutes
import com.copiosa.data.stats.WeeklyFocusStats
import com.copiosa.ui.components.CefrBadge
import com.copiosa.ui.components.CircleAvatar
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.routines.icon
import com.copiosa.ui.routines.statusLine
import com.copiosa.ui.routines.titleRes
import com.copiosa.ui.theme.skin.LocalAppSkin
import com.copiosa.ui.theme.skin.personaTexture
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Home tab: this week's focus-time summary, a "continue reading" callout, and the "Keep going"
 * section — contextual suggestions beside a fixed set of session formats. Everything reacts to
 * profile switches via [HomeViewModel].
 */
@Composable
fun HomeScreen(
    onOpenConversation: (Long) -> Unit,
    onOpenText: (Long) -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenReview: (Int) -> Unit,
    onOpenRapidFire: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenReference: () -> Unit,
    onOpenConjugation: () -> Unit,
    onOpenCloze: () -> Unit,
    onOpenCourses: () -> Unit,
    onOpenNewChat: () -> Unit,
    onOpenRoutine: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val skin = LocalAppSkin.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .personaTexture(skin)
            .verticalScroll(rememberScrollState()),
    ) {
        // The reference sits in the header rather than among the session formats: it is somewhere you
        // go to look something up, not a way to practise.
        ScreenHeader(
            title = stringResource(R.string.app_name),
            actions = {
                IconButton(onClick = onOpenReference) {
                    Icon(Icons.Filled.School, contentDescription = stringResource(R.string.ref_title))
                }
            },
        )

        ui.stats?.let { StatsHeaderCard(it, ui.dailyGoalMinutes, onOpenStats) }

        if (ui.continueItems.isNotEmpty()) {
            SectionLabel(
                stringResource(R.string.home_continue_reading),
                Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
            )
            ui.continueItems.forEach { item ->
                ContinueCard(
                    item = item,
                    onClick = {
                        when (item.kind) {
                            ContinueKind.CHAT -> onOpenConversation(item.id)
                            ContinueKind.TEXT -> onOpenText(item.id)
                            ContinueKind.BOOK -> onOpenBook(item.id)
                        }
                    },
                )
            }
        }

        SectionLabel(
            stringResource(R.string.home_keep_going),
            Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
        )
        KeepGoing(
            ui = ui,
            onOpenReview = onOpenReview,
            onOpenRapidFire = onOpenRapidFire,
            onOpenAudio = onOpenAudio,
            onOpenConversation = onOpenConversation,
            onOpenConjugation = onOpenConjugation,
            onOpenCloze = onOpenCloze,
            onOpenCourses = onOpenCourses,
            onOpenNewChat = onOpenNewChat,
            onOpenRoutine = onOpenRoutine,
        )
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The card's palette. It is meant to be the loudest surface on the screen: in light mode that
 * means flipping to the inverse (dark ink) surface, but in dark mode the inverse slot is a
 * near-white that glares, so we accent with the deep primary container instead.
 */
private data class StatsCardColors(val container: Color, val fg: Color, val positive: Color)

@Composable
private fun statsCardColors(): StatsCardColors {
    val scheme = MaterialTheme.colorScheme
    return if (LocalAppSkin.current.dark) {
        StatsCardColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
    } else {
        StatsCardColors(scheme.inverseSurface, scheme.inverseOnSurface, scheme.inversePrimary)
    }
}

/** The accented summary card: big minutes number, streak, and the week's per-day bars. Tapping opens stats. */
@Composable
private fun StatsHeaderCard(stats: WeeklyFocusStats, goalMinutes: Int, onClick: () -> Unit) {
    val colors = statsCardColors()
    val fg = colors.fg
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colors.container),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${stats.weekMinutes}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                    )
                    Text(
                        stringResource(R.string.home_min_this_week),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = fg.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                    )
                }
                if (stats.streakDays > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.LocalFireDepartment,
                            contentDescription = null,
                            tint = colors.positive,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            pluralStringResource(R.plurals.home_streak, stats.streakDays, stats.streakDays),
                            style = MaterialTheme.typography.bodySmall,
                            color = fg.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            WeekBars(stats.perDay, goalMinutes)
        }
    }
}

/**
 * Seven mini bars, oldest first (today last). Days that hit the daily goal are drawn in the
 * positive accent; the rest are translucent, with today a touch brighter. A faint dashed line
 * marks the goal, and the bars share its scale so it always sits on-chart.
 */
@Composable
private fun WeekBars(perDay: List<DayMinutes>, goalMinutes: Int) {
    val colors = statsCardColors()
    val positive = colors.positive
    val fg = colors.fg
    val max = perDay.maxOfOrNull { it.minutes } ?: 0
    // Scale the bars against whichever is taller — the best day or the goal — so an unmet goal
    // line still lands inside the chart rather than clipping off the top.
    val scaleMax = maxOf(max, goalMinutes).coerceAtLeast(1)
    val goalLine = fg.copy(alpha = 0.3f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.drawBehind {
            if (goalMinutes > 0) {
                // The bar area is the top BAR_MAX_HEIGHT of the row (labels sit below it).
                val y = BAR_MAX_HEIGHT.dp.toPx() * (1f - goalMinutes.toFloat() / scaleMax)
                drawLine(
                    color = goalLine,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
        },
    ) {
        perDay.forEach { day ->
            val hitGoal = goalMinutes > 0 && day.minutes >= goalMinutes
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.height(BAR_MAX_HEIGHT.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val height = BAR_MAX_HEIGHT * day.minutes / scaleMax
                    Box(
                        modifier = Modifier
                            .width(7.dp)
                            .height(height.coerceAtLeast(3).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    hitGoal -> positive
                                    day.isToday -> fg.copy(alpha = 0.6f)
                                    day.minutes > 0 -> fg.copy(alpha = 0.45f)
                                    else -> fg.copy(alpha = 0.2f)
                                },
                            ),
                    )
                }
                Text(
                    dayLetter(day.epochDay),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day.isToday) fg else fg.copy(alpha = 0.6f),
                )
            }
        }
    }
}

private fun dayLetter(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).dayOfWeek
        .getDisplayName(TextStyle.NARROW, Locale.getDefault())
        .uppercase(Locale.getDefault())

@Composable
private fun ContinueCard(item: ContinueItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleAvatar(
                // The same icons the Library rows use for each kind, so the card reads as "that row".
                icon = when (item.kind) {
                    ContinueKind.CHAT -> Icons.Filled.ChatBubbleOutline
                    ContinueKind.TEXT -> Icons.Filled.Description
                    ContinueKind.BOOK -> Icons.AutoMirrored.Filled.MenuBook
                },
                highlighted = true,
            )
            // A book's second line is "author · 42% read"; either half can be missing.
            val subtitle = listOfNotNull(
                item.snippet,
                item.progressPercent?.let { stringResource(R.string.home_book_progress, it) },
            ).joinToString(" · ").ifBlank { null }
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** One contextual suggestion: something the app noticed is waiting, with how much of it there is. */
private data class Suggestion(
    val icon: ImageVector,
    val label: String,
    val title: String,
    val count: Int?,
    val onClick: () -> Unit,
)

/**
 * One session format: a fixed, always-available way to start, with no state behind it. Half the
 * height of a [Suggestion] card because it carries no count and needs no reading — you come to it
 * having already decided you want fifty cards.
 */
private data class SessionFormat(
    val icon: ImageVector,
    val title: String,
    val onClick: () -> Unit,
)

/**
 * The "Keep going" body: suggestions on the left, session formats on the right.
 *
 * The split is the point — the left column changes with what you have waiting and can be empty,
 * the right column never does, so the quickest way into a session doesn't depend on the app having
 * something to suggest. With nothing waiting the formats take the full width rather than sitting in
 * half a row.
 */
@Composable
private fun KeepGoing(
    ui: HomeUi,
    onOpenReview: (Int) -> Unit,
    onOpenRapidFire: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenConjugation: () -> Unit,
    onOpenCloze: () -> Unit,
    onOpenCourses: () -> Unit,
    onOpenNewChat: () -> Unit,
    onOpenRoutine: (Long) -> Unit,
) {
    val suggestions = buildList {
        // First: a routine left part-way is only on offer for a day, and it is the one thing here
        // that was already under way. No count — "step 3 of 7" is a position, not a backlog.
        ui.routine?.let { run ->
            add(
                Suggestion(
                    icon = run.type.icon,
                    label = stringResource(run.type.titleRes),
                    title = run.statusLine(),
                    count = null,
                ) { onOpenRoutine(run.runId) },
            )
        }
        if (ui.dueCards > 0) {
            // The number on the card is the sitting it opens, not everything waiting: a backlog of
            // two thousand is not a thing anyone sits down to, and a badge that says so reads as a
            // reason not to start. The rest goes in the label, small, when there is more than one
            // sitting of it — so a card that says 20 is not pretending 20 is all there is.
            val sitting = ui.dueCards.coerceAtMost(REVIEW_SESSION_CAP)
            add(
                Suggestion(
                    icon = Icons.Filled.Book,
                    label = if (ui.dueCards > sitting) {
                        stringResource(R.string.home_card_review_label_more, ui.dueCards)
                    } else {
                        stringResource(R.string.home_card_review_label)
                    },
                    title = stringResource(R.string.home_card_review),
                    count = sitting,
                ) { onOpenReview(sitting) },
            )
        }
        if (ui.unplayedAudio > 0) {
            add(
                Suggestion(
                    icon = Icons.Filled.Headphones,
                    label = stringResource(R.string.home_card_audio_label),
                    title = stringResource(R.string.home_card_audio),
                    count = ui.unplayedAudio,
                    onClick = onOpenAudio,
                ),
            )
        }
        // A course in flight, or a checkup that has come due. No count: "lesson 4 of 7" is progress,
        // not a backlog, and the card's job here is only to say the course is waiting.
        ui.courseNudge?.let { nudge ->
            add(
                Suggestion(
                    icon = Icons.Filled.School,
                    label = nudge.courseTitle,
                    title = if (nudge.checkupDue) {
                        stringResource(R.string.course_checkup_due)
                    } else {
                        nudge.lessonTitle.orEmpty()
                    },
                    count = null,
                    onClick = onOpenCourses,
                ),
            )
        }
        // Skipped when the continue-reading callout already points at the same chat.
        val unreadId = ui.latestUnreadChatId
        if (ui.unreadChats > 0 && unreadId != null &&
            ui.continueItems.none { it.kind == ContinueKind.CHAT && it.id == unreadId }
        ) {
            add(
                Suggestion(
                    icon = Icons.Filled.ChatBubbleOutline,
                    label = stringResource(R.string.home_card_chats_label),
                    title = stringResource(R.string.home_card_chats),
                    count = ui.unreadChats,
                ) { onOpenConversation(unreadId) },
            )
        }
    }
    val formats = buildList {
        // Two sizes rather than one: a few cards while the kettle boils, or a real sitting. Both
        // study ahead when little is due, so the count asked for is the count you get.
        add(
            SessionFormat(
                icon = Icons.Filled.Style,
                title = stringResource(R.string.home_format_cards, QUICK_REVIEW_COUNT),
            ) { onOpenReview(QUICK_REVIEW_COUNT) },
        )
        add(
            SessionFormat(
                icon = Icons.Filled.Style,
                title = stringResource(R.string.home_format_cards, LONG_REVIEW_COUNT),
            ) { onOpenReview(LONG_REVIEW_COUNT) },
        )
        add(
            SessionFormat(
                icon = Icons.Filled.Speed,
                title = stringResource(R.string.home_format_rapid),
                onClick = onOpenRapidFire,
            ),
        )
        if (ui.conjugationAvailable) {
            add(
                SessionFormat(
                    icon = Icons.Filled.Bolt,
                    title = stringResource(R.string.home_format_conjugation),
                    onClick = onOpenConjugation,
                ),
            )
        }
        // Hidden until the bank holds something: this column is the fastest way into a session, so a
        // format that opens on an empty screen would be worse than one that isn't offered yet.
        if (ui.clozeAvailable) {
            add(
                SessionFormat(
                    icon = Icons.Filled.Extension,
                    title = stringResource(R.string.home_format_cloze),
                    onClick = onOpenCloze,
                ),
            )
        }
        add(
            SessionFormat(
                icon = Icons.Filled.ChatBubbleOutline,
                title = stringResource(R.string.home_format_chat),
                onClick = onOpenNewChat,
            ),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                suggestions.forEach { SuggestionCard(it) }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            formats.forEach { FormatCard(it) }
        }
    }
}

/** A session format: icon, name, and nothing else — two of these stack into one suggestion card. */
@Composable
private fun FormatCard(format: SessionFormat) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(FORMAT_CARD_HEIGHT.dp)
            .clickable(onClick = format.onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                format.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                format.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SuggestionCard(card: Suggestion, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = card.onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                CircleAvatar(icon = card.icon)
                card.count?.let { CefrBadge(it.toString()) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                card.label,
                style = MaterialTheme.typography.labelMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                card.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val BAR_MAX_HEIGHT = 36

/** Cap on the "words to review" suggestion, which sizes itself to what is actually due. */
private const val REVIEW_SESSION_CAP = 20

// The two review formats. Deliberately not the same as REVIEW_SESSION_CAP: these say how long a
// sitting you want, not how much is due, and the session builder studies ahead to fill them.
private const val QUICK_REVIEW_COUNT = 10
private const val LONG_REVIEW_COUNT = 50

/** Half a suggestion card, so a pair of formats squares up against one card beside it. */
private const val FORMAT_CARD_HEIGHT = 62
