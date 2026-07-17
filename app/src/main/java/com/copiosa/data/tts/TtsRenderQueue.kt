package com.copiosa.data.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The drain machinery shared by the TTS render queues (the playlist's AudioGenerationManager and the
 * quiz pool's QuizAudioGenerationManager): single-consumer, gated on the voice model being ready,
 * with row status persisted by the owner so a relaunch resumes where it left off.
 *
 * The owner provides the queue's storage view: [nextPendingId] returns the next row to render (null
 * when drained), [resetOrphans] repairs rows a process death left in GENERATING, and [processOne]
 * renders a single row, persisting READY/FAILED itself.
 */
class TtsRenderQueue(
    private val scope: CoroutineScope,
    private val modelStore: SupertonicModelStore,
    private val nextPendingId: suspend () -> Long?,
    private val resetOrphans: suspend () -> Unit,
    private val processOne: suspend (Long) -> Unit,
) {
    private val lock = Mutex()
    private var processing = false

    /** Repairs orphaned rows, then drains whenever the model is (or becomes) ready. */
    fun start() {
        scope.launch {
            resetOrphans()
            modelStore.state.collect { state ->
                if (state is TtsModelState.Ready) kick()
            }
        }
    }

    /** Starts draining unless a drain is already running; safe to call on every enqueue. */
    fun kick() {
        scope.launch {
            if (!claim()) return@launch
            try {
                while (modelStore.isReady()) {
                    val id = nextPendingId() ?: break
                    processOne(id)
                }
            } finally {
                // Release and, atomically, re-check for work queued during the gap so nothing is
                // stranded after the last item (the re-check runs under the same lock that gates
                // claim(), so a concurrent enqueue either is seen here or claims afresh).
                val more = lock.withLock {
                    processing = false
                    modelStore.isReady() && nextPendingId() != null
                }
                if (more) kick()
            }
        }
    }

    private suspend fun claim(): Boolean = lock.withLock {
        if (processing) false else { processing = true; true }
    }
}
