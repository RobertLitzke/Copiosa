package com.copiosa.data.tts

import android.content.Context
import com.copiosa.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where the user is in the lifecycle of the (opt-in) on-device voice model. */
sealed interface TtsModelState {
    data object NotDownloaded : TtsModelState

    /** [progress] is 0..1, or -1 when the server didn't report a content length. */
    data class Downloading(val progress: Float) : TtsModelState

    data object Ready : TtsModelState

    data class Failed(val message: String) : TtsModelState
}

/** Absolute paths to the seven Supertonic model files, fed to [com.k2fsa.sherpa.onnx.OfflineTts]. */
data class SupertonicPaths(
    val durationPredictor: String,
    val textEncoder: String,
    val vectorEstimator: String,
    val vocoder: String,
    val ttsJson: String,
    val unicodeIndexer: String,
    val voiceStyle: String,
)

/**
 * Owns the on-device Supertonic TTS model (sherpa-onnx). The model is a ~100 MB download, so it is
 * **never fetched automatically** — only [download] (invoked from a user-facing button) pulls it. The
 * archive is streamed, decompressed (bzip2) and untarred into `filesDir/tts_supertonic`. The current
 * [state] drives the Settings card, the Audio tab gating, and the "Send to audio" download prompt.
 *
 * Modelled on [com.copiosa.data.translate.LocalDictionary], but explicit rather than lazy.
 */
@Singleton
class SupertonicModelStore @Inject constructor(
    @ApplicationContext context: Context,
    private val http: OkHttpClient,
    @AppScope private val scope: CoroutineScope,
) {
    private val dir = File(context.filesDir, "tts_supertonic")
    private val mutex = Mutex()

    private val _state = MutableStateFlow<TtsModelState>(
        if (isComplete()) TtsModelState.Ready else TtsModelState.NotDownloaded,
    )
    val state: StateFlow<TtsModelState> = _state.asStateFlow()

    fun isReady(): Boolean = _state.value is TtsModelState.Ready

    /** Resolved file paths, or null if the model isn't fully present. */
    fun paths(): SupertonicPaths? {
        if (!isComplete()) return null
        return SupertonicPaths(
            durationPredictor = File(dir, "duration_predictor.int8.onnx").absolutePath,
            textEncoder = File(dir, "text_encoder.int8.onnx").absolutePath,
            vectorEstimator = File(dir, "vector_estimator.int8.onnx").absolutePath,
            vocoder = File(dir, "vocoder.int8.onnx").absolutePath,
            ttsJson = File(dir, "tts.json").absolutePath,
            unicodeIndexer = File(dir, "unicode_indexer.bin").absolutePath,
            voiceStyle = File(dir, "voice.bin").absolutePath,
        )
    }

    /** Starts the download if it isn't already running or complete. Safe to call repeatedly. */
    fun download() {
        if (_state.value is TtsModelState.Downloading) return
        // IO, not the scope's default: the download and extraction block.
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (isComplete()) {
                    _state.value = TtsModelState.Ready
                    return@withLock
                }
                _state.value = TtsModelState.Downloading(0f)
                try {
                    downloadAndExtract { p -> _state.value = TtsModelState.Downloading(p) }
                    if (isComplete()) {
                        _state.value = TtsModelState.Ready
                    } else {
                        cleanup()
                        _state.value = TtsModelState.Failed("Download incomplete")
                    }
                } catch (e: CancellationException) {
                    cleanup()
                    _state.value = TtsModelState.NotDownloaded
                    throw e
                } catch (e: Exception) {
                    cleanup()
                    _state.value = TtsModelState.Failed(e.message ?: "Download failed")
                }
            }
        }
    }

    /** Deletes the model and reclaims the space. */
    fun remove() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                cleanup()
                _state.value = TtsModelState.NotDownloaded
            }
        }
    }

    private fun isComplete(): Boolean =
        REQUIRED_FILES.all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }

    private fun cleanup() {
        runCatching { dir.deleteRecursively() }
    }

    private fun downloadAndExtract(onProgress: (Float) -> Unit) {
        dir.mkdirs()
        val request = Request.Builder().url(MODEL_URL).build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body ?: throw IOException("Empty response body")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val total = body.contentLength()
            val counting = CountingInputStream(body.byteStream()) { read ->
                onProgress(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f)
            }
            BZip2CompressorInputStream(BufferedInputStream(counting)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val name = File(entry.name).name
                        if (!entry.isDirectory && name in REQUIRED_FILES) {
                            File(dir, name).outputStream().use { out -> tar.copyTo(out) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    /** Reports cumulative bytes read, throttled, so download progress can be surfaced. */
    private class CountingInputStream(
        delegate: InputStream,
        private val onRead: (Long) -> Unit,
    ) : FilterInputStream(delegate) {
        private var count = 0L
        private var lastReported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) bump(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }

        private fun bump(n: Long) {
            count += n
            if (count - lastReported >= REPORT_EVERY_BYTES) {
                lastReported = count
                onRead(count)
            }
        }
    }

    private companion object {
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"

        const val REPORT_EVERY_BYTES = 512L * 1024L

        val REQUIRED_FILES = setOf(
            "duration_predictor.int8.onnx",
            "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx",
            "vocoder.int8.onnx",
            "tts.json",
            "unicode_indexer.bin",
            "voice.bin",
        )
    }
}
