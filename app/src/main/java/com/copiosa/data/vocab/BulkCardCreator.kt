package com.copiosa.data.vocab

import com.copiosa.data.remote.JsonShape
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.translate.LocalTranslateResult
import com.copiosa.data.translate.OnDeviceTranslator
import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.Role
import com.copiosa.domain.prompt.BulkCardDto
import com.copiosa.domain.prompt.BulkCardRequestItem
import com.copiosa.domain.prompt.BulkCards
import com.copiosa.domain.prompt.PromptBuilder
import com.copiosa.domain.usage.LlmFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One card the bulk add has drafted, shown to the learner to confirm before anything is saved. */
data class CardDraft(
    /** What the learner typed for this card. */
    val source: String,
    /** The target-language side — the card's front. */
    val term: String,
    /** The native-language side — the card's back. */
    val translation: String,
    val pos: String? = null,
    val gender: String? = null,
    val example: String? = null,
    /** The deck (or an earlier draft in this batch) already teaches [term]. */
    val duplicate: Boolean = false,
)

/** What one drafting run produced. */
data class BulkDraftResult(
    val drafts: List<CardDraft>,
    /** Typed items nothing could turn into a card, in the order typed. */
    val untranslated: List<String>,
    /** How many of [drafts] came from the on-device translator and so carry no grammar or example. */
    val onDevice: Int,
    /** Why the model wasn't used for some or all of the run, when that was a failure rather than a missing key. */
    val llmError: LlmError?,
)

/**
 * Turns a typed list of words into cards: [draft] proposes, the learner edits and ticks, [add] saves.
 * Nothing is written until [add], so a bad batch costs a model call and no cleanup.
 *
 * The model is the intended path — it picks the word a speaker from the learner's chosen country
 * would use and supplies part of speech, gender and an example in the same call. With no API key, or
 * when the call fails, the on-device translator stands in so the feature still works offline; those
 * drafts are a bare word pair and are counted in [BulkDraftResult.onDevice] so the screen can say so.
 */
@Singleton
class BulkCardCreator @Inject constructor(
    private val flashcards: FlashcardRepository,
    private val settingsRepo: SettingsRepository,
    private val providers: ProviderRegistry,
    private val prompts: PromptBuilder,
    private val translator: OnDeviceTranslator,
) {

    /**
     * Drafts a card for each of [inputs], reporting `(done, total)` as it goes. One model call per
     * [BATCH] items; never throws for a failed call — see [BulkDraftResult.llmError].
     */
    suspend fun draft(
        inputs: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BulkDraftResult = withContext(Dispatchers.Default) {
        val settings = settingsRepo.settings.first()
        // Seeded with the deck, then grown with each draft, so a word typed twice in different
        // wording ("couch, sofa") is flagged on its second appearance too.
        val owned = flashcards.observeAll().first()
            .filter { it.cardKind.isLexicalVocab }
            .mapTo(HashSet()) { normalizeWord(it.targetTerm) }

        val drafts = ArrayList<CardDraft>(inputs.size)
        val untranslated = ArrayList<String>()
        var onDevice = 0
        var llmError: LlmError? = null
        var useLlm = settingsRepo.hasApiKey(settings.provider)
        var useDevice = true

        val items = inputs.mapIndexed { index, text -> BulkCardRequestItem(index, text) }
        var done = 0
        onProgress(0, items.size)
        for (chunk in items.chunked(BATCH)) {
            val fromModel: List<BulkCardDto?> = if (useLlm) {
                try {
                    BulkCards.align(chunk, requestDrafts(chunk, settings))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nearly always the network or the key, which the next chunk would only repeat.
                    llmError = (e as? LlmException)?.error ?: LlmError.Unknown(e.message ?: "error")
                    useLlm = false
                    chunk.map { null }
                }
            } else {
                chunk.map { null }
            }
            for ((item, dto) in chunk.zip(fromModel)) {
                var draft = dto?.let { draftFrom(item.text, it) }
                if (draft == null && useDevice) {
                    when (val result = translator.translate(item.text, settings.nativeLanguage, settings.targetLanguage)) {
                        is LocalTranslateResult.Success ->
                            // Handed straight back means it had no translation; an identical pair
                            // is not a card.
                            if (!result.text.trim().equals(item.text, ignoreCase = true)) {
                                draft = CardDraft(source = item.text, term = result.text.trim(), translation = item.text)
                                onDevice++
                            }
                        // A missing model fails the same way for every item, so stop asking.
                        LocalTranslateResult.Unsupported, LocalTranslateResult.Unavailable -> useDevice = false
                    }
                }
                if (draft == null) {
                    untranslated += item.text
                } else {
                    drafts += draft.copy(duplicate = !owned.add(normalizeWord(draft.term)))
                }
                onProgress(++done, items.size)
            }
        }
        BulkDraftResult(drafts, untranslated, onDevice, llmError)
    }

    /**
     * Saves [drafts] as cards, due now, and returns how many were written. Callers pass only the
     * ones the learner left ticked.
     */
    suspend fun add(drafts: List<CardDraft>): Int {
        val usable = drafts.filter { it.term.isNotBlank() && it.translation.isNotBlank() }
        // A handful share one enrichment call, the same as tapping that many words in a reader. A
        // long list is left to the background backstop, which works it off a batch at a time rather
        // than spending a burst of calls the moment the sheet closes.
        val enrich = usable.size <= ENRICH_NOW_MAX
        for (draft in usable) {
            flashcards.add(
                front = draft.term,
                back = draft.translation,
                contextSentence = draft.example,
                sourceConversationId = null,
                partOfSpeech = draft.pos,
                cardKind = kindFor(draft.term),
                gender = draft.gender,
                autoSource = SOURCE,
                enrich = enrich,
            )
        }
        return usable.size
    }

    private suspend fun requestDrafts(chunk: List<BulkCardRequestItem>, settings: AppSettings): List<BulkCardDto> {
        val response = providers.active().chat(
            LlmRequest(
                systemPrompt = prompts.bulkCardSystemPrompt(
                    country = settings.defaultCountry,
                    targetLang = settings.targetLanguage,
                    nativeLang = settings.nativeLanguage,
                    level = settings.defaultLevel,
                ),
                messages = listOf(LlmMessage(Role.USER, BulkCards.encodeRequest(chunk))),
                model = settings.activeModel,
                feature = LlmFeature.BULK_ADD,
                maxTokens = (chunk.size * 160).coerceIn(512, 4096),
                json = JsonShape.ARRAY,
            ),
        )
        return BulkCards.parse(response.text)
    }

    companion object {
        /** The [com.copiosa.data.local.FlashcardEntity.autoSource] value this generator stamps. */
        const val SOURCE = "bulk"

        /** Items per model call: small enough that a reply with examples fits the token ceiling. */
        const val BATCH = 25

        /** The most cards one [add] will queue for immediate enrichment — one enrichment batch. */
        const val ENRICH_NOW_MAX = 10

        private val POS = setOf("noun", "verb", "adjective", "adverb", "phrase", "expression", "other")
        private val GENDERS = setOf("M", "F", "MF", "N", "MPL", "FPL")

        /**
         * A model reply as a draft, or null when it has no term. Part of speech and gender are kept
         * only when they are values the card editor knows — a model that writes "null" as a string,
         * or invents "adj.", would otherwise store it.
         */
        fun draftFrom(source: String, dto: BulkCardDto): CardDraft? {
            val term = dto.term.trim().ifEmpty { return null }
            val pos = dto.pos?.trim()?.lowercase()?.takeIf { it in POS }
            return CardDraft(
                source = source,
                term = term,
                translation = dto.translation?.trim()?.ifEmpty { null } ?: source,
                pos = pos,
                // Only a noun carries a gender, as in the editor.
                gender = dto.gender?.trim()?.uppercase()?.takeIf { it in GENDERS && pos == "noun" },
                example = dto.example?.trim()?.ifEmpty { null },
            )
        }

        /** A multi-word term is a phrase card — the same rule saving a selection in a reader uses. */
        fun kindFor(term: String): CardKind =
            if (term.trim().any { it.isWhitespace() }) CardKind.PHRASE else CardKind.VOCAB
    }
}
