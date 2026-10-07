package com.copiosa.data.cloze.sources

import com.copiosa.data.cloze.ClozeSource
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.remote.JsonShape
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.remote.tryLlm
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.domain.model.Role
import com.copiosa.domain.prompt.ClozeGenerationParser
import com.copiosa.domain.prompt.ClozePrompts
import com.copiosa.domain.prompt.ClozeRequestItem
import com.copiosa.domain.usage.LlmFeature
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes sentences for words nothing local could serve.
 *
 * The last resort by design. Every other source draws on material the learner has actually met,
 * which is the property that makes this mode better than a generic sentence trainer; a generated
 * sentence has none of that, so it is only worth spending a call on a word that would otherwise have
 * no cloze at all.
 *
 * **One LLM call per [mine], never more** — the whole batch of words goes in a single request, in the
 * id-echo style the vocabulary enrichment already uses. That is what keeps it inside the
 * architecture's one-call-per-maintenance-run budget.
 */
@Singleton
class LlmClozeSource @Inject constructor(
    private val flashcardDao: FlashcardDao,
    private val providers: ProviderRegistry,
    private val settingsRepo: SettingsRepository,
) : ClozeSource {

    override val id = "llm"

    override val needsLlm = true

    override suspend fun mine(ctx: ClozeMineContext, limit: Int): List<ClozeCandidate> {
        val wanted = flashcardDao.allActive(ctx.lang)
            .filter { card ->
                val lemma = normalizeWord(card.targetTerm)
                lemma.isNotEmpty() &&
                    // Nothing in the bank for this word at all, not merely room for more: a word with
                    // a real sentence from the learner's own reading doesn't need an invented one.
                    (ctx.banked[lemma] ?: 0) == 0 &&
                    ctx.hasRoomFor(lemma)
            }
            .take(minOf(limit, BATCH))
        if (wanted.isEmpty()) return emptyList()

        val settings = settingsRepo.settings.first()
        val items = wanted.map {
            ClozeRequestItem(
                id = it.id,
                term = it.targetTerm,
                pos = it.partOfSpeech?.takeIf { pos -> pos != "unknown" },
                meaning = it.back.ifBlank { null },
            )
        }
        val response = tryLlm {
            providers.active().chat(
                LlmRequest(
                    systemPrompt = ClozePrompts.generation(settings.targetLanguage, settings.nativeLanguage),
                    messages = listOf(LlmMessage(Role.USER, ClozeGenerationParser.encodeGenerationRequest(items))),
                    model = settings.backgroundModel,
                    feature = LlmFeature.CLOZE_BANK,
                    maxTokens = (wanted.size * TOKENS_PER_ITEM).coerceIn(512, 4096),
                    json = JsonShape.ARRAY,
                ),
            )
        } ?: return emptyList()

        val byId = wanted.associateBy { it.id }
        return ClozeGenerationParser.parseGeneration(response.text).mapNotNull { dto ->
            val card = dto.id?.let(byId::get) ?: return@mapNotNull null
            val sentence = dto.sentence.trim()
            if (sentence.isEmpty()) return@mapNotNull null
            // The model is asked to echo the exact surface form it used, but it can get that wrong;
            // locating it in the sentence is the only thing that makes the blank correct.
            val form = dto.form?.trim()?.takeIf { it.isNotEmpty() } ?: card.targetTerm
            val start = sentence.indexOf(form, ignoreCase = true)
            if (start < 0) return@mapNotNull null

            ClozeCandidate(
                sentence = sentence,
                answer = sentence.substring(start, start + form.length),
                answerStart = start,
                lemma = normalizeWord(card.targetTerm),
                cardId = card.id,
                translation = dto.translation?.trim()?.ifBlank { null },
                source = id,
            )
        }
    }

    private companion object {
        /** Words per request — the enrichment pass's batch size, for the same reasons. */
        const val BATCH = 10
        const val TOKENS_PER_ITEM = 120
    }
}
