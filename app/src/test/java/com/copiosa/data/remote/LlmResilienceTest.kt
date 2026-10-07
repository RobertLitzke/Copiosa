package com.copiosa.data.remote

import com.copiosa.domain.usage.LlmFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlmResilienceTest {

    @Test
    fun `tryLlm returns the value, or null after reporting a failure`() {
        assertEquals(3, tryLlm { 3 })

        var seen: Exception? = null
        val result = tryLlm({ seen = it }) { error("nope") }
        assertNull(result)
        assertEquals("nope", seen?.message)
    }

    @Test
    fun `tryLlm lets cancellation through`() {
        var reported = false
        try {
            tryLlm({ reported = true }) { throw CancellationException("stop") }
            fail("expected the cancellation to propagate")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
        assertEquals(false, reported)
    }

    @Test
    fun `a blank reply is a refusal, text is kept`() {
        for (blank in listOf("", "  ", "\n")) {
            val e = runCatching { llmResponseOf(blank, truncated = false) }.exceptionOrNull()
            assertEquals(LlmError.Refused, (e as LlmException).error)
        }
        assertEquals(LlmResponse("hola", truncated = true), llmResponseOf("hola", truncated = true))
    }

    @Test
    fun `cancellation is not turned into an Unknown error`() {
        val cancelled = CancellationException("left")
        val thrown = runCatching { cancelled.toLlmException() }.exceptionOrNull()
        assertTrue(thrown === cancelled)
    }

    private class FakeProvider(private val reply: () -> LlmResponse) : LlmProvider {
        override val id = com.copiosa.domain.model.ProviderId.GEMINI
        override suspend fun chat(req: LlmRequest) = reply()
    }

    private val req = LlmRequest(systemPrompt = "", messages = emptyList(), model = "m", feature = LlmFeature.CHAT)

    @Test
    fun `chatOrEmpty swallows a refusal and nothing else`() = runBlocking {
        assertEquals("ok", FakeProvider { LlmResponse("ok") }.chatOrEmpty(req))
        assertEquals("", FakeProvider { throw LlmException(LlmError.Refused) }.chatOrEmpty(req))
        val e = runCatching { FakeProvider { throw LlmException(LlmError.Auth) }.chatOrEmpty(req) }.exceptionOrNull()
        assertEquals(LlmError.Auth, (e as LlmException).error)
    }
}
