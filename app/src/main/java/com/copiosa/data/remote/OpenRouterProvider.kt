package com.copiosa.data.remote

import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.usage.LlmUsageRepository
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import retrofit2.HttpException
import javax.inject.Inject

class OpenRouterProvider @Inject constructor(
    private val api: OpenRouterApi,
    private val settings: SettingsRepository,
    private val usage: LlmUsageRepository,
) : LlmProvider {

    override val id = ProviderId.OPENROUTER

    override suspend fun chat(req: LlmRequest): LlmResponse {
        val key = settings.getApiKey(ProviderId.OPENROUTER) ?: throw LlmException(LlmError.NoKey)
        return try {
            val body = requestFor(req)
            val response = withHeadroom(req.maxTokens) { cap ->
                api.chat(authorization = "Bearer $key", body = body.copy(maxTokens = cap))
            }
            // Before the reply is looked at: a refusal or an empty choice is billed all the same.
            usage.record(id, req, openRouterUsageOf(response.usage))
            val choice = response.choices.firstOrNull()
            llmResponseOf(
                text = choice?.message?.content.orEmpty(),
                truncated = choice?.finishReason == OpenRouterChoice.FINISH_LENGTH,
            )
        } catch (t: Throwable) {
            throw t.toLlmException()
        }
    }

    companion object {
        /** The request body for [req], with the ceiling as the caller gave it — see [withHeadroom]. */
        internal fun requestFor(req: LlmRequest) = OpenRouterRequest(
            model = req.model,
            // OpenAI-style: the system prompt rides as a leading `system` message, then the turns.
            messages = buildList {
                if (req.systemPrompt.isNotBlank()) {
                    add(OpenRouterMessage(role = "system", content = req.systemPrompt))
                }
                req.messages.forEach {
                    add(OpenRouterMessage(role = it.role.toOpenRouterRole(), content = it.content))
                }
            },
            maxTokens = req.maxTokens,
            // json_object can only promise an object: asked of a prompt that wants a bare
            // array, it gets the array wrapped in one, or nothing useful at all.
            responseFormat = OpenRouterResponseFormat.JSON_OBJECT.takeIf { req.json == JsonShape.OBJECT },
        )

        /**
         * Runs [send] with the output ceiling for a request that wants [maxTokens] of reply.
         *
         * `max_tokens` covers reasoning and visible text together, and a model id says nothing about
         * whether the model behind it reasons, so every request gets [THINKING_HEADROOM] on top —
         * sent bare, a 512-token ceiling can be spent entirely on reasoning and come back as a billed
         * reply with no text. Turning reasoning off instead is not an option across a roster: a model
         * whose reasoning is mandatory rejects the request, and `reasoning.exclude` only hides tokens
         * that are still generated, billed and counted.
         *
         * The lifted ceiling can exceed what a small model may be asked for, which is refused as a
         * bad request before anything is generated. That one case is sent again as it always was,
         * so a model that worked without headroom still does.
         */
        internal suspend fun <T> withHeadroom(maxTokens: Int, send: suspend (cap: Int) -> T): T =
            try {
                send(maxTokens + THINKING_HEADROOM)
            } catch (e: HttpException) {
                if (e.code() != HTTP_BAD_REQUEST) throw e
                send(maxTokens)
            }

        private const val HTTP_BAD_REQUEST = 400

        private fun Role.toOpenRouterRole(): String = when (this) {
            Role.ASSISTANT -> "assistant"
            Role.SYSTEM -> "system"
            Role.USER -> "user"
        }
    }
}
