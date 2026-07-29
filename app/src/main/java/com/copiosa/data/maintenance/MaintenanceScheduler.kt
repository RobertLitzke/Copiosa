package com.copiosa.data.maintenance

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues the maintenance work. Called once from `CopiosaApp.onCreate()`.
 *
 * Two requests: a periodic one that runs while the app is closed, and a one-shot kick so upkeep
 * also happens right after the user opens the app (Doze can delay the periodic one considerably,
 * so in practice the kick carries most of the load).
 */
@Singleton
class MaintenanceScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        val wm = WorkManager.getInstance(context)

        // Applied to both requests: without CONNECTED the LLM tasks would run offline, fail,
        // and still burn their cadence stamp.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            // UPDATE, not KEEP: KEEP would pin the first-ever interval and constraints forever,
            // so any later tuning would never reach existing installs.
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
                FLEX_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )

        wm.enqueueUniqueWork(
            ON_OPEN_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MaintenanceWorker>()
                .setConstraints(constraints)
                .build(),
        )
    }

    private companion object {
        const val PERIODIC_WORK = "copiosa-maintenance"
        const val ON_OPEN_WORK = "copiosa-maintenance-now"

        /**
         * Frequent enough for the slowest trickle that needs it (BookTranslateTask's ~90-minute
         * section). Cheap regardless: every task keeps its own cadence floor, so waking more often
         * lets those floors be hit on time rather than making any task run more.
         */
        const val INTERVAL_HOURS = 2L
        const val FLEX_HOURS = 1L
    }
}
