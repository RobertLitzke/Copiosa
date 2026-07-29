package com.copiosa.data.maintenance.tasks

import com.copiosa.data.local.FocusStatsDao
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rolls completed days' `focus_sessions` rows into `focus_daily_stats`, then prunes absorbed raw
 * rows past the retention window.
 *
 * Never touches today — the live UI reads today from raw sessions, and the tracker's midnight
 * split guarantees no open session lingers in a completed day. Idempotent: absorbing marks the
 * sessions `rolledUp` in the same transaction, so a re-run (or the runner's stamp-before-run crash
 * semantics) can't double-count; until a run happens, unrolled rows are still counted correctly by
 * the DAO's union queries.
 */
@Singleton
class FocusStatsRollupTask @Inject constructor(
    private val dao: FocusStatsDao,
) : MaintenanceTask {

    override val id = "focus_stats_rollup"

    // All profiles, so the overall (cross-profile) stats stay complete for inactive languages.
    override val scope = TaskScope.ALL_PROFILES
    override val needsLlm = false
    override val minIntervalMs: Long = TimeUnit.HOURS.toMillis(6)

    override suspend fun run(ctx: TaskContext) {
        val today = Instant.ofEpochMilli(ctx.now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        dao.unrolledBefore(ctx.profileTag, today)
            .groupBy { it.epochDay to it.activityType }
            .forEach { (key, sessions) ->
                dao.absorbIntoRollup(ctx.profileTag, key.first, key.second, sessions)
            }
        dao.pruneRolledBefore(today - RAW_RETENTION_DAYS)
    }

    companion object {
        /** How long absorbed raw sessions stay around for future per-session stats. */
        const val RAW_RETENTION_DAYS = 30L
    }
}
