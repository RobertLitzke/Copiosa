package com.copiosa.domain.vocab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A debounced, batching work queue over card ids — the mechanism behind auto-enriching cards as
 * they're saved, kept free of Android and repository dependencies so it stays JVM-testable.
 *
 * Each [enqueue] restarts the debounce, so a burst of saves (tapping five words in a row) collapses
 * into one [process] call instead of five. Once the queue goes quiet for [debounceMs] the pending
 * ids are handed over [batchSize] at a time, in insertion order.
 *
 * [process] returns false to abandon whatever is still queued: a failure is nearly always the
 * network or a missing key, so the following batches would only repeat it. It is expected to handle
 * its own errors — anything it throws propagates into [scope], which should be a supervisor scope.
 */
class EnrichmentQueue(
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    private val batchSize: Int,
    private val process: suspend (List<Long>) -> Boolean,
) {
    private val mutex = Mutex()

    /** Insertion-ordered so the oldest save is enriched first; a re-enqueued id keeps its place. */
    private val pending = LinkedHashSet<Long>()
    private var drainJob: Job? = null

    /** Queues [id], deferring the drain by another [debounceMs]. Returns immediately. */
    fun enqueue(id: Long) {
        scope.launch {
            mutex.withLock {
                pending += id
                drainJob?.cancel()
                drainJob = scope.launch {
                    delay(debounceMs)
                    drain()
                }
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val batch = mutex.withLock {
                pending.take(batchSize).also { pending -= it.toSet() }
            }
            if (batch.isEmpty()) return
            if (!process(batch)) {
                mutex.withLock { pending.clear() }
                return
            }
        }
    }
}
