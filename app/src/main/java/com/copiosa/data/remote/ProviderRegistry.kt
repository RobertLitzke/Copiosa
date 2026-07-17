package com.copiosa.data.remote

import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.ProviderId
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves the active [LlmProvider] from current settings. */
@Singleton
class ProviderRegistry @Inject constructor(
    private val gemini: GeminiProvider,
    private val openRouter: OpenRouterProvider,
    private val settings: SettingsRepository,
) {
    fun forId(id: ProviderId): LlmProvider = when (id) {
        ProviderId.GEMINI -> gemini
        ProviderId.OPENROUTER -> openRouter
    }

    suspend fun active(): LlmProvider = forId(settings.settings.first().provider)
}
