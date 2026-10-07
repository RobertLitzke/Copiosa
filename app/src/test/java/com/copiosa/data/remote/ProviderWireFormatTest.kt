package com.copiosa.data.remote

import com.copiosa.domain.model.Role
import com.copiosa.domain.usage.LlmFeature
import com.copiosa.domain.usage.TokenUsage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * What the two providers put on the wire and read back off it. Neither can be exercised against the
 * real service from a unit test, so this pins the parts that are easy to get quietly wrong: optional
 * request members must be *absent* rather than null (the app's shared `Json` encodes defaults), and
 * a reply that hit the output ceiling must decode and say so.
 */
class ProviderWireFormatTest {

    /** The same configuration `NetworkModule` hands Retrofit. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun request(model: String, maxTokens: Int = 256, shape: JsonShape? = null) = LlmRequest(
        systemPrompt = "",
        messages = listOf(LlmMessage(Role.USER, "hola")),
        model = model,
        feature = LlmFeature.CHAT,
        maxTokens = maxTokens,
        json = shape,
    )

    private fun geminiConfig(req: LlmRequest): String =
        json.encodeToString(GeminiGenerationConfig.serializer(), GeminiProvider.generationConfigFor(req))

    @Test
    fun `gemini sends the output ceiling exactly where thinking is off`() {
        for (model in listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")) {
            val body = geminiConfig(request(model))
            assertTrue(body, body.contains("\"maxOutputTokens\":256"))
            assertTrue(body, body.contains("\"thinkingBudget\":0"))
        }
        val plain = geminiConfig(request("gemini-2.0-flash"))
        assertTrue(plain, plain.contains("\"maxOutputTokens\":256"))
        assertFalse(plain, plain.contains("thinkingConfig"))
    }

    @Test
    fun `gemini leaves room to think on models that may`() {
        val expected = 256 + THINKING_HEADROOM
        val pro = geminiConfig(request("gemini-2.5-pro"))
        assertTrue(pro, pro.contains("\"maxOutputTokens\":$expected"))
        // No budget is sent for pro, and an explicit null is not the same as leaving it out.
        assertFalse(pro, pro.contains("thinkingConfig"))
        assertTrue(geminiConfig(request("gemini-3.5-flash")).contains("\"maxOutputTokens\":$expected"))
    }

    @Test
    fun `gemini json mode is on for either shape and absent for prose`() {
        for (shape in JsonShape.entries) {
            assertTrue(geminiConfig(request("gemini-2.5-flash", shape = shape)).contains("application/json"))
        }
        assertFalse(geminiConfig(request("gemini-2.5-flash")).contains("responseMimeType"))
    }

    @Test
    fun `a gemini reply cut off before any text still decodes`() {
        val response = json.decodeFromString(
            GeminiResponse.serializer(),
            """{"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS"}]}""",
        )
        val candidate = response.candidates.single()
        assertEquals(GeminiCandidate.FINISH_MAX_TOKENS, candidate.finishReason)
        assertTrue(candidate.content!!.parts.isEmpty())
    }

    @Test
    fun `openrouter leaves response_format out unless an object is wanted`() {
        fun body(format: OpenRouterResponseFormat?) = json.encodeToString(
            OpenRouterRequest.serializer(),
            OpenRouterRequest("m", listOf(OpenRouterMessage("user", "hola")), maxTokens = 256, responseFormat = format),
        )
        assertFalse(body(null).contains("response_format"))
        assertTrue(body(OpenRouterResponseFormat.JSON_OBJECT).contains("\"response_format\":{\"type\":\"json_object\"}"))
    }

    /** The ceilings [OpenRouterProvider.withHeadroom] tried, in order, when the first is answered with [firstStatus]. */
    private fun capsTried(maxTokens: Int, firstStatus: Int?): Pair<List<Int>, Result<String>> {
        val tried = mutableListOf<Int>()
        val result = runCatching {
            runBlocking {
                OpenRouterProvider.withHeadroom(maxTokens) { cap ->
                    tried += cap
                    if (firstStatus != null && tried.size == 1) {
                        throw HttpException(Response.error<Unit>(firstStatus, "{}".toResponseBody()))
                    }
                    "hola"
                }
            }
        }
        return tried to result
    }

    @Test
    fun `openrouter leaves room to reason on every model`() {
        // The request is built with the ceiling the caller asked for; the headroom goes on as it is sent.
        assertEquals(512, OpenRouterProvider.requestFor(request("deepseek/deepseek-v4.1-flash", maxTokens = 512)).maxTokens)
        val (tried, result) = capsTried(512, firstStatus = null)
        assertEquals(listOf(512 + THINKING_HEADROOM), tried)
        assertEquals("hola", result.getOrThrow())
    }

    @Test
    fun `openrouter falls back to the bare ceiling when the lifted one is refused`() {
        val (tried, result) = capsTried(8192, firstStatus = 400)
        assertEquals(listOf(8192 + THINKING_HEADROOM, 8192), tried)
        assertEquals("hola", result.getOrThrow())
    }

    @Test
    fun `openrouter does not send a second request for any other failure`() {
        for (status in listOf(401, 402, 429, 503)) {
            val (tried, result) = capsTried(512, firstStatus = status)
            assertEquals(1, tried.size)
            assertEquals(status, (result.exceptionOrNull() as HttpException).code())
        }
    }

    @Test
    fun `an openrouter reply with no content decodes and reports the cut`() {
        val response = json.decodeFromString(
            OpenRouterResponse.serializer(),
            """{"choices":[{"message":{"role":"assistant","content":null},"finish_reason":"length"}]}""",
        )
        val choice = response.choices.single()
        assertEquals(OpenRouterChoice.FINISH_LENGTH, choice.finishReason)
        assertEquals(null, choice.message!!.content)
    }

    @Test
    fun `gemini usage counts thinking as output and keeps the cached share`() {
        val response = json.decodeFromString(
            GeminiResponse.serializer(),
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"hola"}]},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":1200,"cachedContentTokenCount":800,
               "candidatesTokenCount":300,"thoughtsTokenCount":150,"totalTokenCount":1650,
               "promptTokensDetails":[{"modality":"TEXT","tokenCount":1200}]}}""",
        )
        assertEquals(
            TokenUsage(promptTokens = 1200, cachedTokens = 800, outputTokens = 450, costUsd = null),
            geminiUsageOf(response.usageMetadata),
        )
    }

    @Test
    fun `a gemini reply with no text still reports what it cost`() {
        // The refusal path: no candidates at all, but the prompt was read and is billed.
        val response = json.decodeFromString(
            GeminiResponse.serializer(),
            """{"usageMetadata":{"promptTokenCount":640,"totalTokenCount":640}}""",
        )
        assertEquals(TokenUsage(640, 0, 0), geminiUsageOf(response.usageMetadata))
    }

    @Test
    fun `openrouter usage carries the charge, and adds the upstream one for a byok call`() {
        val plain = json.decodeFromString(
            OpenRouterResponse.serializer(),
            """{"choices":[{"message":{"role":"assistant","content":"hola"},"finish_reason":"stop"}],
               "usage":{"prompt_tokens":194,"completion_tokens":20,"total_tokens":214,"cost":0.00042,
               "prompt_tokens_details":{"cached_tokens":64,"audio_tokens":0},
               "completion_tokens_details":{"reasoning_tokens":8},
               "cost_details":{"upstream_inference_cost":null}}}""",
        )
        assertEquals(TokenUsage(194, 64, 20, costUsd = 0.00042), openRouterUsageOf(plain.usage))

        val byok = json.decodeFromString(
            OpenRouterResponse.serializer(),
            """{"usage":{"prompt_tokens":10,"completion_tokens":2,"cost":0.05,
               "cost_details":{"upstream_inference_cost":1.0}}}""",
        )
        assertEquals(1.05, openRouterUsageOf(byok.usage)!!.costUsd!!, 1e-9)
    }

    @Test
    fun `openrouter usage without a cost is counted but not priced`() {
        val response = json.decodeFromString(
            OpenRouterResponse.serializer(),
            """{"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
        )
        assertEquals(TokenUsage(10, 0, 2, costUsd = null), openRouterUsageOf(response.usage))
    }

    @Test
    fun `a usage block in an unexpected shape never fails the reply`() {
        // Each of these would throw from a typed field; the reply beside them must still decode.
        val odd = listOf(
            """{"choices":[],"usage":null}""",
            """{"choices":[],"usage":"n/a"}""",
            """{"choices":[],"usage":[1,2]}""",
            """{"choices":[],"usage":{"prompt_tokens":"12","completion_tokens":3.0,"cost":"0.5"}}""",
            """{"choices":[],"usage":{"prompt_tokens":null,"completion_tokens":-4,"cost":{}}}""",
        )
        val parsed = odd.map { openRouterUsageOf(json.decodeFromString(OpenRouterResponse.serializer(), it).usage) }
        assertNull(parsed[0])
        assertNull(parsed[1])
        assertNull(parsed[2])
        // Numbers written as strings or floats are still numbers.
        assertEquals(TokenUsage(12, 0, 3, costUsd = 0.5), parsed[3])
        // Nothing usable: a null and a negative are both "not reported".
        assertNull(parsed[4])

        assertNull(geminiUsageOf(json.decodeFromString(GeminiResponse.serializer(), """{"usageMetadata":7}""").usageMetadata))
        assertNull(geminiUsageOf(json.decodeFromString(GeminiResponse.serializer(), """{"usageMetadata":{}}""").usageMetadata))
        assertNull(geminiUsageOf(null))
    }
}
