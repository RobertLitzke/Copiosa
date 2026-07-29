package com.copiosa.data.maintenance.tasks

import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.ReviewReminderNotifier
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts an at-most-daily reminder when enough cards are waiting for review, per profile and only
 * when the profile's opt-in toggle is on. Piggybacks on the maintenance cadence (12-hour worker +
 * on-open kick); the 20-hour floor keeps it to roughly one nudge a day.
 */
@Singleton
class ReviewReminderTask @Inject constructor(
    private val flashcardDao: FlashcardDao,
    private val notifier: ReviewReminderNotifier,
) : MaintenanceTask {

    override val id = "review_reminder"
    override val scope = TaskScope.ALL_PROFILES
    override val needsLlm = false
    override val minIntervalMs: Long = TimeUnit.HOURS.toMillis(20)

    override suspend fun run(ctx: TaskContext) {
        if (!ctx.settings.reviewReminders) return
        val due = flashcardDao.countDue(ctx.profileTag, ctx.now)
        // A couple of due cards isn't worth an interruption; the backlog threshold keeps the
        // reminder meaningful.
        if (due >= MIN_DUE_TO_NOTIFY) notifier.notifyDue(ctx.profileTag, due)
    }

    companion object {
        const val MIN_DUE_TO_NOTIFY = 5
    }
}
