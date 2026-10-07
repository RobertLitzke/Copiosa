package com.copiosa.data.maintenance.tasks

import com.copiosa.data.generation.BookAudioManager
import com.copiosa.data.generation.BookAudioScheduler
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a book's audio catching up with its translation.
 *
 * A forward book can only be spoken as far as its translation frontier, so "generate audio" on a
 * half-translated novel queues the chapters that are ready and stops. This is what closes the gap:
 * once the frontier passes another chapter, the next run queues it, and the folder fills in without
 * the learner having to come back and re-tap.
 *
 * Only books that already have audio are touched — asking for one book's audio is not consent to
 * spend hours of CPU on every book on the shelf. Costs no LLM budget ([needsLlm] is false), since
 * synthesis is entirely on-device.
 */
@Singleton
class BookAudioTask @Inject constructor(
    private val bookAudio: BookAudioManager,
    private val scheduler: BookAudioScheduler,
) : MaintenanceTask {

    override val id = "book_audio"

    /** The synthesis queue and the CPU it costs belong to whoever is studying right now. */
    override val scope = TaskScope.ACTIVE_PROFILE
    override val needsLlm = false
    override val minIntervalMs: Long = TimeUnit.MINUTES.toMillis(60)

    override suspend fun run(ctx: TaskContext) {
        for (book in bookAudio.booksNeedingMoreAudio(ctx.profileTag)) {
            if (bookAudio.enqueueReady(book.id) > 0) {
                // The rows are queued either way; the worker is what keeps the process alive long
                // enough to render them.
                scheduler.enqueue(book.id, book.title)
            }
        }
    }
}
