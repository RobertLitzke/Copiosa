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
import com.copiosa.domain.prompt.ClozeVerdictRequestItem
import com.copiosa.domain.usage.LlmFeature
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last line of defence against an item with more than one right answer.
 *
 * The local checks — content words only, morphologically matched distractors, synonyms and
 * gloss-sharing words refused — catch most of it without a model. What they cannot catch is
 * semantics: that in *una casa ___* both *grande* and *pequeña* are perfectly good answers. This
 * asks a model exactly that question, and only about items the learner has already complained about,
 * so the spend is proportional to real annoyance rather than speculative.
 *
 * **One LLM call per [run]**, batched, matching the maintenance budget.
 */
@Singleton
class ClozeAdjudicator @Inject constructor(
    private val cloze: ClozeRepository,
    private val providers: ProviderRegistry,
    private val settingsRepo: SettingsRepository,
) {

    /** Adjudicates one batch. Returns how many items were judged (0 when there was nothing to do). */
    suspend fun run(lang: String): Int {
        val items = cloze.pendingAdjudication(lang, BATCH)
        if (items.isEmpty()) return 0

        val settings = settingsRepo.settings.first()
        val payload = items.map {
            ClozeVerdictRequestItem(
                id = it.sentenceId,
                sentence = it.prompt,
                answer = it.answer,
                options = it.options,
            )
        }
        val response = tryLlm {
            providers.active().chat(
                LlmRequest(
                    systemPrompt = ClozePrompts.verdict(settings.targetLanguage),
                    messages = listOf(LlmMessage(Role.USER, ClozeGenerationParser.encodeVerdictRequest(payload))),
                    model = settings.backgroundModel,
                    feature = LlmFeature.CLOZE_BANK,
                    maxTokens = (items.size * TOKENS_PER_ITEM).coerceIn(512, 4096),
                    json = JsonShape.ARRAY,
                ),
            )
        } ?: return 0

        val verdicts = ClozeGenerationParser.parseVerdicts(response.text).associateBy { it.id }
        var judged = 0
        for (item in items) {
            // No verdict for an item means the model dropped it; leave the flag on so the next pass
            // picks it up rather than quietly declaring it sound.
            val verdict = verdicts[item.sentenceId] ?: continue
            cloze.applyVerdict(
                item = item,
                // Only options actually on the item count — a model naming a word that wasn't shown
                // is answering a different question.
                alsoFit = verdict.alsoFit.filter { it in item.options && it != item.answer },
                underdetermined = verdict.underdetermined,
            )
            judged++
        }
        return judged
    }

    private companion object {
        const val BATCH = 10
        const val TOKENS_PER_ITEM = 80
    }
}
