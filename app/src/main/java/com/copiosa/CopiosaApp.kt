package com.copiosa

import android.app.Application
import com.copiosa.data.generation.AudioCompactor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CopiosaApp : Application() {
    @Inject lateinit var audioCompactor: AudioCompactor

    override fun onCreate() {
        super.onCreate()
        audioCompactor.start()
    }
}
