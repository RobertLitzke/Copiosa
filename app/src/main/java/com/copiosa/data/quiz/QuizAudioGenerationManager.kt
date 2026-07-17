package com.copiosa.data.quiz

import android.util.Log
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsEngine
import com.copiosa.data.tts.TtsModelState
import com.copiosa.data.tts.TtsRenderQueue
import com.copiosa.domain.model.AudioStatus
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.quiz.QuizPoolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _seedState = MutableStateFlow<PoolSeedState>(PoolSeedState.Idle)
    val seedState: StateFlow<PoolSeedState> = _seedState.asStateFlow()

    private val queue = TtsRenderQueue(
        scope = scope,
        modelStore = modelStore,
        nextPendingId = { repo.unrendered().firstOrNull()?.id },
        resetOrphans = {
            // A process death mid-synthesis leaves a GENERATING row with no job behind it; reset it.
            repo.unrendered()
                .filter { it.status == AudioStatus.GENERATING }
                .forEach { repo.setStatus(it.id, AudioStatus.PENDING) }
        },
        processOne = ::processOne,
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

    private suspend fun processOne(id: Long) {
        val cue = repo.getCue(id) ?: return
        repo.setStatus(id, AudioStatus.GENERATING)
        try {
            val out = repo.cacheFile(cue.spokenText, cue.langCode, cue.sid)
            if (out.exists() && out.length() > 0) {
                repo.setReady(id, out, null)
                return
            }
            val durationMs = tts.synthesizeToFile(
                cue.spokenText, cue.langCode, cue.sid, out, QuizPoolRepository.RENDER_SPEED,
            )
            repo.setReady(id, out, durationMs.takeIf { it > 0 })
        } catch (e: CancellationException) {
            repo.setStatus(id, AudioStatus.PENDING)
            throw e
        } catch (e: Exception) {
            repo.setFailed(id, e.message ?: "Synthesis failed")
        }
    }

    private companion object {
        const val TAG = "QuizAudioGen"
    }
}
