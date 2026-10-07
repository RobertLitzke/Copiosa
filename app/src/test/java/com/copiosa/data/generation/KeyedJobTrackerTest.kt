package com.copiosa.data.generation

import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyedJobTrackerTest {

    @Test
    fun `keep returns the running job and does not run the second body`() = runTest {
        val tracker = KeyedJobTracker(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var runs = 0

        val first = tracker.launch(1, IfRunning.KEEP) { runs++; gate.await() }
        runCurrent()
        val second = tracker.launch(1, IfRunning.KEEP) { runs++ }
        runCurrent()

        assertSame(first, second)
        assertEquals(1, runs)
        gate.complete(Unit)
        runCurrent()
        assertTrue(tracker.active.value.isEmpty())
    }

    @Test
    fun `replace cancels the running job and the new one keeps its active flag`() = runTest {
        val tracker = KeyedJobTracker(backgroundScope)
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()

        val old = tracker.launch(1, IfRunning.REPLACE) { oldGate.await() }
        runCurrent()
        tracker.setProgress(1, 1, 5)
        val new = tracker.launch(1, IfRunning.REPLACE) { newGate.await() }
        runCurrent()

        assertTrue(old.isCancelled)
        // The cancelled job finishing must not wipe the state of the job that replaced it.
        assertEquals(GenPhase.GENERATING, tracker.active.value[1])
        assertTrue(new.isActive)

        newGate.complete(Unit)
        runCurrent()
        assertTrue(tracker.active.value.isEmpty())
    }

    @Test
    fun `a failure lands in errors and in onError, and a stale error is cleared on relaunch`() = runTest {
        val tracker = KeyedJobTracker(backgroundScope)
        var persisted: LlmError? = null

        tracker.launch(1, IfRunning.REPLACE, onError = { persisted = it }) {
            throw LlmException(LlmError.RateLimit)
        }
        runCurrent()
        assertEquals(LlmError.RateLimit, tracker.errors.value[1])
        assertEquals(LlmError.RateLimit, persisted)
        assertTrue(tracker.active.value.isEmpty())

        val gate = CompletableDeferred<Unit>()
        tracker.launch(1, IfRunning.REPLACE) { gate.await() }
        runCurrent()
        assertNull(tracker.errors.value[1])
        gate.complete(Unit)
    }

    @Test
    fun `a non-llm exception becomes Unknown`() = runTest {
        val tracker = KeyedJobTracker(backgroundScope)
        tracker.launch(1, IfRunning.REPLACE) { error("boom") }
        runCurrent()
        assertEquals(LlmError.Unknown("boom"), tracker.errors.value[1])
    }

    @Test
    fun `an onError that itself fails does not replace the recorded error`() = runTest {
        val tracker = KeyedJobTracker(backgroundScope)
        tracker.launch(1, IfRunning.REPLACE, onError = { error("db down") }) {
            throw LlmException(LlmError.Network)
        }
        runCurrent()
        assertEquals(LlmError.Network, tracker.errors.value[1])
    }

    @Test
    fun `cancel records no error and skips onComplete`() = runTest {
        val tracker = KeyedJobTracker(backgroundScope)
        var completed = false
        tracker.launch(1, IfRunning.REPLACE, onComplete = { completed = true }) {
            CompletableDeferred<Unit>().await()
        }
        runCurrent()
        tracker.setProgress(1, 2, 9)

        tracker.cancel(1)
        runCurrent()

        assertTrue(tracker.errors.value.isEmpty())
        assertTrue(tracker.active.value.isEmpty())
        assertTrue(tracker.progress.value.isEmpty())
        assertFalse(completed)
    }

    @Test
    fun `onComplete sees a null cause after a clean finish`() = runTest {
        val tracker = KeyedJobTracker(backgroundScope)
        var cause: Throwable? = IllegalStateException("unset")
        tracker.launch(1, IfRunning.KEEP, onComplete = { cause = it }) { }
        runCurrent()
        assertNull(cause)
    }

    @Test
    fun `markQueued shows a queued phase until the job starts`() = runTest {
        val tracker = KeyedJobTracker(backgroundScope)
        tracker.markQueued(7)
        assertEquals(GenPhase.QUEUED, tracker.active.value[7])

        val gate = CompletableDeferred<Unit>()
        tracker.launch(7, IfRunning.REPLACE) { gate.await() }
        runCurrent()
        assertEquals(GenPhase.GENERATING, tracker.active.value[7])
        gate.complete(Unit)
    }
}
