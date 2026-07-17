package com.copiosa.data.generation

import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a conversation is waiting its turn in a batch, or actively calling the model. */
enum class GenPhase { QUEUED, GENERATING }

/**
 * Owns every LLM generation so a fetch survives the user leaving the screen. Work runs on an
 * app-lifetime [scope] (not a `viewModelScope`), and the in-flight phase is exposed as flows the UI
 * observes, so "Thinking…"/"Generating…" persists across navigation.
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _active = MutableStateFlow<Map<Long, GenPhase>>(emptyMap())
    val active: StateFlow<Map<Long, GenPhase>> = _active.asStateFlow()

    private val _errors = MutableStateFlow<Map<Long, LlmError>>(emptyMap())
    val errors: StateFlow<Map<Long, LlmError>> = _errors.asStateFlow()

    /** Chunk progress for an in-flight text translation: id -> (sections done, total sections). */
    private val _progress = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())
    val progress: StateFlow<Map<Long, Pair<Int, Int>>> = _progress.asStateFlow()

    /** Fires when a chat batch is committed, so the Library list can jump to the new queued rows. */
    private val _batchStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val batchStarted: SharedFlow<Unit> = _batchStarted.asSharedFlow()

    /**
     * Generates the reply for [id]'s trailing user turn — a fresh chat's first reply, or a retry.
     * [vocab] is an optional pre-chosen vocabulary slice (batch generation hands each chat a distinct
     * one); null falls back to a fresh per-turn sample.
     */
    fun generateReply(id: Long, vocab: List<Pair<String, String>>? = null): Job = launchGeneration(id) {
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
                _active.update { it + (id to GenPhase.QUEUED) }
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
            _progress.update { it + (id to (done to total)) }
        }
    }

    /** Aborts the in-flight call for [id]; any already-saved user turn stays in place (drives retry). */
    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        _active.update { it - id }
    }

    /** Clears a surfaced error after the open chat has shown it. */
    fun consumeError(id: Long) { _errors.update { it - id } }

    private fun launchGeneration(id: Long, block: suspend () -> Unit): Job {
        // Replace any prior in-flight call for this conversation and clear a stale error/progress.
        jobs.remove(id)?.cancel()
        _errors.update { it - id }
        _progress.update { it - id }
        _active.update { it + (id to GenPhase.GENERATING) }
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = (e as? LlmException)?.error ?: LlmError.Unknown(e.message ?: "error")
                _errors.update { it + (id to error) }
            }
        }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id, job)
            _active.update { it - id }
            _progress.update { it - id }
        }
        return job
    }
}
