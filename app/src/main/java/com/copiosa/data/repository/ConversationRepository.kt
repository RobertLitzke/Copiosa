package com.copiosa.data.repository

import com.copiosa.data.article.ArticleExtractor
import com.copiosa.data.local.ConversationDao
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.local.ConversationPreview
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.MessageDao
import com.copiosa.data.local.MessageEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.translate.DictionaryResult
import com.copiosa.data.translate.LocalDictionary
import com.copiosa.data.translate.LocalTranslateResult
import com.copiosa.data.translate.OnDeviceTranslator
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import com.copiosa.data.routine.TopicRepository
import com.copiosa.domain.prompt.PromptBuilder
import com.copiosa.domain.prompt.ReplyMetadata
import com.copiosa.domain.prompt.StructuredReplyParser
import com.copiosa.domain.routine.TopicSource
import com.copiosa.domain.srs.ReviewWindows
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val flashcardDao: FlashcardDao,
    private val providers: ProviderRegistry,
    private val prompts: PromptBuilder,
    private val settingsRepo: SettingsRepository,
    private val translator: OnDeviceTranslator,
    private val dictionary: LocalDictionary,
    private val articleExtractor: ArticleExtractor,
    private val topicRepo: TopicRepository,
) {
    /**
     * Chats list: the active profile's real conversations only. Imported texts (and their "Discuss
     * this text" follow-ups) are URL_TRANSLATION and belong to the Texts list, so they're filtered
     * out here. Re-emits when the user switches language profiles.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeConversations(query: String): Flow<List<ConversationEntity>> =
        settingsRepo.activeProfileTag.flatMapLatest { lang ->
            if (query.isBlank()) {
                conversationDao.observeByMode(Mode.CONVERSATION, lang)
            } else {
                conversationDao.searchByMode(Mode.CONVERSATION, query.trim(), lang)
            }
        }

    /** The active profile's imported texts only (URL_TRANSLATION), for the Texts list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTexts(query: String): Flow<List<ConversationEntity>> =
        settingsRepo.activeProfileTag.flatMapLatest { lang ->
            if (query.isBlank()) {
                conversationDao.observeByMode(Mode.URL_TRANSLATION, lang)
            } else {
                conversationDao.searchByMode(Mode.URL_TRANSLATION, query.trim(), lang)
            }
        }

    /** Latest message text per conversation, for the chat-list snippet preview. */
    fun observeLatestPreviews(): Flow<List<ConversationPreview>> = messageDao.observeLatestPreviews()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.observeForConversation(conversationId)

    fun observeConversation(id: Long): Flow<ConversationEntity?> = conversationDao.observe(id)

    suspend fun getConversation(id: Long): ConversationEntity? = conversationDao.getById(id)

    /** The active profile's most recent conversation, used to decide whether to resume on launch. */
    suspend fun mostRecentConversation(): ConversationEntity? =
        conversationDao.getMostRecent(settingsRepo.activeProfileTag.first())

    suspend fun delete(id: Long) = conversationDao.delete(id)

    /** Flags (or unflags) a conversation as a favourite. */
    suspend fun setStarred(id: Long, starred: Boolean) = conversationDao.setStarred(id, starred)

    /** Marks a conversation read (opened) or unread; drives the chat list's read-status coloring. */
    suspend fun setRead(id: Long, read: Boolean) = conversationDao.setRead(id, read)

    /** Persists an imported text's reading position so the reader can resume where it left off. */
    suspend fun setScroll(id: Long, index: Int, offset: Int) = conversationDao.setScroll(id, index, offset)

    /** Marks a text as finished (scrolled to the end); drives the Texts list's read coloring. */
    suspend fun setFinishedReading(id: Long, finished: Boolean) =
        conversationDao.setFinishedReading(id, finished)

    /**
     * Creates a conversation row and stores the learner's opening message (the essay subject).
     * No model call yet — call [generateFirstReply] next so a failed first turn can be rolled back.
     */
    suspend fun createDraftConversation(
        level: Cefr,
        country: String,
        subject: String,
        provider: ProviderId,
        model: String,
        // The list sorts by updatedAt; a batch passes distinct, descending values so its chats keep a
        // stable order (entry order, top-down) instead of reshuffling as each reply lands.
        createdAt: Long = System.currentTimeMillis(),
        // Batch chats start unread so the list flags the ones still waiting to be read; an interactive
        // new chat is created read because the user is already looking at it.
        read: Boolean = true,
    ): Long {
        val settings = settingsRepo.settings.first()
        val conversation = ConversationEntity(
            mode = Mode.CONVERSATION,
            title = subject.take(80),
            subject = subject,
            level = level,
            country = country,
            targetLanguage = settings.targetLanguage,
            nativeLanguage = settings.nativeLanguage,
            provider = provider,
            model = model,
            createdAt = createdAt,
            updatedAt = createdAt,
            read = read,
        )
        val id = conversationDao.insert(conversation)
        messageDao.insert(MessageEntity(conversationId = id, role = Role.USER, content = subject, createdAt = createdAt))
        // The chat's subject is a topic the learner chose to explore — capture it for routine seeding.
        topicRepo.record(
            phrase = subject,
            source = TopicSource.CONVERSATION_SUBJECT,
            targetLanguage = settings.targetLanguage,
            sourceConversationId = id,
            now = createdAt,
        )
        return id
    }

    /**
     * Generates a reply for the dangling user turn at the end of conversation [id] — the opening
     * subject of a fresh chat, or any user message left without a reply (e.g. after a failed/cancelled
     * send). Backs both the first reply and retry.
     *
     * When [rollbackOnError] is true a failure deletes the whole conversation, so an errored
     * interactive first turn never lands in the library. Background/batch generation passes false so a
     * failed chat is kept and can be retried (its dangling user turn drives the "tap to retry" state).
     */
    suspend fun generateFirstReply(
        id: Long,
        rollbackOnError: Boolean = true,
        // A distinct slice of saved words for this chat to weave in; null falls back to a fresh sample.
        // Used by batch generation so sibling chats don't all reuse the same vocabulary.
        vocabOverride: List<Pair<String, String>>? = null,
    ) {
        val conversation = conversationDao.getById(id) ?: return
        val history = messageDao.getForConversation(id).map { LlmMessage(it.role, it.content) }
        // True for a fresh chat's opening reply (no assistant turn yet). Only then do we ask for — and
        // apply — a target-language title; a mid-conversation retry leaves the existing title alone.
        val isOpening = history.none { it.role == Role.ASSISTANT }
        val prepared = systemPromptFor(conversation, includeTitle = isOpening, vocabOverride = vocabOverride)
        val reply = try {
            providers.forId(conversation.provider).chat(
                LlmRequest(
                    systemPrompt = prepared.prompt,
                    messages = history,
                    model = conversation.model,
                ),
            )
        } catch (t: Throwable) {
            // Roll back even when the caller cancelled, so an aborted first turn never lingers.
            if (rollbackOnError) withContext(NonCancellable) { conversationDao.delete(id) }
            throw t
        }
        // Don't bump updatedAt for the opening reply: it keeps a batch's chats in their stable created
        // order (a single new chat is already at the top by its creation time).
        insertReply(conversation, reply.text, bumpUpdatedAt = false, applyTitle = isOpening, injectedVocab = prepared.vocab)
    }

    /**
     * Appends a learner message, calls the model with full history, and stores the reply. An
     * [isExplanation] turn (the "Explain" word-lookup action) is a one-off vocabulary aside, not a
     * conversational turn: it doesn't request next-topic suggestions and its reply doesn't seed the
     * routine topic table.
     */
    suspend fun sendMessage(conversationId: Long, text: String, isExplanation: Boolean = false) {
        val conversation = conversationDao.getById(conversationId) ?: return
        val now = System.currentTimeMillis()
        messageDao.insert(MessageEntity(conversationId = conversationId, role = Role.USER, content = text, createdAt = now))

        // Cap the context sent to the model: past this depth old turns add cost and latency, not
        // coherence, and an unbounded load would spike memory on a very long chat.
        val history = messageDao.getForConversation(conversationId)
            .takeLast(HISTORY_MESSAGE_CAP)
            .map { LlmMessage(it.role, it.content) }
        val prepared = systemPromptFor(conversation, includeTopics = !isExplanation)
        val reply = providers.forId(conversation.provider).chat(
            LlmRequest(
                systemPrompt = prepared.prompt,
                messages = history,
                model = conversation.model,
            ),
        )
        insertReply(conversation, reply.text, recordTopics = !isExplanation, injectedVocab = prepared.vocab)
    }

    /**
     * Stores an assistant reply with a snapshot of the settings used to produce it. [rawText] is the
     * model's full output: the visible body is split from its trailing structured-metadata block,
     * which is stored (as JSON) for next-topic chips and distinctive-word reuse on the next turn.
     */
    private suspend fun insertReply(
        conversation: ConversationEntity,
        rawText: String,
        bumpUpdatedAt: Boolean = true,
        // False for "Explain" turns: their suggested next-topics are suppressed and aren't seeded into
        // the routine topic table.
        recordTopics: Boolean = true,
        // True on a chat's opening reply: adopt the model's target-language title (when it gave one).
        applyTitle: Boolean = false,
        // The saved words we actually injected into this turn's prompt, recorded for the per-reply
        // metadata view. Distinct from the model's self-reported vocabUsed/vocabUnused.
        injectedVocab: List<Pair<String, String>> = emptyList(),
    ) {
        val now = System.currentTimeMillis()
        val (body, parsed) = StructuredReplyParser.parse(rawText)
        // Fold in our ground-truth record of the supplied words (the model never emits this itself).
        val meta = parsed.copy(vocabSupplied = injectedVocab.map { it.first })
        val messageId = messageDao.insert(
            MessageEntity(
                conversationId = conversation.id,
                role = Role.ASSISTANT,
                content = body,
                createdAt = now,
                model = conversation.model,
                level = conversation.level,
                country = conversation.country,
                metadata = StructuredReplyParser.encode(meta),
            ),
        )
        // The model's suggested next-topics are good routine seeds, but cryptic on their own — store
        // each with the gloss it shipped so it makes sense outside this chat.
        if (recordTopics) {
            for (topic in meta.topics) {
                topicRepo.record(
                    phrase = topic.phrase,
                    source = TopicSource.CHAT_SUGGESTION,
                    targetLanguage = conversation.targetLanguage,
                    description = topic.gloss,
                    sourceConversationId = conversation.id,
                    sourceMessageId = messageId,
                    now = now,
                )
            }
        }
        // Replace the native-language subject with the model's target-language title for the list, if it
        // produced one. Keep updatedAt untouched unless this is a regular turn (preserves batch order).
        val newTitle = if (applyTitle) meta.title?.trim()?.takeIf { it.isNotBlank() }?.take(80) else null
        if (newTitle != null || bumpUpdatedAt) {
            conversationDao.update(
                conversation.copy(
                    title = newTitle ?: conversation.title,
                    updatedAt = if (bumpUpdatedAt) now else conversation.updatedAt,
                ),
            )
        }
    }

    suspend fun setConversationLevel(id: Long, level: Cefr) {
        val c = conversationDao.getById(id) ?: return
        conversationDao.update(c.copy(level = level))
    }

    suspend fun setConversationModel(id: Long, model: String) {
        val c = conversationDao.getById(id) ?: return
        conversationDao.update(c.copy(model = model))
    }

    /** Switches the provider of a saved conversation, snapshotting that provider's active model. */
    suspend fun setConversationProvider(id: Long, provider: ProviderId) {
        val c = conversationDao.getById(id) ?: return
        val settings = settingsRepo.settings.first()
        conversationDao.update(c.copy(provider = provider, model = settings.modelFor(provider)))
    }

    /**
     * On-device translation of a selected phrase back to the learner's native language. No LLM or
     * per-lookup network call — the language model is downloaded once, then runs fully offline.
     */
    suspend fun localTranslate(text: String): LocalTranslateResult {
        val settings = settingsRepo.settings.first()
        val result = translator.translate(text, settings.targetLanguage, settings.nativeLanguage)
        // Looking something up signals interest. Capture multi-word / substantial selections as topic
        // seeds (with their translation as context); skip short function words, which make poor topics.
        if (result is LocalTranslateResult.Success && isTopicWorthy(text)) {
            topicRepo.record(
                phrase = text.trim(),
                source = TopicSource.USER_TRANSLATION,
                targetLanguage = settings.targetLanguage,
                description = result.text,
            )
        }
        return result
    }

    /** A selection worth keeping as a topic seed: a phrase, or a single content word of some length. */
    private fun isTopicWorthy(text: String): Boolean {
        val clean = text.trim()
        if (!clean.any { it.isLetter() }) return false
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size >= 2 || clean.length >= 6
    }

    /** Whether offline definitions are wired up at all (a download URL has been configured). */
    fun dictionaryConfigured(): Boolean = dictionary.isConfigured()

    /** Whether the target-language dictionary pack is already on the device. */
    suspend fun dictionaryDownloaded(): Boolean =
        dictionary.isDownloaded(settingsRepo.settings.first().targetLanguage)

    /** Downloads the target-language dictionary pack; returns true once a usable pack is present. */
    suspend fun downloadDictionary(): Boolean =
        dictionary.ensureDownloaded(settingsRepo.settings.first().targetLanguage)

    /** Looks up an offline definition for [word] in the target language. */
    suspend fun defineSelection(word: String): DictionaryResult =
        dictionary.lookup(word, settingsRepo.settings.first().targetLanguage)

    /** A built system prompt paired with the saved words it wove in, so the reply can record them. */
    private data class PreparedPrompt(val prompt: String, val vocab: List<Pair<String, String>>)

    private suspend fun systemPromptFor(
        c: ConversationEntity,
        includeTitle: Boolean = false,
        includeTopics: Boolean = true,
        // A pre-chosen vocab slice (batch generation); null draws a fresh per-turn sample.
        vocabOverride: List<Pair<String, String>>? = null,
    ): PreparedPrompt {
        val settings = settingsRepo.settings.first()
        // A sample of saved words to weave in, plus the prior reply's distinctive words to recur.
        val vocabToUse = vocabOverride ?: pickVocabToUse(c.targetLanguage)
        val reuseWords = latestDistinctiveWords(c.id)
        val prompt = when (c.mode) {
            Mode.CONVERSATION -> prompts.buildConversationPrompt(
                core = settings.corePrompt,
                customInstructions = settings.customInstructions,
                country = c.country,
                targetLang = c.targetLanguage,
                nativeLang = c.nativeLanguage,
                level = c.level,
                subject = c.subject.orEmpty(),
                vocabToUse = vocabToUse,
                reuseWords = reuseWords,
                includeTitle = includeTitle,
                includeTopics = includeTopics,
            )
            // The translated text already sits in history, so discussion turns use a prompt that
            // doesn't re-embed the original; the translation itself is produced by runTextTranslation.
            Mode.URL_TRANSLATION -> prompts.textDiscussionSystemPrompt(
                country = c.country,
                targetLang = c.targetLanguage,
                nativeLang = c.nativeLanguage,
                level = c.level,
                vocabToUse = vocabToUse,
                reuseWords = reuseWords,
                includeTopics = includeTopics,
            )
        }
        return PreparedPrompt(prompt, vocabToUse)
    }

    /**
     * The due-date bound for the vocab-hint pool: words due within the next week, or (when too few
     * qualify) the wider locked-in window — see [ReviewWindows.hintMaxDue].
     */
    private suspend fun vocabHintMaxDue(targetLang: String): Long {
        val now = System.currentTimeMillis()
        val dueSoon = flashcardDao.countUpcomingByLanguage(targetLang, now + ReviewWindows.HINT_DUE_MS)
        return ReviewWindows.hintMaxDue(now, dueSoon)
    }

    /**
     * Vocabulary hints for the model to weave in (front paired with its translation): a random sample
     * of the words soonest up for review. The pool is the next [VOCAB_POOL_SIZE] cards by due date,
     * bounded to those due within the next week (or the locked-in window when fewer than
     * [ReviewWindows.HINT_FALLBACK_MIN_CARDS] are due that soon); [VOCAB_TO_USE_COUNT] are sampled from
     * it. Empty for a learner with no saved words yet.
     */
    private suspend fun pickVocabToUse(targetLang: String): List<Pair<String, String>> {
        val maxDue = vocabHintMaxDue(targetLang)
        return flashcardDao.upcomingByLanguage(targetLang, maxDue, VOCAB_POOL_SIZE)
            .shuffled()
            .take(VOCAB_TO_USE_COUNT)
            .map { it.front to it.back }
    }

    /**
     * Distinct vocab samples for a batch of [count] new conversations, so sibling chats don't all weave
     * in the same saved words. Draws one pool of the soonest-due cards — big enough to fill every chat —
     * and hands out non-overlapping [VOCAB_TO_USE_COUNT]-word slices; if the learner has too few saved
     * words to fill them all, slices cycle (the overlap is then unavoidable). Each list is empty for a
     * learner with no eligible words yet.
     */
    suspend fun pickVocabBatches(targetLang: String, count: Int): List<List<Pair<String, String>>> {
        if (count <= 0) return emptyList()
        val maxDue = vocabHintMaxDue(targetLang)
        val poolSize = maxOf(VOCAB_POOL_SIZE, count * VOCAB_TO_USE_COUNT)
        val pool = flashcardDao.upcomingByLanguage(targetLang, maxDue, poolSize)
            .shuffled()
            .map { it.front to it.back }
        if (pool.isEmpty()) return List(count) { emptyList() }
        val slices = pool.chunked(VOCAB_TO_USE_COUNT)
        return List(count) { i -> slices[i % slices.size] }
    }

    /** The distinctive words from the conversation's most recent assistant reply, for reuse next turn. */
    private suspend fun latestDistinctiveWords(conversationId: Long): List<String> {
        val lastAssistant = messageDao.getLastByRole(conversationId, Role.ASSISTANT)
        return StructuredReplyParser.decode(lastAssistant?.metadata).distinctive
    }

    // ---- Imported texts (URL_TRANSLATION mode) ----

    /** Creates a text conversation for a URL; extraction + translation run later in [runTextTranslation]. */
    suspend fun createUrlTextDraft(url: String): Long {
        val trimmed = url.trim()
        val full = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
        return createTextDraft(
            title = ArticleExtractor.hostOf(full),
            sourceUrl = trimmed,
            originalTitle = null,
            originalText = null,
        )
    }

    /** Creates a text conversation from pasted text; [runTextTranslation] translates it (no fetch). */
    suspend fun createPastedTextDraft(rawText: String): Long {
        val text = rawText.trim()
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val title = firstLine.take(80).ifBlank { text.take(80) }
        return createTextDraft(
            title = title,
            sourceUrl = null,
            originalTitle = firstLine.ifBlank { null },
            originalText = text,
        )
    }

    private suspend fun createTextDraft(
        title: String,
        sourceUrl: String?,
        originalTitle: String?,
        originalText: String?,
    ): Long {
        val settings = settingsRepo.settings.first()
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            mode = Mode.URL_TRANSLATION,
            title = title.ifBlank { "…" },
            subject = originalTitle,
            sourceUrl = sourceUrl,
            originalTitle = originalTitle,
            originalText = originalText,
            level = settings.defaultLevel,
            country = settings.defaultCountry,
            targetLanguage = settings.targetLanguage,
            nativeLanguage = settings.nativeLanguage,
            provider = settings.provider,
            model = settings.activeModel,
            createdAt = now,
            updatedAt = now,
        )
        val id = conversationDao.insert(conversation)
        // A leading user turn so the model's first message is a user one (some providers require an
        // alternating history) and so the later discussion is valid; it's hidden in the reader and chat.
        messageDao.insert(
            MessageEntity(conversationId = id, role = Role.USER, content = TEXT_SEED_MESSAGE, createdAt = now),
        )
        return id
    }

    /**
     * Extracts (for URLs) then translates an imported text into the target language, in paragraph-
     * sized chunks so very long articles stay under the model's output limit. The translated body is
     * grown in place as each chunk lands (so the reader sees progress), and the title is replaced with
     * its translation. Safe to re-run as a retry: extraction is skipped if already done and the
     * translation message is reused.
     */
    suspend fun runTextTranslation(id: Long, onProgress: (done: Int, total: Int) -> Unit) {
        var conversation = conversationDao.getById(id) ?: return
        val provider = providers.forId(conversation.provider)

        // 1) Ensure we have the source text (fetch + readability for URLs).
        if (conversation.originalText.isNullOrBlank()) {
            val url = conversation.sourceUrl ?: throw LlmException(LlmError.Extraction)
            val article = articleExtractor.extract(url)
            conversation = conversation.copy(
                originalTitle = article.title,
                originalText = article.text,
                title = article.title.take(120),
            )
            conversationDao.update(conversation)
        }

        val chunks = chunkText(conversation.originalText.orEmpty())
        val total = chunks.size
        // Per-part distinctive-word budget, sized so a long article yields a sensible total (not one
        // big list per chunk). See [distinctiveBudgetPerChunk].
        val distinctiveBudget = distinctiveBudgetPerChunk(chunks)
        val existing = messageDao.getForConversation(id).firstOrNull { it.role == Role.ASSISTANT }
        val messageId = existing?.id ?: insertEmptyTranslation(conversation)

        // Resume from where a prior attempt stopped: finished chunks (and the title) are preserved, so
        // a rate-limit/network failure mid-way only costs the remaining chunks. A retry with no saved
        // assistant message starts clean.
        var done = if (existing == null) 0 else conversation.translatedChunks.coerceIn(0, total)
        val hasTitle = conversation.originalTitle?.isNotBlank() == true
        val builder = StringBuilder()
        if (done > 0) {
            builder.append(existing?.content.orEmpty())
        } else {
            // Fresh start: (re)translate the short title, then clear any stale partial body.
            if (hasTitle) {
                val translatedTitle = provider.chat(
                    LlmRequest(
                        systemPrompt = textTranslationPrompt(conversation, part = 1, total = 1),
                        messages = listOf(LlmMessage(Role.USER, conversation.originalTitle!!.trim())),
                        model = conversation.model,
                        maxTokens = 256,
                    ),
                ).text.trim().ifBlank { conversation.title }
                conversation = conversation.copy(title = translatedTitle.take(120))
            }
            messageDao.updateContent(messageId, "")
        }
        // Distinctive words accumulate (deduped) across all parts; topics come from the final part.
        // On a resume, seed from whatever a prior attempt already saved on the message.
        val distinctiveWords = linkedSetOf<String>()
        var topics: List<com.copiosa.domain.prompt.TopicSuggestion> = emptyList()
        if (done > 0) {
            val prior = StructuredReplyParser.decode(existing?.metadata)
            distinctiveWords.addAll(prior.distinctive)
            topics = prior.topics
        }
        // Record the chunk plan so the reader/list can tell a finished text from a stalled partial.
        conversation = conversation.copy(totalChunks = total, translatedChunks = done)
        conversationDao.update(conversation)

        onProgress(done, total)
        while (done < total) {
            // A few seconds after the preceding call (the title, or the previous chunk) completes, so a
            // long article doesn't burst past a provider's per-minute request cap.
            if (done > 0 || hasTitle) delay(CHUNK_PACING_MS)
            val raw = provider.chat(
                LlmRequest(
                    systemPrompt = textTranslationPrompt(conversation, part = done + 1, total = total, distinctiveBudget = distinctiveBudget),
                    messages = listOf(LlmMessage(Role.USER, chunks[done])),
                    model = conversation.model,
                    maxTokens = CHUNK_MAX_TOKENS,
                ),
            ).text
            // Strip the trailing metadata block from the visible translation; collect its words/topics.
            val (translated, meta) = StructuredReplyParser.parse(raw)
            distinctiveWords.addAll(meta.distinctive)
            if (meta.topics.isNotEmpty()) topics = meta.topics
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(translated)
            messageDao.updateContent(messageId, builder.toString())
            done += 1
            // Persist progress only after the content is saved, so a crash never claims more is done
            // than is actually stored.
            conversation = conversation.copy(translatedChunks = done, updatedAt = System.currentTimeMillis())
            conversationDao.update(conversation)
            // Save accumulated metadata as we go, so a resume keeps earlier parts' distinctive words.
            messageDao.updateMetadata(
                messageId,
                StructuredReplyParser.encode(ReplyMetadata(topics = topics, distinctive = distinctiveWords.toList())),
            )
            onProgress(done, total)
        }
        // The imported text is itself an area of interest; capture its title and any suggested
        // follow-ups (with their glosses) as routine seeds.
        if (conversation.title.isNotBlank()) {
            topicRepo.record(
                phrase = conversation.title,
                source = TopicSource.USER_TRANSLATION,
                targetLanguage = conversation.targetLanguage,
                sourceConversationId = conversation.id,
            )
        }
        for (topic in topics) {
            topicRepo.record(
                phrase = topic.phrase,
                source = TopicSource.CHAT_SUGGESTION,
                targetLanguage = conversation.targetLanguage,
                description = topic.gloss,
                sourceConversationId = conversation.id,
            )
        }
    }

    private fun textTranslationPrompt(c: ConversationEntity, part: Int, total: Int, distinctiveBudget: Int = 0): String =
        prompts.textTranslationSystemPrompt(
            country = c.country,
            targetLang = c.targetLanguage,
            nativeLang = c.nativeLanguage,
            level = c.level,
            part = part,
            totalParts = total,
            distinctiveBudget = distinctiveBudget,
        )

    /**
     * Distinctive-word budget per translation part. A whole-text target of ~1 word per 1000 chars
     * (clamped 3..12) is spread across the parts so a short text yields a handful and a long, chunked
     * one stays modest (≈1–2 per part) instead of a big list each time.
     */
    private fun distinctiveBudgetPerChunk(chunks: List<String>): Int {
        if (chunks.isEmpty()) return 0
        val totalChars = chunks.sumOf { it.length }
        val totalBudget = (totalChars / 1000).coerceIn(3, 12)
        return (totalBudget.toDouble() / chunks.size).roundToInt().coerceAtLeast(1)
    }

    private suspend fun insertEmptyTranslation(c: ConversationEntity): Long =
        messageDao.insert(
            MessageEntity(
                conversationId = c.id,
                role = Role.ASSISTANT,
                content = "",
                createdAt = System.currentTimeMillis(),
                model = c.model,
                level = c.level,
                country = c.country,
            ),
        )

    /** Splits source text into paragraph-bounded chunks under [CHUNK_CHARS] for sequential translation. */
    private fun chunkText(text: String): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return listOf(text.trim()).filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (p in paragraphs) {
            if (current.isNotEmpty() && current.length + p.length + 2 > CHUNK_CHARS) {
                chunks.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(p)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    companion object {
        /**
         * Source chars per model call. Sized so a typical article (≲2000 words) is a single call —
         * fewer requests means far less chance of tripping a provider's per-minute rate limit — while
         * still leaving a chunk's translation comfortably under [CHUNK_MAX_TOKENS].
         */
        private const val CHUNK_CHARS = 9000
        private const val CHUNK_MAX_TOKENS = 8192
        /** Gap after one translation call completes before the next, to stay clear of rate limits. */
        private const val CHUNK_PACING_MS = 4_000L
        private const val TEXT_SEED_MESSAGE = "Please help me read and understand this text."

        /** The next-due cards the per-turn vocab hints are sampled from. */
        private const val VOCAB_POOL_SIZE = 100

        /** How many saved words to sample per turn for the model to weave in. */
        private const val VOCAB_TO_USE_COUNT = 10

        /** Most recent messages sent as LLM context; older turns are dropped, not summarized. */
        private const val HISTORY_MESSAGE_CAP = 60
    }
}
