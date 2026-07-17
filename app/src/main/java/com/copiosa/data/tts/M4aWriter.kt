package com.copiosa.data.tts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes mono float PCM to AAC-LC in an MP4 container — the compressed replacement for the WAV
 * output TTS used to produce (~10-15x smaller at transparent-for-speech quality). Media3 and
 * MediaPlayer both play the result; the container is what makes duration/seeking work.
 */
object M4aWriter {

    /**
     * Writes [parts] (concatenated) as an .m4a at [file]; returns the audio duration in
     * milliseconds, matching the old WAV writer's contract. CPU-bound; call off the main thread.
     */
    fun write(file: File, parts: List<FloatArray>, sampleRate: Int): Long {
        val totalSamples = parts.sumOf { it.size }
        // MediaMuxer.stop() throws if no samples were ever written, so give empty input a
        // sliver of silence rather than producing an unplayable zero-length file.
        val input = if (totalSamples == 0) listOf(FloatArray(sampleRate / 20)) else parts

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var samplesQueued = 0L
            val pcm = PcmFeeder(input)

            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        // PTS must be derived from samples queued so far, not wall time, to stay
                        // monotonic and give the container an exact duration.
                        val ptsUs = samplesQueued * 1_000_000L / sampleRate
                        val samplesWritten = pcm.fill(buf)
                        if (samplesWritten == 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, samplesWritten * 2, ptsUs, 0)
                            samplesQueued += samplesWritten
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The muxer takes codec-specific data from this format; it must not be
                        // started before, and won't change after.
                        check(!muxerStarted) { "AAC output format changed twice" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && bufferInfo.size > 0) {
                            check(muxerStarted) { "AAC sample produced before output format" }
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        return totalSamples.toLong() * 1000L / sampleRate
    }

    /** Streams float samples as little-endian 16-bit PCM into codec input buffers. */
    private class PcmFeeder(private val parts: List<FloatArray>) {
        private var partIndex = 0
        private var offset = 0

        /** Fills [buf] with as many samples as fit; returns the sample count (0 = exhausted). */
        fun fill(buf: ByteBuffer): Int {
            buf.clear()
            buf.order(ByteOrder.LITTLE_ENDIAN)
            var written = 0
            while (partIndex < parts.size && buf.remaining() >= 2) {
                val part = parts[partIndex]
                if (offset >= part.size) {
                    partIndex++
                    offset = 0
                    continue
                }
                val n = minOf(part.size - offset, buf.remaining() / 2)
                for (i in offset until offset + n) {
                    buf.putShort((part[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
                offset += n
                written += n
            }
            return written
        }
    }

    /** 80kbps mono AAC-LC: transparent for a single synthetic voice, ~7KB/s vs WAV's ~88KB/s. */
    private const val BIT_RATE = 80_000
    private const val MAX_INPUT_BYTES = 16 * 1024
    private const val TIMEOUT_US = 10_000L
}
