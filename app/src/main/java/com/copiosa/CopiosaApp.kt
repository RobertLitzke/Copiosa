package com.copiosa

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.copiosa.data.generation.AudioCompactor
import com.copiosa.data.maintenance.MaintenanceScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CopiosaApp : Application(), Configuration.Provider {
    @Inject lateinit var audioCompactor: AudioCompactor
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var maintenanceScheduler: MaintenanceScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        audioCompactor.start()
        maintenanceScheduler.start()
    }
}
