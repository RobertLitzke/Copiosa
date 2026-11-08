package com.copiosa.data.generation

import com.copiosa.data.repository.AudioRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.tts.RenderQueueStore
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.SupertonicVoices
import com.copiosa.data.tts.TtsEngine
import com.copiosa.data.tts.TtsModelState
import com.copiosa.data.tts.TtsRenderQueue
import com.copiosa.di.AppScope
import com.copiosa.domain.listening.RadioLine
import com.copiosa.domain.listening.RadioScript
import com.copiosa.domain.model.AudioStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders queued [com.copiosa.data.local.AudioItemEntity]s to AAC on an app-lifetime scope, one at
 * a time, so a synthesis survives the user leaving the Audio tab (mirrors [GenerationManager]).
 *
 * It never downloads the voice model — that's the user's explicit choice via [SupertonicModelStore].
 * Until the model is [TtsModelState.Ready], queued items simply stay PENDING; once it becomes ready
 * (or already is), the queue drains ([TtsRenderQueue]). Status lives on the row in Room, so the UI
 * reads it straight from the DB and a relaunch resumes whatever was left unrendered.
 */
@Singleton
class AudioGenerationManager @Inject constructor(
    private val repo: AudioRepository,
    private val tts: TtsEngine,
    modelStore: SupertonicModelStore,
    private val settingsRepo: SettingsRepository,
    @AppScope private val scope: CoroutineScope,
) {
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

    /** Queues [text] for synthesis and starts draining if the model is ready. */
    fun enqueue(
        text: String,
        title: String,
        langCode: String,
        sourceConversationId: Long? = null,
        sourceMessageId: Long? = null,
    ): Job = scope.launch {
        repo.enqueue(
            text = text,
            title = title,
            langCode = langCode,
            // Snapshot the user's chosen voice at enqueue time, so the item keeps it even if the
            // setting changes before it's rendered.
            sid = settingsRepo.settings.first().ttsVoiceSid,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
        )
        queue.kick()
    }

    /**
     * Queues a cloze radio track: [lines] become one playlist item in the [tag] folder, spoken in
     * [langCode] with each sentence's meaning between its two hearings. It goes on the same queue as
     * everything else, so a track never renders alongside another synthesis job.
     */
    fun enqueueRadio(lines: List<RadioLine>, title: String, tag: String, langCode: String): Job = scope.launch {
        repo.enqueue(
            text = RadioScript.encode(lines),
            title = title,
            langCode = langCode,
            sid = settingsRepo.settings.first().ttsVoiceSid,
            tag = tag,
            kind = RadioScript.KIND,
        )
        queue.kick()
    }

    /**
     * Starts the drain for rows inserted by another owner — [com.copiosa.data.generation.BookAudioManager]
     * enqueues book chapters into the same table, and one render queue for the whole playlist is the
     * point: two would put two synthesis jobs on the same native engine at once.
     */
    fun kick() = queue.kick()

    /** Re-queues a FAILED item. */
    fun retry(id: Long) {
        scope.launch {
            repo.setStatus(id, AudioStatus.PENDING)
            queue.kick()
        }
    }

    /** Owner-specific render (the queue wraps this in the GENERATING/PENDING/FAILED envelope). */
    private suspend fun render(id: Long) {
        val item = repo.getById(id) ?: return
        val out = File(repo.audioDir, "audio_$id.m4a")
        // Speed is read live (unlike the per-item voice) so a slider change applies to the queue.
        val speed = settingsRepo.settings.first().ttsSpeed
        val durationMs = if (item.kind == RadioScript.KIND) {
            tts.synthesizeRadio(
                lines = RadioScript.decode(item.text),
                targetLang = item.langCode,
                // The row carries one language; the other side of a radio track is whatever the
                // learner of that language reads translations in.
                nativeLang = settingsRepo.settingsFor(item.langCode).nativeLanguage,
                sid = item.sid,
                nativeSid = (item.sid + SupertonicVoices.NAMES.size / 2) % SupertonicVoices.NAMES.size,
                outFile = out,
                speed = speed,
            )
        } else {
            tts.synthesizeArticle(item.title, item.text, item.langCode, item.sid, out, speed)
        }
        repo.setReady(id, out, durationMs.takeIf { it > 0 })
    }
}
