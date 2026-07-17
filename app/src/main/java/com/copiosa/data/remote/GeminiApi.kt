package com.copiosa.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

@Serializable
data class GeminiGenerationConfig(
    @SerialName("thinkingConfig") val thinkingConfig: GeminiThinkingConfig? = null,
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

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>,
)

@Serializable
data class GeminiPart(val text: String)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
)
