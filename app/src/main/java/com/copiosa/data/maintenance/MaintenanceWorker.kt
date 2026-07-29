package com.copiosa.data.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Thin delegate to [MaintenanceRunner]; all logic lives outside so it stays JVM-testable.
 *
 * Always reports success: per-task failures are already caught and cadence-stamped by the
 * runner, so a WorkManager retry would only re-run a set that is guaranteed to be skipped.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: MaintenanceRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runner.runAll()
        return Result.success()
    }
}
