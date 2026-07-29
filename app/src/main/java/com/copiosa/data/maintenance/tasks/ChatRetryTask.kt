package com.copiosa.data.maintenance.tasks

import com.copiosa.data.generation.GenerationManager
import com.copiosa.data.local.ConversationDao
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import com.copiosa.domain.maintenance.RetryBackoff
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.Role
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-runs a couple of chats whose reply never arrived — a batch interrupted by a flaky network or
 * process death would otherwise sit showing "tap to retry" until the user noticed.
 *
 * Deliberately small per run: a handful of chats, each already bounded by [RetryBackoff], so a
 * systemic failure costs a bounded number of calls rather than one per broken chat per wake-up.
 */
@Singleton
class ChatRetryTask @Inject constructor(
    private val conversationDao: ConversationDao,
    private val generation: GenerationManager,
) : MaintenanceTask {

    override val id = "chat_retry"
    override val scope = TaskScope.ALL_PROFILES
    override val needsLlm = true
    override val minIntervalMs: Long = TimeUnit.HOURS.toMillis(1)

    override suspend fun run(ctx: TaskContext) {
        val candidates = conversationDao.awaitingReply(
            mode = Mode.CONVERSATION,
            lang = ctx.profileTag,
            userRole = Role.USER,
            maxAttempts = RetryBackoff.MAX_ATTEMPTS,
            limit = BATCH_LIMIT,
        )

        // Whatever the foreground is already generating is not ours to retry.
        val inFlight = generation.active.value.keys

        for (chat in candidates) {
            if (chat.id in inFlight) continue
            if (!RetryBackoff.ready(chat.autoRetryAttempts, chat.lastAutoRetryAt, ctx.now)) continue

            // Recorded before the call, so a crash mid-generation still costs an attempt and the
            // chat can't spin forever.
            conversationDao.recordAutoRetry(chat.id, ctx.now)
            // Via the manager rather than the repository, so the Library shows "Generating…" if the
            // user opens the app mid-retry. join() keeps the worker alive for the call.
            generation.generateReply(chat.id).join()
        }
    }

    private companion object {
        /** Chats attempted per run. At a 120s read timeout this stays well inside the worker window. */
        const val BATCH_LIMIT = 3
    }
}
