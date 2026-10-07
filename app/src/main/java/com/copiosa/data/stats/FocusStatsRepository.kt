package com.copiosa.data.stats

import com.copiosa.data.local.DayTotal
import com.copiosa.data.local.FocusStatsDao
import com.copiosa.domain.stats.Streak
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One day of the home-header bar chart. */
data class DayMinutes(val epochDay: Long, val minutes: Int, val isToday: Boolean)

/** The home-header stats payload for one profile (or all profiles combined). */
data class WeeklyFocusStats(
    val weekMinutes: Int,
    val streakDays: Int,
    /** The trailing 7 days, oldest first — always 7 entries; today is the last. */
    val perDay: List<DayMinutes>,
)

/**
 * Read-side aggregation over [FocusStatsDao]. Along with `FocusTimeTracker` (the write side),
 * this is the seam a future stats-detail screen extends: add DAO queries (arbitrary ranges,
 * per-activity breakdowns) and expose them here — nothing else needs to change.
 *
 * Durations leave here as whole minutes; the database stays in milliseconds.
 */
@Singleton
class FocusStatsRepository @Inject constructor(
    private val dao: FocusStatsDao,
) {
    // Seams for tests; production always uses the defaults.
    internal var now: () -> Long = System::currentTimeMillis
    internal var zone: () -> ZoneId = ZoneId::systemDefault

    /** The trailing week's minutes, day streak, and per-day bars for one profile. */
    fun observeWeekly(lang: String): Flow<WeeklyFocusStats> =
        weekly(
            { from -> dao.observeDayTotals(lang, from) },
            { from -> dao.observeActiveDays(lang, from) },
        )

    private fun weekly(
        dayTotals: (fromDay: Long) -> Flow<List<DayTotal>>,
        activeDays: (fromDay: Long) -> Flow<List<Long>>,
    ): Flow<WeeklyFocusStats> {
        val today = today()
        // The trailing 7-day window: [today - 6 .. today].
        val from = today - (WINDOW_DAYS - 1)
        return combine(dayTotals(from), activeDays(today - STREAK_LOOKBACK_DAYS)) { totals, active ->
            val byDay = totals.associate { it.epochDay to it.totalMs }
            WeeklyFocusStats(
                weekMinutes = (totals.sumOf { it.totalMs } / MS_PER_MINUTE).toInt(),
                streakDays = Streak.compute(active, today),
                perDay = (0 until WINDOW_DAYS).map { offset ->
                    val day = from + offset
                    DayMinutes(
                        epochDay = day,
                        minutes = ((byDay[day] ?: 0L) / MS_PER_MINUTE).toInt(),
                        isToday = day == today,
                    )
                },
            )
        }
    }

    private fun today(): Long =
        Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toEpochDay()

    private companion object {
        /** Bars and the headline total both cover the last week of days, today inclusive. */
        const val WINDOW_DAYS = 7
        const val MS_PER_MINUTE = 60_000L

        /** Bounds the streak query; a year-plus streak just reads as "366+". */
        const val STREAK_LOOKBACK_DAYS = 366L
    }
}
