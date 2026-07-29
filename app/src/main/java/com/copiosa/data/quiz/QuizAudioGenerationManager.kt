package com.copiosa.data.quiz

import android.util.Log
import com.copiosa.data.tts.RenderQueueStore
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsEngine
import com.copiosa.data.tts.TtsModelState
import com.copiosa.data.tts.TtsRenderQueue
import com.copiosa.di.AppScope
import com.copiosa.domain.model.AudioStatus
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.quiz.QuizPoolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The seeding (text-generation) phase of a pool build, surfaced above the per-cue render progress. */
sealed interface PoolSeedState {
    data object Idle : PoolSeedState
    data object Seeding : PoolSeedState

    /** Seeding finished; [skippedCount]/[noKey] tell the UI how many LLM-only cues were dropped. */
    data class Done(val skippedCount: Int, val noKey: Boolean) : PoolSeedState
    data class Failed(val message: String) : PoolSeedState
}

/**
 * Builds and renders rapid-fire quiz pools on an app-lifetime scope (mirrors [
 * com.copiosa.data.generation.AudioGenerationManager]): [buildPool] seeds cue texts (LLM +
 * local), replaces the pool, then drains the PENDING rows through the TTS engine one at a time
 * ([TtsRenderQueue]). Status lives on the cue row in Room, so a relaunch resumes whatever was left
 * unrendered — and like the playlist, the voice model is never downloaded here; PENDING cues simply
 * wait for [TtsModelState.Ready].
 */
@Singleton
class QuizAudioGenerationManager @Inject constructor(
    private val repo: QuizPoolRepository,
    private val seeder: QuizPoolSeeder,
    private val tts: TtsEngine,
    modelStore: SupertonicModelStore,
    @AppScope private val scope: CoroutineScope,
) {
    private val _seedState = MutableStateFlow<PoolSeedState>(PoolSeedState.Idle)
    val seedState: StateFlow<PoolSeedState> = _seedState.asStateFlow()

    private val queue = TtsRenderQueue(
        scope = scope,
        modelStore = modelStore,
        store = object : RenderQueueStore {
            override suspend fun unrendered() = repo.unrendered().map { it.id to it.status }
            override suspend fun setStatus(id: Long, status: AudioStatus) = repo.setStatus(id, status)
            override suspend fun setFailed(id: Long, message: String) = repo.setFailed(id, message)
        },
        render = ::render,
    )

    init {
        queue.start()
    }

    /** Seeds and replaces [config]'s pool, then starts rendering. One build at a time per screen. */
    fun buildPool(config: QuizPoolConfig): Job = scope.launch {
        _seedState.value = PoolSeedState.Seeding
        try {
            val result = seeder.seed(config)
            repo.rebuildPool(config, result.cues)
            _seedState.value = PoolSeedState.Done(result.skippedCount, result.noKey)
            queue.kick()
        } catch (e: CancellationException) {
            _seedState.value = PoolSeedState.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pool build failed", e)
            _seedState.value = PoolSeedState.Failed(e.message ?: "Pool build failed")
        }
    }

    fun deletePool(lang: String, type: CueType): Job = scope.launch {
        repo.deletePool(lang, type)
        _seedState.value = PoolSeedState.Idle
    }

    /** Re-queues a pool's FAILED cues. */
    fun retryFailed(lang: String, type: CueType) {
        scope.launch {
            repo.retryFailed(lang, type)
            queue.kick()
        }
    }

    /** Owner-specific render (the queue wraps this in the GENERATING/PENDING/FAILED envelope). */
    private suspend fun render(id: Long) {
        val cue = repo.getCue(id) ?: return
        val out = repo.cacheFile(cue.spokenText, cue.langCode, cue.sid)
        // Cue audio is content-hash-named, so an identical cue rendered earlier is reused as-is.
        if (out.exists() && out.length() > 0) {
            repo.setReady(id, out, null)
            return
        }
        val durationMs = tts.synthesizeToFile(
            cue.spokenText, cue.langCode, cue.sid, out, QuizPoolRepository.RENDER_SPEED,
        )
        repo.setReady(id, out, durationMs.takeIf { it > 0 })
    }

    private companion object {
        const val TAG = "QuizAudioGen"
    }
}
