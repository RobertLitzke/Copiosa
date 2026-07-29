package com.copiosa.data.maintenance

import com.copiosa.data.settings.AppSettings
import com.copiosa.domain.model.ProviderId

/**
 * The slice of settings [MaintenanceRunner] needs, narrowed to suspend calls.
 *
 * Exists so the runner can be unit-tested without DataStore or a Context;
 * `SettingsRepository` implements it directly.
 */
interface ProfileSettingsSource {
    suspend fun activeTag(): String
    suspend fun allTags(): List<String>
    suspend fun settingsFor(tag: String): AppSettings
    fun hasApiKey(provider: ProviderId): Boolean
}
