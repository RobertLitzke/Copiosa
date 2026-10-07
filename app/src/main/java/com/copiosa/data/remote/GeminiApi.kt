package com.copiosa.data.remote

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** Google Generative Language API. Base URL: https://generativelanguage.googleapis.com/ */
interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generate(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body body: GeminiRequest,
    ): GeminiResponse
}

@Serializable
data class GeminiRequest(
    @SerialName("systemInstruction") val systemInstruction: GeminiContent? = null,
    val contents: List<GeminiContent>,
    @SerialName("generationConfig") val generationConfig: GeminiGenerationConfig? = null,
)

/**
 * Every member is optional and left out of the body when unset: the shared `Json` encodes defaults,
 * and an explicit `"thinkingConfig": null` is not the same request as no thinking config at all.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiGenerationConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("thinkingConfig") val thinkingConfig: GeminiThinkingConfig? = null,
    /** Ceiling on the reply. Thinking tokens are counted against it too — see [GeminiProvider]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
    /** `application/json` switches on JSON mode; unset is ordinary text. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("responseMimeType") val responseMimeType: String? = null,
)

/**
 * Gemini 2.5+/3.x "flash" models think by default with a dynamic budget. For graded comprehensible
 * input a chain of thought is overkill, so we pin the budget to 0 to switch thinking off where the
 * model supports it (see [GeminiProvider]).
 */
@Serializable
data class GeminiThinkingConfig(
    @SerialName("thinkingBudget") val thinkingBudget: Int,
)

/**
 * [parts] defaults to empty because a reply cut off before its first token comes back as a content
 * object with a role and nothing else; requiring the list would turn that into a decode failure
 * and hide the `MAX_TOKENS` beside it.
 */
@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
data class GeminiPart(val text: String = "")

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    /** Token counts for the call; left as raw JSON and read by [geminiUsageOf], which says why. */
    @SerialName("usageMetadata") val usageMetadata: JsonElement? = null,
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    /** `STOP` for a finished reply; [FINISH_MAX_TOKENS] when the output ceiling cut it short. */
    @SerialName("finishReason") val finishReason: String? = null,
) {
    companion object {
        const val FINISH_MAX_TOKENS = "MAX_TOKENS"
    }
}
