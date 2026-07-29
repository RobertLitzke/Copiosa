package com.copiosa.domain.vocab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnrichmentQueueTest {

    private val debounce = 3_000L

    @Test
    fun `nothing is processed until the queue goes quiet`() = runTest {
        val batches = mutableListOf<List<Long>>()
        val queue = queue(batches)

        queue.enqueue(1)
        advanceTimeBy(debounce - 1)
        assertTrue("processed before the debounce elapsed", batches.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf(listOf(1L)), batches)
    }

    @Test
    fun `a burst of saves collapses into one batch`() = runTest {
        val batches = mutableListOf<List<Long>>()
        val queue = queue(batches)

        // Each save lands inside the previous one's debounce window, restarting it.
        for (id in 1L..5L) {
            queue.enqueue(id)
            advanceTimeBy(debounce / 2)
        }
        advanceUntilIdle()

        assertEquals(listOf(listOf(1L, 2L, 3L, 4L, 5L)), batches)
    }

    @Test
    fun `a save is only enriched once`() = runTest {
        val batches = mutableListOf<List<Long>>()
        val queue = queue(batches)

        queue.enqueue(7)
        advanceUntilIdle()
        queue.enqueue(8)
        advanceUntilIdle()

        assertEquals(listOf(listOf(7L), listOf(8L)), batches)
    }

    @Test
    fun `more than a batch is split, oldest first`() = runTest {
        val batches = mutableListOf<List<Long>>()
        val queue = queue(batches, batchSize = 2)

        (1L..5L).forEach(queue::enqueue)
        advanceUntilIdle()

        assertEquals(listOf(listOf(1L, 2L), listOf(3L, 4L), listOf(5L)), batches)
    }

    @Test
    fun `a re-enqueued id is not duplicated`() = runTest {
        val batches = mutableListOf<List<Long>>()
        val queue = queue(batches)

        queue.enqueue(3)
        queue.enqueue(3)
        advanceUntilIdle()

        assertEquals(listOf(listOf(3L)), batches)
    }

    @Test
    fun `a refused batch abandons the rest of the queue`() = runTest {
        val batches = mutableListOf<List<Long>>()
        val queue = EnrichmentQueue(this, debounce, batchSize = 2) { ids ->
            batches += ids
            false // e.g. the network is down, or background LLM tasks are switched off
        }

        (1L..6L).forEach(queue::enqueue)
        advanceUntilIdle()

        assertEquals("kept spending calls after a refusal", listOf(listOf(1L, 2L)), batches)
    }

    @Test
    fun `the queue keeps working after a refusal`() = runTest {
        val batches = mutableListOf<List<Long>>()
        var allow = false
        val queue = EnrichmentQueue(this, debounce, batchSize = 10) { ids ->
            batches += ids
            allow
        }

        queue.enqueue(1)
        advanceUntilIdle()
        allow = true
        queue.enqueue(2)
        advanceUntilIdle()

        assertEquals(listOf(listOf(1L), listOf(2L)), batches)
    }

    private fun CoroutineScope.queue(
        sink: MutableList<List<Long>>,
        batchSize: Int = 10,
    ) = EnrichmentQueue(this, debounce, batchSize) { ids ->
        sink += ids
        true
    }
}
