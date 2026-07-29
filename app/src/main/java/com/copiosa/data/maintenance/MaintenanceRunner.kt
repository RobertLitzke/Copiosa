package com.copiosa.data.maintenance

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs every registered [MaintenanceTask] for every language profile, applying the three gates
 * (scope, LLM budget, cadence) and isolating failures so one broken task can't starve the rest.
 *
 * The periodic worker and the on-open kick are separate WorkManager queues and can overlap, so
 * runs are serialised here; overlapping runs are then made harmless by each task's cadence floor.
 */
@Singleton
class MaintenanceRunner @Inject constructor(
    private val tasks: MaintenanceTasks,
    private val settings: ProfileSettingsSource,
    private val lastRun: LastRunStore,
) {
    private val mutex = Mutex()

    suspend fun runAll(now: Long = System.currentTimeMillis()) = mutex.withLock {
        val active = settings.activeTag()
        for (tag in settings.allTags()) {
            val profile = settings.settingsFor(tag)
            // Evaluated once per profile, never per task: one setting, one key lookup.
            val llmAllowed = profile.backgroundLlmTasks && settings.hasApiKey(profile.provider)

            for (task in tasks.all) {
                if (task.scope == TaskScope.ACTIVE_PROFILE && tag != active) continue
                if (task.needsLlm && !llmAllowed) continue
                if (now - lastRun.get(task.id, tag) < task.minIntervalMs) continue

                // Stamped before running, not after: a task that throws — or whose process is
                // killed mid-run — must not re-run on the very next kick.
                lastRun.set(task.id, tag, now)
                try {
                    task.run(TaskContext(tag, profile, now))
                } catch (e: CancellationException) {
                    // The worker is being torn down; don't swallow it into the next task.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Maintenance task ${task.id} failed for $tag", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "MaintenanceRunner"
    }
}
