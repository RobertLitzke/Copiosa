package com.copiosa.data.tts

import com.copiosa.domain.model.AudioStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The queue's view of its owner's storage: which rows still need rendering (PENDING or GENERATING,
 * in render order) and the status transitions the queue drives. READY is persisted by the owner's
 * [TtsRenderQueue] render callback itself, since what "ready" means (file path, duration) is
 * owner-specific.
 */
interface RenderQueueStore {
    /** (id, status) of rows not yet rendered, in render order. Must exclude FAILED rows. */
    suspend fun unrendered(): List<Pair<Long, AudioStatus>>
    suspend fun setStatus(id: Long, status: AudioStatus)
    suspend fun setFailed(id: Long, message: String)
}

/**
 * The drain machinery shared by the TTS render queues (the playlist's AudioGenerationManager and the
 * quiz pool's QuizAudioGenerationManager): single-consumer, gated on the voice model being ready,
 * with row status persisted through [store] so a relaunch resumes where it left off.
 *
 * The queue owns the status envelope — GENERATING while [render] runs, back to PENDING on
 * cancellation, FAILED on error, and orphan repair for rows a process death left in GENERATING.
 * [render] does just the owner-specific part: load the row, synthesize, persist READY.
 */
class TtsRenderQueue(
    private val scope: CoroutineScope,
    private val modelStore: SupertonicModelStore,
    private val store: RenderQueueStore,
    private val render: suspend (Long) -> Unit,
) {
    private val lock = Mutex()
    private var processing = false

    /** Repairs orphaned rows, then drains whenever the model is (or becomes) ready. */
    fun start() {
        scope.launch {
            // A process death mid-synthesis leaves a GENERATING row with no job behind it; reset it.
            store.unrendered()
                .filter { (_, status) -> status == AudioStatus.GENERATING }
                .forEach { (id, _) -> store.setStatus(id, AudioStatus.PENDING) }
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

    private suspend fun nextPendingId(): Long? = store.unrendered().firstOrNull()?.first

    private suspend fun processOne(id: Long) {
        store.setStatus(id, AudioStatus.GENERATING)
        try {
            render(id)
        } catch (e: CancellationException) {
            store.setStatus(id, AudioStatus.PENDING)
            throw e
        } catch (e: Exception) {
            store.setFailed(id, e.message ?: "Synthesis failed")
        }
    }

    private suspend fun claim(): Boolean = lock.withLock {
        if (processing) false else { processing = true; true }
    }
}
