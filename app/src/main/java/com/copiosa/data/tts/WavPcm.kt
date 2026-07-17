package com.copiosa.data.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for the 16-bit mono PCM WAVs this app itself wrote before switching to AAC
 * ([M4aWriter]). Only used by the one-time migration that re-encodes legacy files; it is not a
 * general WAV parser, but it does walk RIFF chunks so an unexpected extra chunk won't break it.
 */
object WavPcm {

    data class Audio(val samples: FloatArray, val sampleRate: Int)

    /** Decodes [file]; returns null (rather than throwing) for anything but 16-bit mono PCM. */
    fun read(file: File): Audio? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 44) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (readTag(buf) != "RIFF") return null
        buf.int // RIFF size, unreliable in truncated files; chunk walk below bounds itself
        if (readTag(buf) != "WAVE") return null

        var sampleRate = 0
        var valid = false
        while (buf.remaining() >= 8) {
            val tag = readTag(buf)
            val size = buf.int
            if (size < 0 || size > buf.remaining()) return null
            when (tag) {
                "fmt " -> {
                    if (size < 16) return null
                    val start = buf.position()
                    val format = buf.short.toInt()
                    val channels = buf.short.toInt()
                    sampleRate = buf.int
                    buf.int // byte rate
                    buf.short // block align
                    val bitsPerSample = buf.short.toInt()
                    valid = format == 1 && channels == 1 && sampleRate > 0 && bitsPerSample == 16
                    buf.position(start + size + (size and 1))
                }
                "data" -> {
                    if (!valid) return null
                    val sampleCount = size / 2
                    val samples = FloatArray(sampleCount)
                    for (i in 0 until sampleCount) {
                        samples[i] = buf.short / 32768f
                    }
                    return Audio(samples, sampleRate)
                }
                else -> buf.position(buf.position() + size + (size and 1))
            }
        }
        return null
    }

    private fun readTag(buf: ByteBuffer): String {
        val b = ByteArray(4)
        buf.get(b)
        return String(b, Charsets.US_ASCII)
    }
}
