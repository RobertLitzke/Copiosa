package com.copiosa.data.remote

import com.copiosa.domain.usage.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.io.IOException

/**
 * Room left for a model that may think before it answers. Thinking is billed and counted as output
 * by both providers, so on those models a bare ceiling of a few hundred tokens can be spent before
 * the first visible one — the cap is a guard against a runaway reply, not a thinking budget.
 */
internal const val THINKING_HEADROOM = 8192

/**
 * The reply a provider hands back. A refusal or an empty candidate arrives as a 200 with no text
 * rather than as an error status, so it is raised here, once, for every caller — left as an empty
 * string it reads as "the model answered with nothing" and gets stored, parsed and retried as if it
 * were an answer. A reply cut off by the output ceiling before any text is the same thing to a caller.
 */
internal fun llmResponseOf(text: String, truncated: Boolean): LlmResponse {
    if (text.isBlank()) throw LlmException(LlmError.Refused)
    return LlmResponse(text, truncated)
}

/*
 * Usage blocks are read as raw JSON and picked apart by hand rather than declared as serializable
 * classes. A typed field that met a shape it didn't expect — a count sent as a float, a cost sent
 * as a string, a null where a number was promised — would fail the decode of the whole response,
 * and the learner would lose a reply they had already paid for to a bookkeeping field. Read this
 * way, anything unexpected is simply not counted.
 */

/**
 * Gemini's `usageMetadata`. `promptTokenCount` already includes the cached part; thinking is
 * reported beside `candidatesTokenCount`, not inside it, and is billed as output. No cost: Gemini
 * reports tokens only.
 */
internal fun geminiUsageOf(usageMetadata: JsonElement?): TokenUsage? {
    val meta = usageMetadata as? JsonObject ?: return null
    val prompt = meta.count("promptTokenCount")
    val output = (meta.count("candidatesTokenCount") ?: 0) + (meta.count("thoughtsTokenCount") ?: 0)
    if (prompt == null && output == 0L) return null
    return TokenUsage(
        promptTokens = prompt ?: 0,
        cachedTokens = meta.count("cachedContentTokenCount") ?: 0,
        outputTokens = output,
    )
}

/**
 * OpenRouter's `usage`. `prompt_tokens` includes the cached part and `completion_tokens` includes
 * reasoning. `cost` is what OpenRouter charged, in credits, which are dollars; with a
 * bring-your-own-key provider that is only its fee, and the provider's own charge arrives beside it
 * as `cost_details.upstream_inference_cost` (zero or absent otherwise) — the two together are what
 * the call cost. A response with no `cost` at all leaves the cost unknown rather than free.
 */
internal fun openRouterUsageOf(usage: JsonElement?): TokenUsage? {
    val u = usage as? JsonObject ?: return null
    val prompt = u.count("prompt_tokens")
    val output = u.count("completion_tokens")
    if (prompt == null && output == null) return null
    val upstream = (u["cost_details"] as? JsonObject)?.number("upstream_inference_cost") ?: 0.0
    return TokenUsage(
        promptTokens = prompt ?: 0,
        cachedTokens = (u["prompt_tokens_details"] as? JsonObject)?.count("cached_tokens") ?: 0,
        outputTokens = output ?: 0,
        costUsd = u.number("cost")?.let { it + upstream },
    )
}

/** A finite, non-negative number under [key], however it was written; null for anything else. */
private fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

private fun JsonObject.count(key: String): Long? = number(key)?.toLong()

/** Maps SDK/HTTP failures onto the classified [LlmError] surface. */
internal fun Throwable.toLlmException(): LlmException = when (this) {
    is LlmException -> this
    // A cancelled call is the caller stopping, not a failure: it passes through unchanged.
    is CancellationException -> throw this
    is HttpException -> when (code()) {
        401, 403 -> LlmException(LlmError.Auth)
        429 -> LlmException(LlmError.RateLimit)
        500, 502, 503, 504 -> LlmException(LlmError.ServiceBusy)
        else -> LlmException(LlmError.Unknown("HTTP ${code()}"))
    }
    is IOException -> LlmException(LlmError.Network)
    else -> LlmException(LlmError.Unknown(message ?: this::class.simpleName ?: "error"))
}
