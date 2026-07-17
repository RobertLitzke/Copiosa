package com.copiosa.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `round-trips 16-bit mono pcm`() {
        val samples = shortArrayOf(0, 32767, -32768, 12345, -1)
        val file = writeWav(samples, sampleRate = 44100)

        val audio = WavPcm.read(file)!!

        assertEquals(44100, audio.sampleRate)
        assertEquals(samples.size, audio.samples.size)
        for (i in samples.indices) {
            assertEquals(samples[i] / 32768f, audio.samples[i], 0f)
        }
    }

    @Test
    fun `skips unknown riff chunks before data`() {
        val samples = shortArrayOf(100, -100)
        val file = writeWav(samples, sampleRate = 22050, extraChunk = true)

        val audio = WavPcm.read(file)!!

        assertEquals(22050, audio.sampleRate)
        assertEquals(2, audio.samples.size)
    }

    @Test
    fun `rejects stereo`() {
        assertNull(WavPcm.read(writeWav(shortArrayOf(1, 2), sampleRate = 44100, channels = 2)))
    }

    @Test
    fun `rejects non-wav and truncated files`() {
        val junk = tmp.newFile().apply { writeBytes(ByteArray(100) { it.toByte() }) }
        assertNull(WavPcm.read(junk))
        val tiny = tmp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(WavPcm.read(tiny))
    }

    @Test
    fun `handles empty data chunk`() {
        val audio = WavPcm.read(writeWav(shortArrayOf(), sampleRate = 44100))!!
        assertTrue(audio.samples.isEmpty())
    }

    /** Writes a WAV the same way the app's old writer did, optionally with a stray chunk. */
    private fun writeWav(
        samples: ShortArray,
        sampleRate: Int,
        channels: Int = 1,
        extraChunk: Boolean = false,
    ): File {
        val dataSize = samples.size * 2
        val extraSize = if (extraChunk) 8 + 4 else 0
        val buf = ByteBuffer.allocate(44 + extraSize + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + extraSize + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2 * channels)
        buf.putShort((2 * channels).toShort())
        buf.putShort(16)
        if (extraChunk) {
            buf.put("LIST".toByteArray(Charsets.US_ASCII))
            buf.putInt(4)
            buf.put("INFO".toByteArray(Charsets.US_ASCII))
        }
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in samples) buf.putShort(s)
        return tmp.newFile().apply { writeBytes(buf.array()) }
    }
}
