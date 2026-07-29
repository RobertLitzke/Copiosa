package com.copiosa.data.generation

import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import com.copiosa.data.repository.BookRepository
import com.copiosa.di.AppScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single writer for book translation. Every entry point — the reader's auto read-ahead,
 * "translate whole book", a retry tap, the background maintenance task — only ever *raises a
 * per-book target section*; one loop per book consumes the target by translating the frontier
 * section ([BookEntity.translatedSections]) again and again. A section can therefore never be
 * translated twice, and concurrent requests coalesce for free.
 *
 * GenerationManager's idiom otherwise: app-lifetime [scope] so navigation never cancels work,
 * id-keyed [active]/[errors]/[progress] flows for the UI, failure state derivable from Room
 * (frontier < total and not active = stalled).
 */
@Singleton
class BookTranslationManager @Inject constructor(
    private val bookRepo: BookRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val jobs = ConcurrentHashMap<Long, Job>()

    /** Highest section (inclusive) each book's loop should reach; raised, never lowered. */
    private val targets = ConcurrentHashMap<Long, AtomicInteger>()

    private val _active = MutableStateFlow<Map<Long, GenPhase>>(emptyMap())
    val active: StateFlow<Map<Long, GenPhase>> = _active.asStateFlow()

    private val _errors = MutableStateFlow<Map<Long, LlmError>>(emptyMap())
    val errors: StateFlow<Map<Long, LlmError>> = _errors.asStateFlow()

    /** Section progress for an in-flight book: id -> (sections done, total sections). */
    private val _progress = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())
    val progress: StateFlow<Map<Long, Pair<Int, Int>>> = _progress.asStateFlow()

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

    /** Aborts the in-flight loop for [bookId]; completed sections stay saved (that's the frontier). */
    fun cancel(bookId: Long) {
        targets.remove(bookId)
        jobs.remove(bookId)?.cancel()
        _active.update { it - bookId }
    }

    /** Clears a surfaced error after the reader has shown it. */
    fun consumeError(bookId: Long) { _errors.update { it - bookId } }

    private fun raiseTarget(bookId: Long, targetSection: Int) {
        val target = targets.getOrPut(bookId) { AtomicInteger(-1) }
        target.updateAndGet { maxOf(it, targetSection) }
    }

    @Synchronized
    private fun startLoop(bookId: Long, maxSections: Int?): Job {
        jobs[bookId]?.takeIf { it.isActive }?.let { return it }
        _errors.update { it - bookId }
        _active.update { it + (bookId to GenPhase.GENERATING) }
        val job = scope.launch {
            var doneThisRun = 0
            try {
                while (true) {
                    val book = bookRepo.getById(bookId) ?: break
                    _progress.update { it + (bookId to (book.translatedSections to book.totalSections)) }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = (e as? LlmException)?.error ?: LlmError.Unknown(e.message ?: "error")
                _errors.update { it + (bookId to error) }
            }
        }
        jobs[bookId] = job
        job.invokeOnCompletion { cause ->
            jobs.remove(bookId, job)
            _active.update { it - bookId }
            _progress.update { it - bookId }
            // A target raised in the instant between the loop's last check and this completion
            // would otherwise be dropped, stalling read-ahead until the reader crossed another
            // section. Re-check once — never after a cancellation or a failure, and never for a
            // budgeted (maintenance) run, so neither a broken book nor a bounded run can spin.
            if (cause == null && maxSections == null && _errors.value[bookId] == null) {
                scope.launch {
                    val book = bookRepo.getById(bookId) ?: return@launch
                    val target = minOf(targets[bookId]?.get() ?: -1, book.totalSections - 1)
                    if (book.translatedSections <= target) startLoop(bookId, maxSections = null)
                }
            }
        }
        return job
    }

    private companion object {
        const val SECTION_PACING_MS = 4_000L
    }
}
