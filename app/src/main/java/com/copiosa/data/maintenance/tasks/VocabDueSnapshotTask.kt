package com.copiosa.data.maintenance.tasks

import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.maintenance.LastRunStore
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches how many cards are due per profile, so a badge can be shown without loading any cards.
 *
 * This is a convenience, not a correctness mechanism: `FlashcardRepository.observeDue` re-evaluates
 * its own cutoff on a ticker, so the review screen is always live regardless of this snapshot.
 */
@Singleton
class VocabDueSnapshotTask @Inject constructor(
    private val flashcardDao: FlashcardDao,
    private val lastRun: LastRunStore,
) : MaintenanceTask {

    override val id = "vocab_due_snapshot"
    override val scope = TaskScope.ALL_PROFILES
    override val needsLlm = false
    override val minIntervalMs: Long = TimeUnit.MINUTES.toMillis(15)

    override suspend fun run(ctx: TaskContext) {
        val due = flashcardDao.countDue(ctx.profileTag, ctx.now)
        lastRun.putLong(dueCountKey(ctx.profileTag), due.toLong())
    }

    companion object {
        /** Key holding the cached due count for [tag]; read via [LastRunStore.getLong]. */
        fun dueCountKey(tag: String) = "vocab.$tag.due_count"
    }
}
