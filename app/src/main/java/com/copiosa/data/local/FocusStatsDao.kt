package com.copiosa.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.copiosa.domain.stats.FocusActivityType
import kotlinx.coroutines.flow.Flow

/** One day's total focus time, summed across activity types. */
data class DayTotal(val epochDay: Long, val totalMs: Long)

/** All-time focus time for one activity type, for the stats-screen breakdown. */
data class ActivityTotal(val activityType: FocusActivityType, val totalMs: Long)

/**
 * Focus-time storage: the raw `focus_sessions` log plus the `focus_daily_stats` rollups.
 *
 * Every aggregate query is the union of rollup rows and not-yet-rolled-up raw rows
 * (`rolledUp = 0`), so totals are correct whether or not the rollup task has run, and today —
 * which is never rolled up — is always live. Room observes both tables named in the union, so the
 * Flows re-emit on heartbeat writes and on rollup writes alike.
 */
@Dao
interface FocusStatsDao {
    @Insert
    suspend fun insert(session: FocusSessionEntity): Long

    @Query("UPDATE focus_sessions SET durationMs = :durationMs WHERE id = :id")
    suspend fun setDuration(id: Long, durationMs: Long)

    @Query("DELETE FROM focus_sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "SELECT epochDay, SUM(ms) AS totalMs FROM (" +
            "SELECT epochDay, totalDurationMs AS ms FROM focus_daily_stats " +
            "WHERE langTag = :lang AND epochDay >= :fromDay " +
            "UNION ALL " +
            "SELECT epochDay, durationMs AS ms FROM focus_sessions " +
            "WHERE langTag = :lang AND epochDay >= :fromDay AND rolledUp = 0" +
            ") GROUP BY epochDay ORDER BY epochDay ASC",
    )
    fun observeDayTotals(lang: String, fromDay: Long): Flow<List<DayTotal>>

    /** Distinct days with any focus time, newest first — the streak source. */
    @Query(
        "SELECT DISTINCT epochDay FROM (" +
            "SELECT epochDay FROM focus_daily_stats WHERE langTag = :lang AND epochDay >= :fromDay " +
            "UNION ALL " +
            "SELECT epochDay FROM focus_sessions " +
            "WHERE langTag = :lang AND epochDay >= :fromDay AND rolledUp = 0" +
            ") ORDER BY epochDay DESC",
    )
    fun observeActiveDays(lang: String, fromDay: Long): Flow<List<Long>>

    // --- stats screen: all-time, cross-language-optional aggregates ---
    // A null [lang] means "every profile combined"; otherwise the query is scoped to that profile.

    /** Every day with focus time (oldest first), all-time. The source for streaks, totals, and the heatmap. */
    @Query(
        "SELECT epochDay, SUM(ms) AS totalMs FROM (" +
            "SELECT epochDay, totalDurationMs AS ms FROM focus_daily_stats WHERE (:lang IS NULL OR langTag = :lang) " +
            "UNION ALL " +
            "SELECT epochDay, durationMs AS ms FROM focus_sessions " +
            "WHERE (:lang IS NULL OR langTag = :lang) AND rolledUp = 0" +
            ") GROUP BY epochDay ORDER BY epochDay ASC",
    )
    fun observeDayTotalsScoped(lang: String?): Flow<List<DayTotal>>

    /** All-time focus time per activity type, for the breakdown bars. */
    @Query(
        "SELECT activityType, SUM(ms) AS totalMs FROM (" +
            "SELECT activityType, totalDurationMs AS ms FROM focus_daily_stats WHERE (:lang IS NULL OR langTag = :lang) " +
            "UNION ALL " +
            "SELECT activityType, durationMs AS ms FROM focus_sessions " +
            "WHERE (:lang IS NULL OR langTag = :lang) AND rolledUp = 0" +
            ") GROUP BY activityType",
    )
    fun observeActivityTotals(lang: String?): Flow<List<ActivityTotal>>

    // --- stats screen: clear focus-time history ---

    /** Wipes raw focus sessions for a profile (or all profiles when [lang] is null). */
    @Query("DELETE FROM focus_sessions WHERE (:lang IS NULL OR langTag = :lang)")
    suspend fun clearSessions(lang: String?)

    /** Wipes the daily focus rollups for a profile (or all profiles when [lang] is null). */
    @Query("DELETE FROM focus_daily_stats WHERE (:lang IS NULL OR langTag = :lang)")
    suspend fun clearDailyStats(lang: String?)

    // --- rollup task support ---

    @Query("SELECT * FROM focus_sessions WHERE langTag = :lang AND rolledUp = 0 AND epochDay < :beforeDay")
    suspend fun unrolledBefore(lang: String, beforeDay: Long): List<FocusSessionEntity>

    @Query("SELECT * FROM focus_daily_stats WHERE langTag = :lang AND epochDay = :day AND activityType = :type")
    suspend fun rollupRow(lang: String, day: Long, type: FocusActivityType): FocusDailyStatEntity?

    @Upsert
    suspend fun upsertRollup(row: FocusDailyStatEntity)

    @Query("UPDATE focus_sessions SET rolledUp = 1 WHERE id IN (:ids)")
    suspend fun markRolledUp(ids: List<Long>)

    /**
     * Absorbs one (day, activity) group into its rollup row and marks the sessions, atomically —
     * a crash between the two writes can never double-count a session.
     */
    @Transaction
    suspend fun absorbIntoRollup(lang: String, day: Long, type: FocusActivityType, sessions: List<FocusSessionEntity>) {
        val existing = rollupRow(lang, day, type)
        upsertRollup(
            FocusDailyStatEntity(
                langTag = lang,
                epochDay = day,
                activityType = type,
                totalDurationMs = (existing?.totalDurationMs ?: 0L) + sessions.sumOf { it.durationMs },
                sessionCount = (existing?.sessionCount ?: 0) + sessions.size,
            ),
        )
        markRolledUp(sessions.map { it.id })
    }

    /** Prunes absorbed raw rows past the retention window; recent raw detail stays queryable. */
    @Query("DELETE FROM focus_sessions WHERE rolledUp = 1 AND epochDay < :beforeDay")
    suspend fun pruneRolledBefore(beforeDay: Long)
}
