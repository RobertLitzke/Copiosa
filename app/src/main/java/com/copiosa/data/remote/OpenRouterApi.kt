package com.copiosa.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

/** OpenRouter chat-completions API (OpenAI-compatible). Base URL: https://openrouter.ai/api/v1/ */
interface OpenRouterApi {
    // X-Title is an optional attribution header OpenRouter shows on its activity/rankings pages.
    @Headers("X-Title: Copiosa")
    @POST("chat/completions")
    suspend fun chat(
        @Header("Authorization") authorization: String,
        @Body body: OpenRouterRequest,
    ): OpenRouterResponse
}

@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
)

@Serializable
data class OpenRouterMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OpenRouterResponse(
    val choices: List<OpenRouterChoice> = emptyList(),
)

@Serializable
data class OpenRouterChoice(
    val message: OpenRouterMessage? = null,
)
