package com.copiosa.data.remote

import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.usage.LlmUsageRepository
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import javax.inject.Inject

class GeminiProvider @Inject constructor(
    private val api: GeminiApi,
    private val settings: SettingsRepository,
    private val usage: LlmUsageRepository,
) : LlmProvider {

    override val id = ProviderId.GEMINI

    override suspend fun chat(req: LlmRequest): LlmResponse {
        val key = settings.getApiKey(ProviderId.GEMINI) ?: throw LlmException(LlmError.NoKey)
        return try {
            val response = api.generate(
                model = req.model,
                apiKey = key,
                body = GeminiRequest(
                    systemInstruction = req.systemPrompt
                        .takeIf { it.isNotBlank() }
                        ?.let { GeminiContent(parts = listOf(GeminiPart(it))) },
                    contents = req.messages.map {
                        GeminiContent(
                            role = it.role.toGeminiRole(),
                            parts = listOf(GeminiPart(it.content)),
                        )
                    },
                    generationConfig = generationConfigFor(req),
                ),
            )
            // Before the reply is looked at: a refusal or an empty candidate is billed all the same.
            usage.record(id, req, geminiUsageOf(response.usageMetadata))
            val candidate = response.candidates.firstOrNull()
            val text = candidate?.content?.parts.orEmpty().joinToString("\n") { it.text }
            llmResponseOf(text, truncated = candidate?.finishReason == GeminiCandidate.FINISH_MAX_TOKENS)
        } catch (t: Throwable) {
            throw t.toLlmException()
        }
    }

    private fun Role.toGeminiRole(): String = when (this) {
        Role.ASSISTANT -> "model"
        else -> "user"
    }

    companion object {
        internal fun generationConfigFor(req: LlmRequest) = GeminiGenerationConfig(
            thinkingConfig = thinkingConfigFor(req.model),
            maxOutputTokens = outputCapFor(req.model, req.maxTokens),
            // Gemini's JSON mode takes either shape, so an array request gets it too.
            responseMimeType = if (req.json != null) "application/json" else null,
        )

        /**
         * Disables thinking on the "flash" thinking models (2.5/3.x), which otherwise think by default.
         * Returns null for 2.0-flash (no thinking) and the "pro" models (which can't be set to 0), so
         * those keep their provider defaults rather than getting a budget that would be rejected.
         */
        internal fun thinkingConfigFor(model: String): GeminiThinkingConfig? {
            val m = model.lowercase()
            val isThinkingFlash = "flash" in m && ("2.5" in m || "3." in m || "3-" in m)
            return if (isThinkingFlash) GeminiThinkingConfig(thinkingBudget = 0) else null
        }

        /**
         * The output ceiling to send for a request that wants [maxTokens] of reply. Exact where
         * thinking is known to be off — 2.0, which has none, and 2.5 flash, which honours a zero
         * budget. Anything else (the pro models, and 3.x, where a zero budget is a request rather
         * than a guarantee) gets [THINKING_HEADROOM] on top.
         */
        internal fun outputCapFor(model: String, maxTokens: Int): Int {
            val m = model.lowercase()
            val neverThinks = "2.0" in m || ("2.5" in m && "flash" in m)
            return if (neverThinks) maxTokens else maxTokens + THINKING_HEADROOM
        }
    }
}
