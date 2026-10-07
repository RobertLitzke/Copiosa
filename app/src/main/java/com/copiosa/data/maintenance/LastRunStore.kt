package com.copiosa.data.maintenance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-(task, profile) cadence bookkeeping.
 *
 * An interface so [MaintenanceRunner] is testable without DataStore. The data is bounded and
 * scalar — a handful of tasks times a handful of profiles — so it lives in preferences rather
 * than Room, where every new task would otherwise cost a schema migration.
 */
interface LastRunStore {
    suspend fun get(taskId: String, tag: String): Long
    suspend fun set(taskId: String, tag: String, at: Long)
}

private val Context.maintenanceStore: DataStore<Preferences> by
    preferencesDataStore(name = "maintenance")

/**
 * DataStore-backed [LastRunStore]. Deliberately its own preferences file so maintenance
 * bookkeeping never mixes into the "p.{tag}.{field}" settings namespace.
 */
@Singleton
class DataStoreLastRunStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : LastRunStore {

    override suspend fun get(taskId: String, tag: String): Long =
        context.maintenanceStore.data.first()[lastRunKey(taskId, tag)] ?: 0L

    override suspend fun set(taskId: String, tag: String, at: Long) {
        context.maintenanceStore.edit { it[lastRunKey(taskId, tag)] = at }
    }

    private fun lastRunKey(taskId: String, tag: String) =
        longPreferencesKey("t.$taskId.$tag.last_run")
}
