package com.copiosa.data.tts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

/**
 * Incremental AAC-LC encoder writing an MP4 container — the streaming form of [M4aWriter.write].
 *
 * Peak memory is one codec buffer rather than the whole render, which is what makes long-form
 * synthesis possible at all: a chapter of a book is ~20 minutes of audio, and holding that as float
 * PCM first would be ~115 MB (at ~24 kHz, ~5.8 MB per minute) before a single byte was encoded.
 * Callers feed [write] a sentence at a time and the samples are gone as soon as they are queued.
 *
 * Not thread-safe, and single-use: [write] until done, then [finish] once. CPU-bound; call off the
 * main thread. Always close it (via [use]) — a codec and a muxer are native resources.
 */
class M4aEncoder(file: File, private val sampleRate: Int) : Closeable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val bufferInfo = MediaCodec.BufferInfo()

    private var muxerStarted = false
    private var trackIndex = -1
    private var samplesQueued = 0L
    private var finished = false
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        // Released by hand rather than by the caller's `use`: a constructor that throws never
        // returns an object to close, and both of these hold native resources (the muxer, a file
        // descriptor on the output path).
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            muxer.release()
            throw e
        }
    }

    /** Appends [samples] (mono float) to the stream, draining encoder output as it goes. */
    fun write(samples: FloatArray) {
        check(!finished) { "M4aEncoder.write after finish()" }
        var offset = 0
        while (offset < samples.size) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)!!
                buf.clear()
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val n = minOf(samples.size - offset, buf.remaining() / 2)
                check(n > 0) { "AAC input buffer too small to hold a sample" }
                for (i in offset until offset + n) {
                    buf.putShort((samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
                // PTS must be derived from samples queued so far, not wall time, to stay monotonic
                // and give the container an exact duration. Read before samplesQueued advances.
                codec.queueInputBuffer(inIndex, 0, n * 2, ptsUs(), 0)
                offset += n
                samplesQueued += n
            }
            drain(endOfStream = false)
        }
    }

    /** Flushes the encoder and finalises the container; returns the audio duration in milliseconds. */
    fun finish(): Long {
        check(!finished) { "M4aEncoder.finish() called twice" }
        // MediaMuxer.stop() throws if no samples were ever written, so give empty input a sliver of
        // silence rather than producing an unplayable zero-length file.
        if (samplesQueued == 0L) write(FloatArray(sampleRate / 20))
        finished = true

        var queued = false
        while (!queued) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else {
                drain(endOfStream = false)
            }
        }
        drain(endOfStream = true)
        return samplesQueued * 1000L / sampleRate
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        codec.release()
        if (muxerStarted) runCatching { muxer.stop() }
        muxer.release()
    }

    private fun ptsUs(): Long = samplesQueued * 1_000_000L / sampleRate

    /**
     * Moves whatever the encoder has ready into the muxer. Returns as soon as nothing more is
     * available unless [endOfStream], in which case it keeps waiting for the EOS-flagged buffer.
     *
     * The mid-stream drain polls with no timeout: [write] calls it after every input buffer, and a
     * chapter is thousands of those — waiting [TIMEOUT_US] on each just to be told there is nothing
     * ready yet would add minutes of pure sleeping to a long render. [write]'s own
     * `dequeueInputBuffer` still blocks, so the loop never becomes a busy spin.
     */
    private fun drain(endOfStream: Boolean) {
        val timeout = if (endOfStream) TIMEOUT_US else 0L
        while (true) {
            when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeout)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // The muxer takes codec-specific data from this format; it must not be started
                    // before, and won't change after.
                    check(!muxerStarted) { "AAC output format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                else -> if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && bufferInfo.size > 0) {
                        check(muxerStarted) { "AAC sample produced before output format" }
                        muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    internal companion object {
        /** 80kbps mono AAC-LC: transparent for a single synthetic voice, ~7KB/s vs WAV's ~88KB/s. */
        const val BIT_RATE = 80_000
        const val MAX_INPUT_BYTES = 16 * 1024
        const val TIMEOUT_US = 10_000L
    }
}

/**
 * Encodes mono float PCM to AAC-LC in an MP4 container — the compressed replacement for the WAV
 * output TTS used to produce (~10-15x smaller at transparent-for-speech quality). Media3 and
 * MediaPlayer both play the result; the container is what makes duration/seeking work.
 *
 * This whole-input form is for renders small enough to hold in memory (the WAV compaction migration).
 * Anything long-form should drive an [M4aEncoder] directly so the samples never all exist at once.
 */
object M4aWriter {

    /**
     * Writes [parts] (concatenated) as an .m4a at [file]; returns the audio duration in
     * milliseconds, matching the old WAV writer's contract. CPU-bound; call off the main thread.
     */
    fun write(file: File, parts: List<FloatArray>, sampleRate: Int): Long =
        M4aEncoder(file, sampleRate).use { encoder ->
            parts.forEach(encoder::write)
            encoder.finish()
        }
}
