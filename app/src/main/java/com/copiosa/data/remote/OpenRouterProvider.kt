package com.copiosa.data.remote

import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import javax.inject.Inject

class OpenRouterProvider @Inject constructor(
    private val api: OpenRouterApi,
    private val settings: SettingsRepository,
) : LlmProvider {

    override val id = ProviderId.OPENROUTER

    override suspend fun chat(req: LlmRequest): LlmResponse {
        val key = settings.getApiKey(ProviderId.OPENROUTER) ?: throw LlmException(LlmError.NoKey)
        return try {
            // OpenAI-style: the system prompt rides as a leading `system` message, then the turns.
            val messages = buildList {
                if (req.systemPrompt.isNotBlank()) {
                    add(OpenRouterMessage(role = "system", content = req.systemPrompt))
                }
                req.messages.forEach {
                    add(OpenRouterMessage(role = it.role.toOpenRouterRole(), content = it.content))
                }
            }
            val response = api.chat(
                authorization = "Bearer $key",
                body = OpenRouterRequest(
                    model = req.model,
                    messages = messages,
                    maxTokens = req.maxTokens,
                ),
            )
            val text = response.choices.firstOrNull()?.message?.content.orEmpty()
            LlmResponse(text)
        } catch (t: Throwable) {
            throw t.toLlmException()
        }
    }

    private fun Role.toOpenRouterRole(): String = when (this) {
        Role.ASSISTANT -> "assistant"
        Role.SYSTEM -> "system"
        Role.USER -> "user"
    }
}
