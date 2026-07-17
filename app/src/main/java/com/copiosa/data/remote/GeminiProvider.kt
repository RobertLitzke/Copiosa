package com.copiosa.data.remote

import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import javax.inject.Inject

class GeminiProvider @Inject constructor(
    private val api: GeminiApi,
    private val settings: SettingsRepository,
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
                    generationConfig = thinkingConfigFor(req.model)
                        ?.let { GeminiGenerationConfig(thinkingConfig = it) },
                ),
            )
            val text = response.candidates.firstOrNull()
                ?.content?.parts.orEmpty()
                .joinToString("\n") { it.text }
            LlmResponse(text)
        } catch (t: Throwable) {
            throw t.toLlmException()
        }
    }

    private fun Role.toGeminiRole(): String = when (this) {
        Role.ASSISTANT -> "model"
        else -> "user"
    }

    /**
     * Disables thinking on the "flash" thinking models (2.5/3.x), which otherwise think by default.
     * Returns null for 2.0-flash (no thinking) and the "pro" models (which can't be set to 0), so
     * those keep their provider defaults rather than getting a budget that would be rejected.
     */
    private fun thinkingConfigFor(model: String): GeminiThinkingConfig? {
        val m = model.lowercase()
        val isThinkingFlash = "flash" in m && ("2.5" in m || "3." in m || "3-" in m)
        return if (isThinkingFlash) GeminiThinkingConfig(thinkingBudget = 0) else null
    }
}
