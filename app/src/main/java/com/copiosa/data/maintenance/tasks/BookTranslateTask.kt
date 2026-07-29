package com.copiosa.data.maintenance.tasks

import com.copiosa.data.generation.BookTranslationManager
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import com.copiosa.data.repository.BookRepository
import com.copiosa.domain.maintenance.RetryBackoff
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Slow translate": advances books the user flagged for background translation
 * ([BookEntity.autoTranslate]) so a whole novel gets translated without requiring an open reading
 * session.
 *
 * Deliberately a trickle, not a batch: one section per book per run, and [minIntervalMs] keeps runs
 * ~90 minutes apart, so a book fills in over days at a negligible cost per day rather than spending a
 * novel's worth of calls in one sitting. (Reading is what should feel fast — the reader's read-ahead
 * covers that.) Bounded per book by [RetryBackoff] too, so a permanently-failing book stops costing
 * calls. Attempts are recorded *before* the call (crash still costs an attempt) and reset by every
 * successfully stored section, the ChatRetryTask idiom throughout.
 */
@Singleton
class BookTranslateTask @Inject constructor(
    private val bookRepo: BookRepository,
    private val books: BookTranslationManager,
) : MaintenanceTask {

    override val id = "book_translate"
    override val scope = TaskScope.ALL_PROFILES
    override val needsLlm = true
    override val minIntervalMs: Long = TimeUnit.MINUTES.toMillis(90)

    override suspend fun run(ctx: TaskContext) {
        val candidates = bookRepo.pendingSlowTranslate(
            lang = ctx.profileTag,
            maxAttempts = RetryBackoff.MAX_ATTEMPTS,
            limit = BOOKS_PER_RUN,
        )

        for (book in candidates) {
            // Whatever the foreground is already translating is not ours to touch.
            if (book.id in books.active.value) continue
            if (!RetryBackoff.ready(book.autoRetryAttempts, book.lastAutoRetryAt, ctx.now)) continue

            bookRepo.recordAutoRetry(book.id, ctx.now)
            // Via the manager (its single per-book loop), so the reader/library show live progress
            // if the user opens the app mid-run. join() keeps the worker alive for the calls.
            books.translateToEnd(book.id, maxSections = SECTIONS_PER_RUN).join()
        }
    }

    private companion object {
        /** Books per run, and the trickle: one section each, ~90 minutes apart (see [minIntervalMs]). */
        const val BOOKS_PER_RUN = 2
        const val SECTIONS_PER_RUN = 1
    }
}
