package com.copiosa.data.maintenance.tasks

import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import com.copiosa.data.repository.AudioRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes playlist audio the user finished listening to longer than their retention window ago.
 *
 * Per profile, because the retention setting is per profile — this used to read the *active*
 * profile's window while deleting across every language.
 */
@Singleton
class AudioPurgeTask @Inject constructor(
    private val audioRepository: AudioRepository,
) : MaintenanceTask {

    override val id = "audio_purge"
    override val scope = TaskScope.ALL_PROFILES
    override val needsLlm = false
    override val minIntervalMs: Long = TimeUnit.HOURS.toMillis(6)

    override suspend fun run(ctx: TaskContext) {
        audioRepository.pruneOldPlayed(ctx.profileTag, ctx.settings.autoDeletePlayedAfterDays)
    }
}
