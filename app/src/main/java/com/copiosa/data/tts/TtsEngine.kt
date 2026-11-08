package com.copiosa.data.tts

import com.copiosa.domain.listening.RadioLine
import com.copiosa.domain.text.Sentences
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Display names for the Supertonic voice styles, indexed by sid. */
object SupertonicVoices {
    // Per on-device testing of the pinned 2026-05-11 model: sid 0..4 are female, 5..9 male.
    val NAMES = listOf("Sofía", "Aria", "Lena", "Mia", "Nora", "Leo", "Max", "Theo", "Marco", "Hugo")

    fun name(sid: Int): String? = NAMES.getOrNull(sid)
}

/** A stretch of mono float PCM at [sampleRate], as [TtsEngine.speech] hands it out. */
class SpeechChunk(val samples: FloatArray, val sampleRate: Int)

/**
 * Thin wrapper over sherpa-onnx's [OfflineTts] configured for the Supertonic 3 model. The native
 * engine is built lazily on first synthesis (loading the model files from
 * [SupertonicModelStore.paths]) and reused; callers must ensure the model is downloaded first.
 *
 * Language is passed per call via [GenerationConfig.extra] `{"lang": <iso code>}` — Supertonic 3 is
 * multilingual, so the conversation's target-language tag (e.g. "es") selects the voice's language.
 */
@Singleton
class TtsEngine @Inject constructor(
    private val modelStore: SupertonicModelStore,
) {
    private val mutex = Mutex()

    @Volatile
    private var engine: OfflineTts? = null

    /** Renders [text] to an AAC .m4a at [outFile]; returns its duration in milliseconds. CPU-bound. */
    suspend fun synthesizeToFile(
        text: String,
        langCode: String,
        sid: Int,
        outFile: File,
        speed: Float = 1.0f,
    ): Long = withContext(Dispatchers.Default) {
        val tts = engine()
        val lang = normalizeLang(langCode)
        val sampleRate = tts.sampleRate()
        encodeTo(outFile, sampleRate) { encoder ->
            // Synthesize sentence by sentence with our own gap between, so periods land as full stops
            // even when the model under-pauses on them (notably pt); synthesizeArticle does the same.
            for ((idx, sentence) in Sentences.split(text).withIndex()) {
                ensureActive()
                if (idx > 0) encoder.write(silence(sampleRate, SENTENCE_GAP_SEC))
                encoder.write(tts.generateWithConfig(sentence, genConfig(sid, lang, speed)).samples)
            }
        }
    }

    /**
     * [text] as speech to be played now rather than kept: each sentence is emitted the moment it is
     * synthesized, so the first can be playing while the rest of a long reply is still being
     * rendered. Paced like [synthesizeToFile] — the gap between sentences arrives as a chunk of
     * silence, so a player only has to play what it is given, in order.
     *
     * Cold: nothing is synthesized until it is collected, and cancelling the collector stops it at
     * the next sentence. Put a small `buffer` after it to bound how far synthesis runs ahead.
     */
    fun speech(text: String, langCode: String, sid: Int, speed: Float = 1.0f): Flow<SpeechChunk> = flow {
        val tts = engine()
        val lang = normalizeLang(langCode)
        val sampleRate = tts.sampleRate()
        for ((idx, sentence) in Sentences.split(text).withIndex()) {
            val samples = tts.generateWithConfig(sentence, genConfig(sid, lang, speed)).samples
            if (idx > 0) emit(SpeechChunk(silence(sampleRate, SENTENCE_GAP_SEC), sampleRate))
            emit(SpeechChunk(samples, sampleRate))
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Renders a full playlist "article": an emphasized spoken [title] (slightly louder and slower so
     * it stands out), then the [body], then a soft chime to mark the end of the item. Returns the
     * total duration in ms. The title is skipped when [body] already opens with it (e.g. a short
     * highlighted selection), so a word isn't spoken twice.
     *
     * Each sentence is encoded as soon as it is synthesized, so a body of any length costs one
     * sentence of PCM at a time — this is what lets a whole book chapter be rendered in one item.
     * [onProgress] reports the fraction of sentences done, for those long renders.
     */
    suspend fun synthesizeArticle(
        title: String,
        body: String,
        langCode: String,
        sid: Int,
        outFile: File,
        speed: Float = 1.0f,
        onProgress: ((Float) -> Unit)? = null,
    ): Long = withContext(Dispatchers.Default) {
        val tts = engine()
        val lang = normalizeLang(langCode)
        val sampleRate = tts.sampleRate()

        val t = title.trim()
        val b = body.trim().ifBlank { t }
        val sentences = Sentences.split(b)

        encodeTo(outFile, sampleRate) { encoder ->
            if (t.isNotEmpty() && !b.startsWith(t, ignoreCase = true)) {
                // The title stays slightly slower than the body to set it apart, scaled by the user's speed.
                val titleAudio = tts.generateWithConfig(t, genConfig(sid, lang, speed * TITLE_SPEED))
                encoder.write(amplify(titleAudio.samples, TITLE_GAIN))
                encoder.write(silence(sampleRate, TITLE_GAP_SEC))
            }

            // Speak the body one sentence at a time, inserting our own pause between them. The Supertonic
            // phonemizer under-weights sentence-final periods on some languages (Portuguese especially, where
            // periods can be rushed while commas are respected), so relying on it glues sentences together.
            for ((idx, sentence) in sentences.withIndex()) {
                // A chapter is thousands of sentences; without this a cancelled render runs to the end.
                ensureActive()
                if (idx > 0) encoder.write(silence(sampleRate, SENTENCE_GAP_SEC))
                encoder.write(tts.generateWithConfig(sentence, genConfig(sid, lang, speed)).samples)
                onProgress?.invoke((idx + 1).toFloat() / sentences.size)
            }
            encoder.write(silence(sampleRate, CHIME_GAP_SEC))
            encoder.write(chime(sampleRate))
        }
    }

    /**
     * Renders a cloze radio track: each of [lines] spoken in [targetLang], then its meaning in
     * [nativeLang] (when it has one), then spoken again — with room after the first hearing to work
     * it out before being told. Ends on the same chime an article does. Returns the duration in ms.
     *
     * The translation is given a different voice ([nativeSid]) so the two languages can be told apart
     * with eyes on the road. A line with no translation keeps the same shape with silence in the
     * middle, so the second hearing still comes after a pause to think.
     */
    suspend fun synthesizeRadio(
        lines: List<RadioLine>,
        targetLang: String,
        nativeLang: String,
        sid: Int,
        nativeSid: Int,
        outFile: File,
        speed: Float = 1.0f,
    ): Long = withContext(Dispatchers.Default) {
        val tts = engine()
        val target = normalizeLang(targetLang)
        val native = normalizeLang(nativeLang)
        val sampleRate = tts.sampleRate()

        encodeTo(outFile, sampleRate) { encoder ->
            for ((idx, line) in lines.withIndex()) {
                ensureActive()
                if (idx > 0) encoder.write(silence(sampleRate, RADIO_BETWEEN_SEC))
                // Synthesized once and written twice: the repeat is the same recording, and a track
                // of thirty sentences is otherwise sixty renders.
                val spoken = tts.generateWithConfig(line.target, genConfig(sid, target, speed)).samples
                encoder.write(spoken)
                encoder.write(silence(sampleRate, RADIO_THINK_SEC))
                if (line.native != null) {
                    encoder.write(tts.generateWithConfig(line.native, genConfig(nativeSid, native, speed)).samples)
                    encoder.write(silence(sampleRate, RADIO_AFTER_MEANING_SEC))
                }
                encoder.write(spoken)
            }
            encoder.write(silence(sampleRate, CHIME_GAP_SEC))
            encoder.write(chime(sampleRate))
        }
    }

    /**
     * Runs [body] against a fresh encoder for [outFile], returning the finished duration.
     *
     * On any failure — including cancellation partway through a long chapter — the partial file is
     * deleted. A truncated .m4a is worse than none: the container header is written incrementally,
     * so Media3 would happily play whatever prefix survived and the row would look rendered.
     */
    private inline fun encodeTo(outFile: File, sampleRate: Int, body: (M4aEncoder) -> Unit): Long {
        outFile.parentFile?.mkdirs()
        var ok = false
        try {
            return M4aEncoder(outFile, sampleRate).use { encoder ->
                body(encoder)
                encoder.finish()
            }.also { ok = true }
        } finally {
            if (!ok) outFile.delete()
        }
    }

    /** Number of voice styles the loaded model exposes (selectable as sid 0 until this). */
    suspend fun voiceCount(): Int = withContext(Dispatchers.Default) { engine().numSpeakers() }

    /** Frees native resources; the engine rebuilds on the next call. */
    suspend fun release() = mutex.withLock {
        engine?.release()
        engine = null
    }

    private fun genConfig(sid: Int, normalizedLang: String, speed: Float) =
        GenerationConfig(speed = speed, sid = sid, extra = mapOf("lang" to normalizedLang))

    private fun amplify(samples: FloatArray, gain: Float): FloatArray =
        FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }

    private fun silence(sampleRate: Int, seconds: Double): FloatArray =
        FloatArray((sampleRate * seconds).toInt())

    /** A short, soft two-partial bell with a gentle attack and exponential decay. */
    private fun chime(sampleRate: Int): FloatArray {
        val n = (sampleRate * CHIME_SECONDS).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val time = i.toDouble() / sampleRate
            val env = exp(-time / CHIME_DECAY) * (1.0 - exp(-time / 0.006))
            val tone = sin(2 * PI * CHIME_FREQ_HZ * time) + 0.5 * sin(2 * PI * CHIME_FREQ_HZ * 1.5 * time)
            out[i] = (CHIME_AMPLITUDE * env * tone).toFloat()
        }
        return out
    }

    private suspend fun engine(): OfflineTts = mutex.withLock {
        engine ?: build().also { engine = it }
    }

    private fun build(): OfflineTts {
        val paths = modelStore.paths() ?: error("TTS model is not downloaded")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = paths.durationPredictor,
                    textEncoder = paths.textEncoder,
                    vectorEstimator = paths.vectorEstimator,
                    vocoder = paths.vocoder,
                    ttsJson = paths.ttsJson,
                    unicodeIndexer = paths.unicodeIndexer,
                    voiceStyle = paths.voiceStyle,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
        )
        // assetManager defaults to null, so the model loads from the absolute file paths above.
        return OfflineTts(config = config)
    }

    /** Supertonic uses ISO 639-1 codes; reduce a BCP-47 tag ("es-MX") to its primary subtag. */
    private fun normalizeLang(code: String): String =
        code.substringBefore('-').substringBefore('_').trim().lowercase().ifBlank { "na" }

    companion object {
        /** Fallback voice count before the model is queried; the real count comes from numSpeakers(). */
        const val VOICE_COUNT = 7

        // Title emphasis: a touch louder and slightly slower, with a pause before the body.
        private const val TITLE_GAIN = 1.25f
        private const val TITLE_SPEED = 0.95f
        private const val TITLE_GAP_SEC = 0.45
        private const val CHIME_GAP_SEC = 0.5

        /** Silence between sentences so periods land as full stops; the model under-pauses on some langs (e.g. pt). */
        private const val SENTENCE_GAP_SEC = 0.32

        // Radio pacing. The first pause is the one that matters: long enough to attempt the meaning
        // before it is given, short enough that thirty sentences are not mostly silence.
        private const val RADIO_THINK_SEC = 1.6
        private const val RADIO_AFTER_MEANING_SEC = 0.7
        private const val RADIO_BETWEEN_SEC = 1.4

        // Soft end-of-article chime.
        private const val CHIME_SECONDS = 0.7
        private const val CHIME_DECAY = 0.18
        private const val CHIME_FREQ_HZ = 880.0
        private const val CHIME_AMPLITUDE = 0.28
    }
}
