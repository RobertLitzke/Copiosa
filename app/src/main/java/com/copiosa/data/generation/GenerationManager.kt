package com.copiosa.data.generation

import com.copiosa.data.remote.LlmError
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.di.AppScope
import com.copiosa.domain.prompt.VocabHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every LLM generation so a fetch survives the user leaving the screen. Work runs on an
 * app-lifetime scope (not a `viewModelScope`) through a [KeyedJobTracker], whose phase, error and
 * progress flows the UI observes, so "Thinking…"/"Generating…" persists across navigation. A new
 * request for a conversation replaces its in-flight one.
 *
 * Failure is deliberately *not* stored as a phase: a conversation whose last message is a USER turn
 * and that isn't in [active] is awaiting a reply / failed — derivable straight from Room, so the
 * retry state is correct even after process death (a killed-mid-fetch chat just shows "tap to retry"
 * on relaunch). [errors] only carries the reason to surface inside the currently-open chat.
 */
@Singleton
class GenerationManager @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val settingsRepo: SettingsRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val jobs = KeyedJobTracker(scope)

    val active: StateFlow<Map<Long, GenPhase>> = jobs.active
    val errors: StateFlow<Map<Long, LlmError>> = jobs.errors

    /** Chunk progress for an in-flight text translation: id -> (sections done, total sections). */
    val progress: StateFlow<Map<Long, Pair<Int, Int>>> = jobs.progress

    /** Fires when a chat batch is committed, so the Library list can jump to the new queued rows. */
    private val _batchStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val batchStarted: SharedFlow<Unit> = _batchStarted.asSharedFlow()

    /**
     * Generates the reply for [id]'s trailing user turn — a fresh chat's first reply, or a retry.
     * [vocab] is an optional pre-chosen vocabulary slice (batch generation hands each chat a distinct
     * one); null falls back to a fresh per-turn sample.
     */
    fun generateReply(id: Long, vocab: List<VocabHint>? = null): Job = launchGeneration(id) {
        conversationRepo.generateFirstReply(id, rollbackOnError = false, vocabOverride = vocab)
    }

    /**
     * Appends [text] as a user turn on [id], then generates the reply (normal turns and "Explain").
     * [isExplanation] marks the word-lookup "Explain" action so its reply skips next-topic suggestions.
     */
    fun sendUserTurn(id: Long, text: String, isExplanation: Boolean = false): Job = launchGeneration(id) {
        conversationRepo.sendMessage(id, text, isExplanation = isExplanation)
    }

    /**
     * Spawns a chat per topic and generates the opening replies in sequence. Each conversation is
     * created up front (so all topics appear immediately as Queued), then replies are fetched one at
     * a time so the batch doesn't hammer the API. Topics are trimmed; blank lines are ignored.
     * Level/provider/country come from the user's current settings.
     */
    fun startChatBatch(topics: List<String>) {
        val cleaned = topics.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        // Announce before the inserts land so a listener can snapshot the pre-batch list state.
        _batchStarted.tryEmit(Unit)
        scope.launch {
            val settings = settingsRepo.settings.first()
            // A distinct vocab slice per chat, so sibling chats don't all weave in the same saved words.
            val vocabBatches = conversationRepo.pickVocabBatches(settings.targetLanguage, cleaned.size)
            // Distinct, descending timestamps so the batch lists in entry order (first topic on top)
            // and stays put as replies land — see createDraftConversation's createdAt note.
            val base = System.currentTimeMillis()
            val ids = cleaned.mapIndexed { i, topic ->
                val id = conversationRepo.createDraftConversation(
                    level = settings.defaultLevel,
                    country = settings.defaultCountry,
                    subject = topic,
                    provider = settings.provider,
                    model = settings.activeModel,
                    createdAt = base - i,
                    // Unread until the user opens it, so the list highlights freshly-generated chats.
                    read = false,
                )
                jobs.markQueued(id)
                id
            }
            ids.forEachIndexed { i, id -> generateReply(id, vocabBatches.getOrNull(i)).join() }
        }
    }

    /** Creates a text conversation from a URL and starts extraction + translation; returns the new id. */
    suspend fun importUrl(url: String): Long {
        val id = conversationRepo.createUrlTextDraft(url)
        translate(id)
        return id
    }

    /** Creates a text conversation from pasted text and starts translation; returns the new id. */
    suspend fun importText(text: String): Long {
        val id = conversationRepo.createPastedTextDraft(text)
        translate(id)
        return id
    }

    /** Translates (or retries) an imported text [id], publishing per-section progress to [progress]. */
    fun translate(id: Long): Job = launchGeneration(id) {
        conversationRepo.runTextTranslation(id) { done, total ->
            jobs.setProgress(id, done, total)
        }
    }

    /** Aborts the in-flight call for [id]; any already-saved user turn stays in place (drives retry). */
    fun cancel(id: Long) = jobs.cancel(id)

    /** Clears a surfaced error after the open chat has shown it. */
    fun consumeError(id: Long) = jobs.consumeError(id)

    /** Replaces any prior in-flight call for this conversation, clearing its stale error/progress. */
    private fun launchGeneration(id: Long, block: suspend () -> Unit): Job =
        jobs.launch(id, IfRunning.REPLACE, body = block)
}
