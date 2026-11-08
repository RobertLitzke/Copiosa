package com.copiosa.playback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.tts.SpeechChunk
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsEngine
import com.copiosa.di.AppScope
import com.copiosa.domain.text.SpeechText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * What the [Pronouncer] is saying: [text] is what was asked for, as the button that asked holds it,
 * and [audible] turns true once sound is actually coming out — before that the voice is still
 * working on the first sentence.
 */
data class Speaking(val text: String, val audible: Boolean)

/**
 * Says a piece of target-language text aloud, now — the play button on the lookup card, a review
 * card, a chat reply. The Audio tab is for listening later and keeps what it renders; this keeps
 * nothing and exists to answer "how is that said?" while the word is still on screen.
 *
 * Speech is streamed rather than rendered to a file first: [TtsEngine.speech] hands over one
 * sentence at a time and each is written to an [AudioTrack] as it arrives, so a reply of several
 * paragraphs starts within a sentence's synthesis instead of after all of it.
 *
 * One utterance at a time; asking for another replaces it. It never downloads the voice — callers
 * check [SupertonicModelStore] and offer that themselves — and it stops when the app leaves the
 * foreground: nothing here holds a foreground service, and a process frozen mid-sentence would
 * keep whatever it interrupted paused.
 *
 * [speak], [toggle] and [stop] are called from the main thread, which is also where [speaking]
 * changes.
 */
@Singleton
class Pronouncer @Inject constructor(
    @ApplicationContext context: Context,
    private val tts: TtsEngine,
    private val modelStore: SupertonicModelStore,
    private val settingsRepo: SettingsRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _speaking = MutableStateFlow<Speaking?>(null)
    val speaking: StateFlow<Speaking?> = _speaking.asStateFlow()

    private var job: Job? = null

    init {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                // A rotation stops and restarts the activity without the learner having gone anywhere.
                override fun onActivityStopped(activity: Activity) {
                    if (!activity.isChangingConfigurations) stop()
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** Stops [text] if it is what is being said, otherwise says it — one button, play and stop. */
    fun toggle(text: String, langCode: String) {
        if (_speaking.value?.text == text) stop() else speak(text, langCode)
    }

    /**
     * Says [text] (Markdown is fine; it is reduced to prose first) in [langCode], replacing whatever
     * was being said. Does nothing when the voice is not installed or nothing speakable is left.
     */
    fun speak(text: String, langCode: String) {
        val spoken = SpeechText.fromMarkdown(text)
        if (spoken.isBlank() || !modelStore.isReady()) return
        stop()
        _speaking.value = Speaking(text, audible = false)
        // Dispatchers.Main, not immediate: the body must not run before `job` holds this launch.
        job = scope.launch(Dispatchers.Main) {
            val mine = coroutineContext[Job]
            try {
                val settings = settingsRepo.settings.first()
                play(tts.speech(spoken, langCode, settings.ttsVoiceSid, settings.ttsSpeed)) {
                    _speaking.value = Speaking(text, audible = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not speak", e)
            } finally {
                // Only when still the current utterance: a replacement has already set its own state.
                if (job === mine) {
                    job = null
                    _speaking.value = null
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _speaking.value = null
    }

    /** Plays [chunks] to the end, calling [onAudible] (on the main thread) as the first one starts. */
    private suspend fun play(chunks: Flow<SpeechChunk>, onAudible: () -> Unit) {
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ATTRIBUTES)
            // A call, or another app taking over: give way rather than talk across it.
            .setOnAudioFocusChangeListener({ change -> if (change < 0) stop() }, Handler(Looper.getMainLooper()))
            .build()
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        var track: AudioTrack? = null
        try {
            withContext(Dispatchers.IO) {
                chunks.buffer(SENTENCES_AHEAD).collect { chunk ->
                    val out = track ?: newTrack(chunk.sampleRate).also {
                        track = it
                        it.play()
                        withContext(Dispatchers.Main) { onAudible() }
                    }
                    out.writeAll(chunk.samples)
                }
                track?.let { out ->
                    // In stream mode stop() plays out what is still buffered — including a clip too
                    // short to have reached the start threshold — and a blocking write leaves at
                    // most one buffer unplayed, so that bounds the wait.
                    out.stop()
                    delay(out.bufferSizeInFrames * 1000L / out.sampleRate + DRAIN_MARGIN_MS)
                }
            }
        } finally {
            track?.let { out ->
                // Cut off mid-word: silence what is queued rather than letting it play out.
                runCatching { out.pause(); out.flush() }
                out.release()
            }
            audioManager.abandonAudioFocusRequest(focus)
        }
    }

    private fun newTrack(sampleRate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, sampleRate / BUFFER_FRACTION * Float.SIZE_BYTES))
            .build()
    }

    /**
     * Writes all of [samples], a slice at a time so a cancelled utterance stops within a slice
     * instead of blocking until the whole sentence has gone through the buffer.
     */
    private suspend fun AudioTrack.writeAll(samples: FloatArray) {
        val slice = sampleRate / SLICES_PER_SECOND
        var offset = 0
        while (offset < samples.size) {
            coroutineContext.ensureActive()
            val wrote = write(samples, offset, minOf(slice, samples.size - offset), AudioTrack.WRITE_BLOCKING)
            // Zero from a blocking write means the track is no longer playing; looping would spin.
            check(wrote > 0) { "AudioTrack write returned $wrote" }
            offset += wrote
        }
    }

    private companion object {
        const val TAG = "Pronouncer"

        /** Sentences synthesized ahead of the one playing — enough to ride out a slow one. */
        const val SENTENCES_AHEAD = 2

        /** The track buffers a quarter of a second: short, so playback starts and stops promptly. */
        const val BUFFER_FRACTION = 4
        const val SLICES_PER_SECOND = 10

        /** Past the buffer's own length, for the output path's latency. */
        const val DRAIN_MARGIN_MS = 200L

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
