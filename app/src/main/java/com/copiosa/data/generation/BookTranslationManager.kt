package com.copiosa.data.generation

import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.code
import com.copiosa.data.repository.BookRepository
import com.copiosa.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single writer for book translation. Every entry point — the reader's auto read-ahead,
 * "translate whole book", a retry tap, the background maintenance task — only ever *raises a
 * per-book target section*; one loop per book consumes the target by translating the frontier
 * section ([BookEntity.translatedSections]) again and again. A section is therefore never
 * translated twice by accident, and concurrent requests coalesce for free. [retranslate] is the one
 * deliberate exception — the same single-job harness, walking a fixed range instead of the frontier.
 *
 * GenerationManager's idiom otherwise: a [KeyedJobTracker] on the app-lifetime [scope] so navigation
 * never cancels work, id-keyed [active]/[errors]/[progress] flows for the UI, failure state
 * derivable from Room (frontier < total and not active = stalled).
 */
@Singleton
class BookTranslationManager @Inject constructor(
    private val bookRepo: BookRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val jobs = KeyedJobTracker(scope)

    /** Highest section (inclusive) each book's loop should reach; raised, never lowered. */
    private val targets = ConcurrentHashMap<Long, AtomicInteger>()

    val active: StateFlow<Map<Long, GenPhase>> = jobs.active
    val errors: StateFlow<Map<Long, LlmError>> = jobs.errors

    /** Section progress for an in-flight book: id -> (sections done, total sections). */
    val progress: StateFlow<Map<Long, Pair<Int, Int>>> = jobs.progress

    /**
     * Raise [bookId]'s translation target to at least [targetSection] (inclusive) and make sure its
     * loop is running. Idempotent and coalescing — read-ahead calls this on every section crossing.
     */
    fun ensureTranslatedThrough(bookId: Long, targetSection: Int): Job {
        raiseTarget(bookId, targetSection)
        return startLoop(bookId, maxSections = null)
    }

    /**
     * Push [bookId] towards the end of the book. [maxSections] bounds how many sections this call may
     * translate (the slow-translate per-run budget); null is unbounded.
     *
     * A bounded call deliberately does *not* raise the shared target — its budget is its bound. Raising
     * the target to the end of the book would outlive the call and quietly turn the reader's next
     * read-ahead (which reuses the same target) into a full-speed run to the last page.
     */
    fun translateToEnd(bookId: Long, maxSections: Int? = null): Job {
        if (maxSections == null) raiseTarget(bookId, Int.MAX_VALUE)
        return startLoop(bookId, maxSections)
    }

    /**
     * Re-translates sections [from]..[to] (inclusive) with the book's *current* model — what the
     * download-mode dialog runs after the model is changed on a partly-translated book.
     *
     * Safe against the frontier because BookDao.setTranslatedSections only ever raises it: the book
     * stays as readable as it was, each section's text is simply replaced in turn. Like every other
     * entry point this is one job per book, so it no-ops (returning the running job) if something is
     * already translating — callers that mean to interrupt must [cancel] first.
     */
    fun retranslate(bookId: Long, from: Int, to: Int): Job =
        startJob(bookId, restartable = false) {
            val total = bookRepo.getById(bookId)?.totalSections ?: return@startJob
            var done = 0
            for (section in from..minOf(to, total - 1)) {
                jobs.setProgress(bookId, section, total)
                if (done > 0) delay(SECTION_PACING_MS)
                bookRepo.translateSection(bookId, section)
                done += 1
            }
        }

    /** Aborts the in-flight loop for [bookId]; completed sections stay saved (that's the frontier). */
    fun cancel(bookId: Long) {
        targets.remove(bookId)
        jobs.cancel(bookId)
    }

    /** Clears a surfaced error after the reader has shown it. */
    fun consumeError(bookId: Long) = jobs.consumeError(bookId)

    private fun raiseTarget(bookId: Long, targetSection: Int) {
        val target = targets.getOrPut(bookId) { AtomicInteger(-1) }
        target.updateAndGet { maxOf(it, targetSection) }
    }

    private fun startLoop(bookId: Long, maxSections: Int?): Job =
        // Only an unbounded run chases a target that can move under it, so only it is restartable.
        startJob(bookId, restartable = maxSections == null) {
            var doneThisRun = 0
            while (true) {
                val book = bookRepo.getById(bookId) ?: break
                jobs.setProgress(bookId, book.translatedSections, book.totalSections)
                // A budgeted run is bounded by its budget, not by the read-ahead target.
                val target = if (maxSections != null) {
                    book.totalSections - 1
                } else {
                    minOf(targets[bookId]?.get() ?: -1, book.totalSections - 1)
                }
                if (book.translatedSections > target) break
                if (maxSections != null && doneThisRun >= maxSections) break
                // A few seconds between calls so a book doesn't burst past a provider's
                // per-minute request cap (the chunked-article pacing).
                if (doneThisRun > 0) delay(SECTION_PACING_MS)
                bookRepo.translateSection(bookId, book.translatedSections)
                doneThisRun += 1
            }
        }

    /**
     * The one-job-per-book entry every path runs through: at most one [body] per book — a request
     * while one is running gets that job back — with its failure recorded rather than thrown.
     * [restartable] re-checks the target once on completion — a target raised in the instant between
     * the loop's last check and this completion would otherwise be dropped, stalling read-ahead
     * until the reader crossed another section. Never after a cancellation or a failure, so neither
     * a broken book nor a bounded run can spin.
     */
    private fun startJob(bookId: Long, restartable: Boolean, body: suspend () -> Unit): Job =
        jobs.launch(
            bookId,
            IfRunning.KEEP,
            // Also to Room, because [errors] is in-memory and a background failure is read long
            // after the process that saw it: without this a book that stalled overnight can only
            // report a section count that stopped moving. Cleared by the next stored section.
            onError = { error -> bookRepo.setLastError(bookId, error.code) },
            onComplete = { cause ->
                if (restartable && cause == null && errors.value[bookId] == null) {
                    scope.launch {
                        val book = bookRepo.getById(bookId) ?: return@launch
                        val target = minOf(targets[bookId]?.get() ?: -1, book.totalSections - 1)
                        if (book.translatedSections <= target) startLoop(bookId, maxSections = null)
                    }
                }
            },
            body = body,
        )

    private companion object {
        const val SECTION_PACING_MS = 4_000L
    }
}
