package com.copiosa.data.maintenance.tasks

import com.copiosa.data.generation.BookTranslationManager
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import com.copiosa.data.repository.BookRepository
import com.copiosa.domain.maintenance.RetryBackoff
import com.copiosa.domain.model.BookDownloadMode
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Slow translate": advances books set to [BookDownloadMode.BACKGROUND] so a whole novel gets
 * translated without requiring an open reading session.
 *
 * Deliberately a trickle, not a batch: one section per book per run, and [minIntervalMs] keeps runs
 * ~90 minutes apart, so a book fills in over days at a negligible cost per day rather than spending a
 * novel's worth of calls in one sitting. (Reading is what should feel fast — the reader's read-ahead
 * covers that; a learner who wants the whole book *now* picks [BookDownloadMode.RAPID], which is a
 * foreground worker rather than this.) Paced per book by [RetryBackoff.readyCapped], so a
 * permanently-failing book backs off to a call a day rather than being given up on: a background
 * download nobody is watching has to be able to heal itself once whatever broke it clears. Attempts
 * are recorded *before* the call (crash still costs an attempt) and reset by every successfully
 * stored section, the ChatRetryTask idiom throughout.
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
        val candidates = bookRepo.pendingSlowTranslate(lang = ctx.profileTag, limit = BOOKS_PER_RUN)

        for (book in candidates) {
            // Whatever the foreground is already translating is not ours to touch.
            if (book.id in books.active.value) continue
            // Capped, not bounded: see [RetryBackoff.readyCapped]. A book that keeps failing slows
            // to a call a day instead of being abandoned, which is what it means for a download to
            // run "in the background" — nothing else is watching it.
            if (!RetryBackoff.readyCapped(book.autoRetryAttempts, book.lastAutoRetryAt, ctx.now)) continue

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
