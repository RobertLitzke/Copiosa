package com.copiosa.data.remote

import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import com.copiosa.domain.usage.LlmFeature
import kotlinx.coroutines.CancellationException

/** A single turn sent to the model. */
data class LlmMessage(val role: Role, val content: String)

/**
 * What a reply that is nothing but JSON has at its top level. A request names one to ask the
 * provider for its JSON output mode; the distinction matters because the OpenAI-style mode can only
 * promise an object, so an [ARRAY] request gets the mode from Gemini and plain prompting elsewhere.
 */
enum class JsonShape { OBJECT, ARRAY }

/** A provider-agnostic chat request. */
data class LlmRequest(
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val model: String,
    /**
     * What this call is for, which is the line of the usage meter it is counted under. Required, so
     * a new call site cannot be added without one.
     */
    val feature: LlmFeature,
    /** The book this call translates for, when there is one — lets a book report its own cost. */
    val bookId: Long? = null,
    val maxTokens: Int = 2048,
    /**
     * Set when the whole reply must be JSON of this shape. Leave null for prose, and for prose with
     * a trailing metadata block (chat replies, text translation) — JSON mode would refuse the prose.
     */
    val json: JsonShape? = null,
)

/**
 * A provider-agnostic chat response (non-streaming in this build).
 *
 * [truncated] is true when the model stopped because it ran out of output tokens rather than
 * because it had finished. The text is then a prefix of the answer, and asking again for the same
 * thing would be cut at the same place — a caller that retries on a malformed reply has to tell
 * the two apart, or it pays twice for the same half.
 */
data class LlmResponse(val text: String, val truncated: Boolean = false)

/** Classified, user-presentable failure modes. */
sealed class LlmError {
    data object NoKey : LlmError()
    data object Auth : LlmError()
    data object Network : LlmError()
    data object RateLimit : LlmError()
    data object ServiceBusy : LlmError()

    /**
     * The call succeeded but carried nothing usable back — a safety refusal, or a candidate with no
     * content. Providers signal this with a 200 and an empty body rather than an error status, so it
     * has to be detected at the parse site and raised deliberately; left as a silent empty string it
     * reads as "the model translated this section into nothing".
     */
    data object Refused : LlmError()

    /** A pasted URL couldn't be fetched/read into article text (paywall, JS-only page, 404…). */
    data object Extraction : LlmError()
    data class Unknown(val message: String) : LlmError()
}

class LlmException(val error: LlmError) : Exception()

/**
 * Stable string form of an error, for the few places one is *stored* rather than shown and
 * discarded ([com.copiosa.data.local.BookEntity.lastError]). A background failure has to be able to
 * name itself days later, after the process that saw it is long gone.
 *
 * These tags are persisted data: an existing tag may never be repurposed, and anything unrecognised
 * degrades to [LlmError.Unknown] rather than throwing, so a row written by a newer build stays
 * readable by an older one.
 */
val LlmError.code: String
    get() = when (this) {
        LlmError.NoKey -> "NO_KEY"
        LlmError.Auth -> "AUTH"
        LlmError.Network -> "NETWORK"
        LlmError.RateLimit -> "RATE_LIMIT"
        LlmError.ServiceBusy -> "SERVICE_BUSY"
        LlmError.Refused -> "REFUSED"
        LlmError.Extraction -> "EXTRACTION"
        is LlmError.Unknown -> "UNKNOWN:$message"
    }

/** Inverse of [code]; see there for why the fallback is total. */
fun llmErrorOfCode(code: String): LlmError = when (code) {
    "NO_KEY" -> LlmError.NoKey
    "AUTH" -> LlmError.Auth
    "NETWORK" -> LlmError.Network
    "RATE_LIMIT" -> LlmError.RateLimit
    "SERVICE_BUSY" -> LlmError.ServiceBusy
    "REFUSED" -> LlmError.Refused
    "EXTRACTION" -> LlmError.Extraction
    else -> LlmError.Unknown(code.removePrefix("UNKNOWN:"))
}

/**
 * Runs an LLM call that is allowed to fail quietly: the value on success, null on any failure.
 * [onFailure] sees the exception first, for logging.
 *
 * Cancellation is rethrown. `runCatching` catches it along with everything else, so a call wrapped
 * in it turns "the user left" into "the model failed" — the caller carries on instead of stopping,
 * and a cancelled maintenance task reports a failure it never had. Inline, so [block] may suspend.
 */
inline fun <T> tryLlm(onFailure: (Exception) -> Unit = {}, block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e)
        null
    }

/**
 * One interface, multiple backends. The implementation reads the user's key from settings;
 * [ProviderRegistry] selects which implementation is active. Designed to add a streaming
 * `chatStream(): Flow<String>` later without touching callers of [chat].
 */
interface LlmProvider {
    val id: ProviderId

    /** Throws [LlmException] on failure — [LlmError.Refused] when the reply carries no text. */
    suspend fun chat(req: LlmRequest): LlmResponse
}

/**
 * The reply's text, or empty when the model [LlmError.Refused]. For an optional call whose empty
 * answer means "keep what you have" (translating a title), where a refusal should not sink the
 * work around it; every other failure still throws.
 */
suspend fun LlmProvider.chatOrEmpty(req: LlmRequest): String =
    try {
        chat(req).text
    } catch (e: LlmException) {
        if (e.error != LlmError.Refused) throw e
        ""
    }
