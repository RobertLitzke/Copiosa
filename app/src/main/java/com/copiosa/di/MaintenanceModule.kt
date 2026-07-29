package com.copiosa.di

import com.copiosa.data.maintenance.AndroidReviewReminderNotifier
import com.copiosa.data.maintenance.DataStoreLastRunStore
import com.copiosa.data.maintenance.LastRunStore
import com.copiosa.data.maintenance.ProfileSettingsSource
import com.copiosa.data.maintenance.ReviewReminderNotifier
import com.copiosa.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The two maintenance seams that exist as interfaces so [com.copiosa.data.maintenance.MaintenanceRunner]
 * can be unit-tested without a Context. Provided rather than bound, matching how DatabaseModule
 * provides DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object MaintenanceModule {

    @Provides
    @Singleton
    fun provideLastRunStore(impl: DataStoreLastRunStore): LastRunStore = impl

    @Provides
    @Singleton
    fun provideProfileSettingsSource(impl: SettingsRepository): ProfileSettingsSource = impl

    @Provides
    @Singleton
    fun provideReviewReminderNotifier(impl: AndroidReviewReminderNotifier): ReviewReminderNotifier = impl
}
