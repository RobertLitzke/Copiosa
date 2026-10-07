package com.copiosa.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.stats.ActivityMinutes
import com.copiosa.data.stats.ClozeStats
import com.copiosa.data.stats.ConjugationStats
import com.copiosa.data.stats.DayMinutes
import com.copiosa.data.stats.ReadingStats
import com.copiosa.data.stats.TimeStats
import com.copiosa.data.stats.VocabStats
import com.copiosa.domain.conjugation.IrregularityFamily
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.domain.vocab.freq.BucketCoverage
import com.copiosa.ui.conjugation.label
import com.copiosa.ui.vocab.scopeLabel
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.SegmentedToggle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The stats screen reached by tapping the home tracker. Shows all-time time/consistency, vocabulary,
 * and reading/listening figures for the active language (with an all-languages toggle), plus a
 * guarded "clear" that wipes only the study-time history.
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.stats_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SegmentedToggle(
                selected = ui.scope.ordinal,
                labels = listOf(langLabel(ui.activeLang), stringResource(R.string.stats_scope_all)),
                onSelect = { viewModel.setScope(StatsScope.entries[it]) },
            )

            ui.stats?.let { stats ->
                TimeSection(stats.time)
                VocabSection(stats.vocab)
                ConjugationSection(stats.conjugation)
                ClozeSection(stats.cloze)
                ReadingSection(stats.reading)

                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.width(18.dp))
                    Text(
                        stringResource(R.string.stats_clear),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.stats_clear_confirm_title)) },
            text = {
                Text(
                    if (ui.scope == StatsScope.ALL) {
                        stringResource(R.string.stats_clear_confirm_body_all)
                    } else {
                        stringResource(R.string.stats_clear_confirm_body_active, langLabel(ui.activeLang))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearStats()
                    confirmClear = false
                }) { Text(stringResource(R.string.stats_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun TimeSection(t: TimeStats) {
    StatCard(stringResource(R.string.stats_time_section)) {
        StatTileGrid(
            listOf(
                Tile(formatMinutes(t.totalMinutes), stringResource(R.string.stats_total_time)),
                Tile(daysValue(t.currentStreak), stringResource(R.string.stats_current_streak)),
                Tile(daysValue(t.longestStreak), stringResource(R.string.stats_longest_streak)),
                Tile(daysValue(t.daysStudied), stringResource(R.string.stats_days_studied)),
                Tile(formatMinutes(t.bestDayMinutes), stringResource(R.string.stats_best_day)),
                Tile(formatMinutes(t.avgMinutesPerActiveDay), stringResource(R.string.stats_daily_average)),
            ),
        )
        Spacer(Modifier.height(4.dp))
        SectionLabel(stringResource(R.string.stats_daily_time))
        DailyTimeChart(t.heatmap.takeLast(DAILY_CHART_DAYS))
        if (t.byActivity.any { it.minutes > 0 }) {
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.stats_by_activity))
            ActivityBreakdown(t.byActivity)
        }
        Spacer(Modifier.height(4.dp))
        SectionLabel(stringResource(R.string.stats_heatmap_title))
        Heatmap(t.heatmap)
    }
}

@Composable
private fun VocabSection(v: VocabStats) {
    StatCard(stringResource(R.string.stats_vocab_section)) {
        StatTileGrid(
            listOf(
                Tile("${v.totalCards}", stringResource(R.string.stats_total_cards)),
                Tile("${v.reviewsDone}", stringResource(R.string.stats_reviews_done)),
                Tile(v.reviewAccuracyPct?.let { "$it%" } ?: "—", stringResource(R.string.stats_accuracy)),
                Tile("${v.addedLast7}", stringResource(R.string.stats_added_7)),
                Tile("${v.addedLast30}", stringResource(R.string.stats_added_30)),
                Tile("${v.suspendedCards}", stringResource(R.string.stats_frozen)),
            ),
        )
        Spacer(Modifier.height(4.dp))
        SectionLabel(stringResource(R.string.stats_card_states))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MiniStat(stringResource(R.string.stats_state_new), v.newCards, Modifier.weight(1f))
            MiniStat(stringResource(R.string.stats_state_learning), v.learningCards, Modifier.weight(1f))
            MiniStat(stringResource(R.string.stats_state_known), v.reviewCards, Modifier.weight(1f))
        }
        if (v.reviewsPerDay.any { it > 0 }) {
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.stats_reviews_per_day))
            Sparkline(v.reviewsPerDay)
        }
        if (v.ratingCounts.values.sum() > 0) {
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.stats_answer_distribution))
            RatingDistribution(v.ratingCounts)
        }
        if (v.coverage.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.stats_coverage_section))
            CoverageBars(v.coverage)
        }
    }
}

/**
 * Frequency coverage, one row per band. Two figures, not one: how many of the band's words are in
 * the deck at all, and how many have actually stuck. Seeding a few hundred words moves the first
 * number the moment it finishes and the second not at all, which is the honest picture — showing
 * only "in deck" would let a fresh seed read as fluency.
 */
@Composable
private fun CoverageBars(coverage: List<BucketCoverage>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        coverage.forEach { row ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        row.bucket.scopeLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(
                            R.string.stats_coverage_row,
                            row.inDeck,
                            row.inDeckPercent,
                            row.mature,
                            row.maturePercent,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // One track, two fills: the paler "in deck" bar with the solid "known" bar over it.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(row.inDeckPercent / 100f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(row.maturePercent / 100f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.stats_coverage_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Conjugation drilling. Shown only once there is something to show — an empty card would just be a
 * row of dashes on every profile that has never opened the drill.
 */
@Composable
private fun ConjugationSection(c: ConjugationStats) {
    if (c.drills == 0) return
    StatCard(stringResource(R.string.stats_conjugation_section)) {
        StatTileGrid(
            listOf(
                Tile("${c.drills}", stringResource(R.string.stats_conj_drills)),
                Tile(c.accuracyPercent?.let { "$it%" } ?: "—", stringResource(R.string.stats_accuracy)),
                Tile(
                    if (c.avgLatencyMs == 0L) "—" else stringResource(R.string.conj_results_seconds, c.avgLatencyMs / 1000f),
                    stringResource(R.string.stats_conj_recall),
                ),
                Tile("${c.itemsMastered}", stringResource(R.string.stats_conj_mastered)),
                Tile("${c.itemsSeen}", stringResource(R.string.stats_conj_seen)),
            ),
        )
        if (c.byFamily.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.conj_results_by_family))
            c.byFamily.forEach { (slug, total, correct) ->
                BucketRow(IrregularityFamily.fromSlug(slug)?.label() ?: slug, correct, total)
            }
        }
        if (c.byTense.size > 1) {
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.conj_results_by_tense))
            c.byTense.forEach { (slug, total, correct) ->
                BucketRow(Tense.fromSlug(slug)?.label() ?: slug, correct, total)
            }
        }
    }
}

@Composable
private fun ClozeSection(c: ClozeStats) {
    // A bank with nothing answered in it isn't a statistic yet, and an empty card is noise.
    if (c.answered == 0) return
    StatCard(stringResource(R.string.stats_cloze_section)) {
        StatTileGrid(
            listOf(
                Tile("${c.answered}", stringResource(R.string.stats_cloze_answered)),
                Tile(c.accuracyPercent?.let { "$it%" } ?: "—", stringResource(R.string.stats_accuracy)),
                Tile(
                    if (c.avgLatencyMs == 0L) {
                        "—"
                    } else {
                        stringResource(R.string.conj_results_seconds, c.avgLatencyMs / 1000f)
                    },
                    stringResource(R.string.stats_cloze_speed),
                ),
                // Banked and solid are shown apart for the same reason vocab coverage splits them:
                // mining a hundred sentences is not the same as knowing them.
                Tile("${c.mature}", stringResource(R.string.stats_cloze_solid)),
                Tile("${c.bankSize}", stringResource(R.string.stats_cloze_bank)),
            ),
        )
    }
}

@Composable
private fun BucketRow(label: String, correct: Int, total: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            "$correct/$total",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Grades in review order (Again→Easy) with a fixed, scheme-aware colour each. */
private val RATING_ORDER = listOf(ReviewRating.AGAIN, ReviewRating.HARD, ReviewRating.GOOD, ReviewRating.EASY)

/** A proportional stacked bar of how often each grade was chosen, with a labelled + counted legend. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingDistribution(counts: Map<ReviewRating, Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            RATING_ORDER.forEach { rating ->
                val c = counts[rating] ?: 0
                if (c > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(c.toFloat())
                            .background(ratingColor(rating)),
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RATING_ORDER.forEach { rating ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(ratingColor(rating)),
                    )
                    Text(
                        " ${ratingLabel(rating)} ${counts[rating] ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Fixed, semantic grade hues (fail→ace), independent of the profile's accent palette — under a
 * red-accent country theme, deriving these from the scheme collides Again/Good (both red) and
 * Hard/Easy (both gold). These mid-tones stay legible on the card surface in light and dark.
 */
private fun ratingColor(rating: ReviewRating): Color = when (rating) {
    ReviewRating.AGAIN -> Color(0xFFD64545) // red
    ReviewRating.HARD -> Color(0xFFE0952B) // amber
    ReviewRating.GOOD -> Color(0xFF3FA45B) // green
    ReviewRating.EASY -> Color(0xFF3E7CB1) // blue
}

@Composable
private fun ratingLabel(rating: ReviewRating): String = stringResource(
    when (rating) {
        ReviewRating.AGAIN -> R.string.rating_again
        ReviewRating.HARD -> R.string.rating_hard
        ReviewRating.GOOD -> R.string.rating_good
        ReviewRating.EASY -> R.string.rating_easy
    },
)

@Composable
private fun ReadingSection(r: ReadingStats) {
    StatCard(stringResource(R.string.stats_reading_section)) {
        StatTileGrid(
            listOf(
                Tile("${r.conversations}", stringResource(R.string.stats_conversations)),
                Tile("${r.messages}", stringResource(R.string.stats_messages)),
                Tile("${r.textsFinished}", stringResource(R.string.stats_texts)),
                Tile("${r.audioPlayed}", stringResource(R.string.stats_audio_played)),
                Tile(formatMinutes(r.listeningMinutes), stringResource(R.string.stats_listening)),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(title)
            content()
        }
    }
}

private data class Tile(val value: String, val label: String)

/** Two-per-row grid of big-number stat tiles. */
@Composable
private fun StatTileGrid(tiles: List<Tile>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { tile -> StatTile(tile, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(tile: Tile, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            tile.value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            tile.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Horizontal bars, one per activity type, scaled to the busiest activity. */
@Composable
private fun ActivityBreakdown(items: List<ActivityMinutes>) {
    val max = items.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.filter { it.minutes > 0 }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    activityLabel(item.type),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(76.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(item.minutes.toFloat() / max)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    formatMinutes(item.minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).width(56.dp),
                )
            }
        }
    }
}

/** Days shown in the daily-time chart — the tail of the 12-week window the heatmap already carries. */
private const val DAILY_CHART_DAYS = 30

private val CHART_HEIGHT = 116.dp
private val AXIS_WIDTH = 48.dp

/** Room for one axis label, so the top and bottom ones sit inside the plot rather than overhanging it. */
private val AXIS_LABEL_HEIGHT = 14.dp

/** Axis maxima that give round halves; anything past the last one rounds up to whole hours. */
private val AXIS_STEPS = listOf(15, 30, 60, 90, 120, 180, 240, 300, 360, 480, 600, 720)

/**
 * Daily study time as a bar chart (oldest left, today right) — the home card's week bars grown up:
 * a labelled minute scale down the left, and a tap on any bar reads out that day's exact figure.
 */
@Composable
private fun DailyTimeChart(days: List<DayMinutes>) {
    if (days.isEmpty()) return
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val picked = selected?.let { days[it] }
    val scaleMax = niceAxisMax(days.maxOfOrNull { it.minutes } ?: 0)

    val bar = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = muted.copy(alpha = 0.2f)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurface

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The read-out for the tapped day; the hint holds the same line so the chart never shifts.
        Text(
            picked?.let { "${dayDateLabel(it.epochDay)} · ${formatMinutes(it.minutes)}" }
                ?: stringResource(R.string.stats_chart_hint),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (picked == null) FontWeight.Normal else FontWeight.SemiBold,
            color = if (picked == null) muted else MaterialTheme.colorScheme.onSurface,
        )
        Row {
            Box(modifier = Modifier.width(AXIS_WIDTH).height(CHART_HEIGHT)) {
                listOf(1f, 0.5f, 0f).forEach { frac ->
                    Text(
                        formatMinutes((scaleMax * frac).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (CHART_HEIGHT - AXIS_LABEL_HEIGHT) * (1f - frac))
                            .padding(end = 6.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(CHART_HEIGHT)
                    .drawBehind {
                        listOf(0f, 0.5f, 1f).forEach { frac ->
                            val y = (size.height - AXIS_LABEL_HEIGHT.toPx()) * (1f - frac) +
                                AXIS_LABEL_HEIGHT.toPx() / 2
                            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        }
                    }
                    .pointerInput(days.size) {
                        detectTapGestures { offset ->
                            // Bars share the width evenly, so the column under the finger is pure arithmetic.
                            val i = (offset.x / (size.width / days.size.toFloat())).toInt().coerceIn(days.indices)
                            selected = if (i == selected) null else i
                        }
                    },
            ) {
                Row(
                    // Bars stand on the zero gridline, which sits half a label above the box floor.
                    modifier = Modifier.fillMaxSize().padding(bottom = AXIS_LABEL_HEIGHT / 2),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    days.forEachIndexed { i, day ->
                        val height = (CHART_HEIGHT - AXIS_LABEL_HEIGHT) * (day.minutes.toFloat() / scaleMax)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(height.coerceAtLeast(2.dp))
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    when {
                                        i == selected -> highlight
                                        day.minutes <= 0 -> empty
                                        day.isToday -> bar
                                        else -> bar.copy(alpha = 0.55f)
                                    },
                                ),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = AXIS_WIDTH),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(rowDateLabel(days.first().epochDay), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(rowDateLabel(days.last().epochDay), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

/** Rounds the busiest day up to a scale maximum whose halfway mark is still a round figure. */
private fun niceAxisMax(maxMinutes: Int): Int =
    AXIS_STEPS.firstOrNull { it >= maxMinutes } ?: (((maxMinutes + 119) / 120) * 120)

/**
 * A compact line sparkline of daily counts (oldest left). A faint baseline anchors it; a dot marks
 * the most recent day. Filled area under the line gives it weight at this size.
 */
@Composable
private fun Sparkline(values: List<Int>) {
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.15f)
    val baseline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
        if (values.size < 2) return@Canvas
        val stepX = size.width / (values.size - 1)
        val topPad = 3.dp.toPx()
        val h = size.height - topPad
        fun yFor(v: Int) = topPad + h * (1f - v.toFloat() / max)
        val points = values.mapIndexed { i, v -> Offset(stepX * i, yFor(v)) }

        // Filled area under the curve.
        val area = Path().apply {
            moveTo(points.first().x, size.height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, size.height)
            close()
        }
        drawPath(area, color = fill)

        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, color = line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(line, radius = 2.5.dp.toPx(), center = points.last())
    }
}

/**
 * A 12-week density grid, Instagram-style: the most recent day sits top-left, time running newest→
 * oldest left-to-right and down the rows (two weeks each). Each row's newest (leftmost) date labels it.
 */
@Composable
private fun Heatmap(days: List<DayMinutes>) {
    val max = days.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    // Newest first, two weeks per row: today lands top-left, oldest day bottom-right.
    val rows = days.reversed().chunked(14)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rowDateLabel(row.first().epochDay),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { day ->
                        val color = if (day.minutes <= 0) {
                            empty
                        } else {
                            base.copy(alpha = 0.25f + 0.75f * (day.minutes.toFloat() / max))
                        }
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(14.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(color),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

/** "45m" / "3h" / "3h 20m". */
private fun formatMinutes(min: Int): String {
    if (min < 60) return "${min}m"
    val h = min / 60
    val m = min % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}

/** A day count with its unit, e.g. "1 day" / "6 days" — so a bare streak number isn't left context-less. */
@Composable
private fun daysValue(n: Int): String = pluralStringResource(R.plurals.stats_days, n, n)

/** "Jul 9" — the label for a heatmap row's starting day, and for the daily chart's end dates. */
private fun rowDateLabel(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))

/** "Wed, Jul 9" — the read-out for a tapped bar, where the weekday is worth knowing. */
private fun dayDateLabel(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))

@Composable
private fun activityLabel(type: FocusActivityType): String = stringResource(
    when (type) {
        FocusActivityType.READING -> R.string.activity_reading
        FocusActivityType.READING_BOOK -> R.string.activity_reading_book
        FocusActivityType.LISTENING -> R.string.activity_listening
        FocusActivityType.REVIEW -> R.string.activity_review
        FocusActivityType.QUIZ -> R.string.activity_quiz
        FocusActivityType.ROUTINE -> R.string.activity_routine
        FocusActivityType.CONJUGATION -> R.string.activity_conjugation
        FocusActivityType.CLOZE -> R.string.activity_cloze
        FocusActivityType.LISTEN_REBUILD -> R.string.activity_listen_rebuild
    },
)

private fun langLabel(tag: String): String =
    if (tag.isBlank()) tag
    else Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).ifBlank { tag }
