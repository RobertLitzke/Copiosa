package com.copiosa.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [code] is written into `books.lastError` and read back days later by a different process, so it is
 * persisted data rather than a debugging convenience: the tags have to round-trip exactly, and an
 * unrecognised one has to degrade instead of throwing.
 */
class LlmErrorCodeTest {

    private val all = listOf(
        LlmError.NoKey,
        LlmError.Auth,
        LlmError.Network,
        LlmError.RateLimit,
        LlmError.ServiceBusy,
        LlmError.Refused,
        LlmError.Extraction,
        LlmError.Unknown("502 from the proxy"),
    )

    @Test
    fun `every error round-trips through its code`() {
        all.forEach { assertEquals(it, llmErrorOfCode(it.code)) }
    }

    @Test
    fun `the tags are distinct, so no two errors read back as each other`() {
        assertEquals(all.size, all.map { it.code }.toSet().size)
    }

    @Test
    fun `a tag from a newer build degrades rather than throwing`() {
        // An older build meeting a case it has never heard of must still open the book.
        assertEquals(LlmError.Unknown("CONTENT_POLICY"), llmErrorOfCode("CONTENT_POLICY"))
        assertEquals(LlmError.Unknown(""), llmErrorOfCode(""))
    }

    @Test
    fun `an unknown error keeps its message across the round trip`() {
        val stored = LlmError.Unknown("timeout after 30s").code
        assertEquals("UNKNOWN:timeout after 30s", stored)
        assertEquals(LlmError.Unknown("timeout after 30s"), llmErrorOfCode(stored))
    }
}
