package com.copiosa.data.remote

import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role

/** A single turn sent to the model. */
data class LlmMessage(val role: Role, val content: String)

/** A provider-agnostic chat request. */
data class LlmRequest(
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val model: String,
    val maxTokens: Int = 2048,
)

/** A provider-agnostic chat response (non-streaming in this build). */
data class LlmResponse(val text: String)

/** Classified, user-presentable failure modes. */
sealed class LlmError {
    data object NoKey : LlmError()
    data object Auth : LlmError()
    data object Network : LlmError()
    data object RateLimit : LlmError()
    data object ServiceBusy : LlmError()

    /** A pasted URL couldn't be fetched/read into article text (paywall, JS-only page, 404…). */
    data object Extraction : LlmError()
    data class Unknown(val message: String) : LlmError()
}

class LlmException(val error: LlmError) : Exception()

/**
 * One interface, multiple backends. The implementation reads the user's key from settings;
 * [ProviderRegistry] selects which implementation is active. Designed to add a streaming
 * `chatStream(): Flow<String>` later without touching callers of [chat].
 */
interface LlmProvider {
    val id: ProviderId
    suspend fun chat(req: LlmRequest): LlmResponse
}
