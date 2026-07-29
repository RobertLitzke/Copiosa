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
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.copiosa.R
import com.copiosa.data.stats.DayMinutes
import com.copiosa.data.stats.WeeklyFocusStats
import com.copiosa.ui.components.CefrBadge
import com.copiosa.ui.components.CircleAvatar
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.theme.skin.LocalAppSkin
import com.copiosa.ui.theme.skin.personaTexture
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Home tab: this week's focus-time summary, a "continue reading" callout, and quick-jump
 * suggestion cards. Everything reacts to profile switches via [HomeViewModel].
 */
@Composable
fun HomeScreen(
    onOpenConversation: (Long) -> Unit,
    onOpenText: (Long) -> Unit,
    onOpenReview: (Int) -> Unit,
    onOpenRapidFire: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val skin = LocalAppSkin.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .personaTexture(skin)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = stringResource(R.string.app_name))

        ui.stats?.let { StatsHeaderCard(it, ui.dailyGoalMinutes, onOpenStats) }

        ui.continueReading?.let { item ->
            SectionLabel(
                stringResource(R.string.home_continue_reading),
                Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
            )
            ContinueCard(
                item = item,
                onClick = { if (item.isText) onOpenText(item.id) else onOpenConversation(item.id) },
            )
        }

        SectionLabel(
            stringResource(R.string.home_keep_going),
            Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
        )
        SuggestionGrid(ui, onOpenReview, onOpenRapidFire, onOpenAudio, onOpenConversation)
        Spacer(Modifier.height(20.dp))
    }
}

/** The dark summary card: big minutes number, streak, and the week's per-day bars. Tapping opens stats. */
@Composable
private fun StatsHeaderCard(stats: WeeklyFocusStats, goalMinutes: Int, onClick: () -> Unit) {
    val fg = MaterialTheme.colorScheme.inverseOnSurface
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
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
                            tint = MaterialTheme.colorScheme.inversePrimary,
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
    val positive = MaterialTheme.colorScheme.inversePrimary
    val fg = MaterialTheme.colorScheme.inverseOnSurface
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
                icon = if (item.isText) Icons.AutoMirrored.Filled.MenuBook else Icons.Filled.ChatBubbleOutline,
                highlighted = true,
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.snippet?.let {
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

/** One quick-jump suggestion for the grid. */
private data class Suggestion(
    val icon: ImageVector,
    val label: String,
    val title: String,
    val count: Int?,
    val onClick: () -> Unit,
)

@Composable
private fun SuggestionGrid(
    ui: HomeUi,
    onOpenReview: (Int) -> Unit,
    onOpenRapidFire: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenConversation: (Long) -> Unit,
) {
    val cards = buildList {
        if (ui.dueCards > 0) {
            add(
                Suggestion(
                    icon = Icons.Filled.Book,
                    label = stringResource(R.string.home_card_review_label),
                    title = stringResource(R.string.home_card_review),
                    count = ui.dueCards,
                ) { onOpenReview(ui.dueCards.coerceAtMost(REVIEW_SESSION_CAP)) },
            )
        }
        add(
            Suggestion(
                icon = Icons.Filled.Speed,
                label = stringResource(R.string.home_card_listening_label),
                title = stringResource(R.string.home_card_listening),
                count = null,
                onClick = onOpenRapidFire,
            ),
        )
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
        // Skipped when the continue-reading callout already points at the same chat.
        val unreadId = ui.latestUnreadChatId
        if (ui.unreadChats > 0 && unreadId != null &&
            !(ui.continueReading?.isText == false && ui.continueReading.id == unreadId)
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
    cards.chunked(2).forEach { rowCards ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rowCards.forEach { card -> SuggestionCard(card, Modifier.weight(1f)) }
            if (rowCards.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SuggestionCard(card: Suggestion, modifier: Modifier = Modifier) {
    Card(modifier = modifier.clickable(onClick = card.onClick)) {
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
private const val REVIEW_SESSION_CAP = 20
