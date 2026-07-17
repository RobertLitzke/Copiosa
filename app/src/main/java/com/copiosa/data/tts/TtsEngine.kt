package com.copiosa.data.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
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
        // Synthesize sentence by sentence with our own gap between, so periods land as full stops even
        // when the model under-pauses on them (notably pt); see synthesizeArticle for the same treatment.
        val parts = ArrayList<FloatArray>()
        for ((idx, sentence) in splitForSpeech(text).withIndex()) {
            if (idx > 0) parts += silence(sampleRate, SENTENCE_GAP_SEC)
            parts += tts.generateWithConfig(sentence, genConfig(sid, lang, speed)).samples
        }
        outFile.parentFile?.mkdirs()
        M4aWriter.write(outFile, parts, sampleRate)
    }

    /**
     * Renders a full playlist "article": an emphasized spoken [title] (slightly louder and slower so
     * it stands out), then the [body], then a soft chime to mark the end of the item. Returns the
     * total duration in ms. The title is skipped when [body] already opens with it (e.g. a short
     * highlighted selection), so a word isn't spoken twice.
     */
    suspend fun synthesizeArticle(
        title: String,
        body: String,
        langCode: String,
        sid: Int,
        outFile: File,
        speed: Float = 1.0f,
    ): Long = withContext(Dispatchers.Default) {
        val tts = engine()
        val lang = normalizeLang(langCode)
        val sampleRate = tts.sampleRate()
        val parts = ArrayList<FloatArray>()

        val t = title.trim()
        val b = body.trim().ifBlank { t }
        if (t.isNotEmpty() && !b.startsWith(t, ignoreCase = true)) {
            // The title stays slightly slower than the body to set it apart, scaled by the user's speed.
            val titleAudio = tts.generateWithConfig(t, genConfig(sid, lang, speed * TITLE_SPEED))
            parts += amplify(titleAudio.samples, TITLE_GAIN)
            parts += silence(sampleRate, TITLE_GAP_SEC)
        }

        // Speak the body one sentence at a time, inserting our own pause between them. The Supertonic
        // phonemizer under-weights sentence-final periods on some languages (Portuguese especially, where
        // periods can be rushed while commas are respected), so relying on it glues sentences together.
        for ((idx, sentence) in splitForSpeech(b).withIndex()) {
            if (idx > 0) parts += silence(sampleRate, SENTENCE_GAP_SEC)
            parts += tts.generateWithConfig(sentence, genConfig(sid, lang, speed)).samples
        }
        parts += silence(sampleRate, CHIME_GAP_SEC)
        parts += chime(sampleRate)

        outFile.parentFile?.mkdirs()
        M4aWriter.write(outFile, parts, sampleRate)
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

    /**
     * Splits [text] into speakable segments at sentence-final punctuation and line breaks, so the caller
     * can insert its own pause between them. Deliberately biased toward over-splitting: a spurious split
     * only adds a short gap, whereas a missed split reproduces the run-on pacing this exists to fix. A
     * period is not treated as a boundary unless followed by whitespace/end (so decimals like "3.5" stay
     * intact) nor after a few common pt/es abbreviations ("Sr.", "Dr.", "etc."). Returns trimmed,
     * non-blank segments, or a single-element list when [text] holds no interior sentence boundary.
     */
    private fun splitForSpeech(text: String): List<String> {
        val segments = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            var start = 0
            var i = 0
            while (i < line.length) {
                if (line[i] == '.' || line[i] in TERMINATORS) {
                    // Extend across a run of terminators like "?!" or "..." so we split once, after it.
                    var end = i
                    while (end + 1 < line.length && (line[end + 1] == '.' || line[end + 1] in TERMINATORS)) end++
                    val next = line.getOrNull(end + 1)
                    val atBoundary = next == null || next.isWhitespace()
                    val abbrev = line[i] == '.' && end == i && endsWithAbbrev(line, start, i)
                    if (atBoundary && !abbrev) {
                        segments += line.substring(start, end + 1).trim()
                        var k = end + 1
                        while (k < line.length && line[k].isWhitespace()) k++
                        start = k
                        i = k
                        continue
                    }
                    i = end + 1
                } else {
                    i++
                }
            }
            if (start < line.length) segments += line.substring(start).trim()
        }
        val cleaned = segments.filter { it.isNotEmpty() }
        return if (cleaned.size <= 1) listOfNotNull(text.trim().ifBlank { null }) else cleaned
    }

    /** True when the token ending at the period [dotIndex] (back to the last space or [start]) is a known abbreviation. */
    private fun endsWithAbbrev(line: String, start: Int, dotIndex: Int): Boolean {
        var b = dotIndex - 1
        while (b >= start && !line[b].isWhitespace()) b--
        return line.substring(b + 1, dotIndex + 1).lowercase() in ABBREVIATIONS
    }

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

        /** Sentence-final punctuation other than '.', which needs the decimal/abbreviation guards. */
        private const val TERMINATORS = "!?…。"

        /** Lowercased pt/es abbreviations (with trailing dot) whose period must not end a sentence. */
        private val ABBREVIATIONS = setOf(
            "sr.", "sra.", "srs.", "dr.", "dra.", "prof.", "profa.",
            "etc.", "ex.", "p.ex.", "sto.", "sta.", "av.", "pág.", "núm.", "no.", "vs.",
        )

        // Soft end-of-article chime.
        private const val CHIME_SECONDS = 0.7
        private const val CHIME_DECAY = 0.18
        private const val CHIME_FREQ_HZ = 880.0
        private const val CHIME_AMPLITUDE = 0.28
    }
}
