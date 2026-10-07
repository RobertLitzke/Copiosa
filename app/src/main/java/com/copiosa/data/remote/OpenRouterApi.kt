package com.copiosa.data.remote

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    /** Ceiling on the reply. Reasoning tokens are counted against it too — see [OpenRouterProvider]. */
    @SerialName("max_tokens") val maxTokens: Int,
    /** Left out of the body entirely when unset, rather than sent as an explicit null. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("response_format") val responseFormat: OpenRouterResponseFormat? = null,
)

@Serializable
data class OpenRouterResponseFormat(val type: String) {
    companion object {
        /** OpenAI-style JSON mode: the reply is one JSON object. */
        val JSON_OBJECT = OpenRouterResponseFormat("json_object")
    }
}

@Serializable
data class OpenRouterMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OpenRouterResponse(
    val choices: List<OpenRouterChoice> = emptyList(),
    /**
     * Token counts and the charge for the call, which OpenRouter includes on every response without
     * being asked. Left as raw JSON and read by [openRouterUsageOf], which says why.
     */
    val usage: JsonElement? = null,
)

@Serializable
data class OpenRouterChoice(
    val message: OpenRouterReply? = null,
    /** `stop` for a finished reply; [FINISH_LENGTH] when `max_tokens` cut it short. */
    @SerialName("finish_reason") val finishReason: String? = null,
) {
    companion object {
        const val FINISH_LENGTH = "length"
    }
}

/**
 * The assistant message of a response. Separate from [OpenRouterMessage] because a reply's content
 * can be null — a model that spent its whole allowance reasoning returns no text at all — where a
 * message being sent always has some.
 */
@Serializable
data class OpenRouterReply(val content: String? = null)
