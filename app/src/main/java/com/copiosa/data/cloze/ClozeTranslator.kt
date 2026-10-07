package com.copiosa.data.cloze

import com.copiosa.data.remote.JsonShape
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.remote.tryLlm
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.Role
import com.copiosa.domain.prompt.ClozeGenerationParser
import com.copiosa.domain.prompt.ClozePrompts
import com.copiosa.domain.prompt.ClozeTranslationRequestItem
import com.copiosa.domain.usage.LlmFeature
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Back-fills the translations the local miners could not supply.
 *
 * Every local source banks `translation = null` on purpose: a card's back glosses the *word*, not the
 * sentence around it, and the dictionary pack is monolingual, so inventing one from what is to hand
 * would put a wrong translation under a right answer. That leaves the honest gap this closes — the
 * translation is what tells a learner whether they picked the right word for the right reason, so a
 * bank without one is a bank of unexplained answers.
 *
 * **One LLM call per [run]**, batched, matching the maintenance budget. The blank is filled in before
 * sending, so the model translates prose rather than `{{c1::…}}` markup.
 */
@Singleton
class ClozeTranslator @Inject constructor(
    private val cloze: ClozeRepository,
    private val providers: ProviderRegistry,
    private val settingsRepo: SettingsRepository,
) {

    /** Translates one batch. Returns how many were stored (0 when there was nothing to do). */
    suspend fun run(lang: String): Int {
        val pending = cloze.pendingTranslation(lang, BATCH)
        if (pending.isEmpty()) return 0

        val settings = settingsRepo.settings.first()
        // The learner's own language is what a translation is *for*, so it comes from the profile
        // rather than the sentence: a bank row records the language it teaches, not the one it explains in.
        val response = tryLlm {
            providers.active().chat(
                LlmRequest(
                    systemPrompt = ClozePrompts.translation(lang, settings.nativeLanguage),
                    messages = listOf(
                        LlmMessage(
                            Role.USER,
                            ClozeGenerationParser.encodeTranslationRequest(
                                pending.map { (id, sentence) ->
                                    ClozeTranslationRequestItem(id = id, sentence = sentence)
                                },
                            ),
                        ),
                    ),
                    model = settings.backgroundModel,
                    feature = LlmFeature.CLOZE_BANK,
                    maxTokens = (pending.size * TOKENS_PER_ITEM).coerceIn(512, 4096),
                    json = JsonShape.ARRAY,
                ),
            )
        } ?: return 0

        val wanted = pending.map { it.first }.toSet()
        var stored = 0
        for (dto in ClozeGenerationParser.parseTranslations(response.text)) {
            // An id that wasn't asked about is the model answering a different question; a row left
            // out simply stays in the queue for the next pass rather than being marked done.
            val id = dto.id?.takeIf { it in wanted } ?: continue
            val text = dto.translation.trim()
            if (text.isEmpty()) continue
            cloze.applyTranslation(id, text)
            stored++
        }
        return stored
    }

    private companion object {
        /** Sentences per request — the batch size the bank's other model passes use. */
        const val BATCH = 10
        const val TOKENS_PER_ITEM = 80
    }
}
