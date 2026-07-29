package com.copiosa.data.maintenance

import com.copiosa.data.settings.AppSettings

/** Which language profiles a task runs for. */
enum class TaskScope {
    /** Runs once per known profile; the task decides what "for this language" means. */
    ALL_PROFILES,

    /** Runs only for the profile the user is currently in. For tasks with real per-run cost. */
    ACTIVE_PROFILE,
}

/** Everything a task needs for one (profile, run) pair. */
data class TaskContext(
    val profileTag: String,
    val settings: AppSettings,
    val now: Long,
)

/**
 * One unit of background upkeep, run by [MaintenanceRunner] from a WorkManager worker.
 *
 * Tasks are plain injectable classes so they stay JVM-testable — nothing here touches the
 * Android worker. Implementations should be idempotent and cheap to no-op: the runner may
 * invoke them on every app open, and [minIntervalMs] is the only thing keeping that in check.
 */
interface MaintenanceTask {
    /** Stable bookkeeping key, persisted in [LastRunStore] — never rename. */
    val id: String

    val scope: TaskScope

    /** Whether this task spends LLM calls; gated by [AppSettings.backgroundLlmTasks]. */
    val needsLlm: Boolean

    /** Minimum gap between runs for one profile. This is the whole cadence mechanism. */
    val minIntervalMs: Long

    suspend fun run(ctx: TaskContext)
}
