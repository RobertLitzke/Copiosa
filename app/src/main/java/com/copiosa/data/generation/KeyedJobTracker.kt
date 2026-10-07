package com.copiosa.data.generation

import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Whether an item is waiting its turn in a batch, or actively calling the model. */
enum class GenPhase { QUEUED, GENERATING }

/** What [KeyedJobTracker.launch] does when its key already has a job running. */
enum class IfRunning {
    /** Cancel the running job and start this one in its place. */
    REPLACE,

    /** Leave the running job alone and return it; this request is dropped. */
    KEEP,
}

/**
 * The harness behind [GenerationManager] and [BookTranslationManager]: at most one job per id, run
 * on an app-lifetime [scope] so navigation never cancels it, with the UI-facing state those
 * managers share — [active], [errors] and [progress], all keyed by id.
 *
 * What it guarantees, and the hand-rolled versions did not:
 * - **Replace-or-reuse is atomic.** The check, the registration and the start happen under one lock,
 *   so two simultaneous requests for an id can never both launch.
 * - **A job only cleans up after itself while it is still the current one.** A replaced or cancelled
 *   job finishing late must not clear the [active]/[progress] state of the job that took its place,
 *   nor write an [errors] entry the new job has just cleared.
 * - **A failure is captured, not thrown.** Cancellation is rethrown; anything else becomes an
 *   [LlmError] in [errors] (an [LlmException] keeps its own).
 */
class KeyedJobTracker(private val scope: CoroutineScope) {

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val lock = Any()

    private val _active = MutableStateFlow<Map<Long, GenPhase>>(emptyMap())
    val active: StateFlow<Map<Long, GenPhase>> = _active.asStateFlow()

    private val _errors = MutableStateFlow<Map<Long, LlmError>>(emptyMap())
    val errors: StateFlow<Map<Long, LlmError>> = _errors.asStateFlow()

    /** Section/chunk progress for an in-flight job: id -> (done, total). */
    private val _progress = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())
    val progress: StateFlow<Map<Long, Pair<Int, Int>>> = _progress.asStateFlow()

    /**
     * Runs [body] as [id]'s job, clearing any stale error and progress first. [ifRunning] decides
     * what happens when [id] already has a live job; with [IfRunning.KEEP] that job is returned and
     * nothing else changes.
     *
     * [onError] runs inside the job after a failure has been recorded in [errors] — somewhere to
     * persist it. A failure of its own is swallowed so it cannot replace the error it was recording.
     * [onComplete] runs once the job ends, however it ends, and only if it is still the current job
     * for [id] (not one that was replaced or cancelled); [active] and [progress] are already clear.
     */
    fun launch(
        id: Long,
        ifRunning: IfRunning,
        onError: suspend (LlmError) -> Unit = {},
        onComplete: (cause: Throwable?) -> Unit = {},
        body: suspend () -> Unit,
    ): Job = synchronized(lock) {
        val running = jobs[id]?.takeIf { it.isActive }
        if (running != null && ifRunning == IfRunning.KEEP) return running
        running?.cancel()

        _errors.update { it - id }
        _progress.update { it - id }
        _active.update { it + (id to GenPhase.GENERATING) }

        // Lazy, so the job is in [jobs] before it can run — and so before it can finish.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (jobs[id] !== self) return@launch
                val error = (e as? LlmException)?.error ?: LlmError.Unknown(e.message ?: "error")
                _errors.update { it + (id to error) }
                try {
                    onError(error)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                }
            }
        }
        jobs[id] = job
        job.invokeOnCompletion { cause ->
            if (!jobs.remove(id, job)) return@invokeOnCompletion
            _active.update { it - id }
            _progress.update { it - id }
            onComplete(cause)
        }
        job.start()
        job
    }

    /** Marks [id] as waiting its turn, for work queued behind another job. */
    fun markQueued(id: Long) {
        _active.update { it + (id to GenPhase.QUEUED) }
    }

    /** Publishes [done] of [total] for [id]'s running job. */
    fun setProgress(id: Long, done: Int, total: Int) {
        _progress.update { it + (id to (done to total)) }
    }

    /** Aborts [id]'s job, if any; whatever it already saved stays saved. */
    fun cancel(id: Long) {
        synchronized(lock) {
            jobs.remove(id)?.cancel()
            _active.update { it - id }
            _progress.update { it - id }
        }
    }

    /** Clears a surfaced error once the screen showing it has seen it. */
    fun consumeError(id: Long) {
        _errors.update { it - id }
    }
}
