package com.copiosa.data.maintenance.tasks

import com.copiosa.data.local.ConversationDao
import com.copiosa.data.local.SuggestedTopicDao
import com.copiosa.data.local.SuggestedTopicEntity
import com.copiosa.data.local.TopicDao
import com.copiosa.data.maintenance.LastRunStore
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.Role
import com.copiosa.domain.topics.TopicSuggestionPrompt
import com.copiosa.domain.topics.TopicSuggestionScorer
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proposes conversation topics the learner might want next, mined from their chat history and
 * recorded interests.
 *
 * Two stages: a free local ranking ([TopicSuggestionScorer]) over signals the app already has, then
 * one model call to turn those interests into fresh suggestions. If the call fails or comes back
 * unusable, the scorer's own ranking is stored instead — the feature degrades rather than breaking.
 *
 * Only runs for the active profile, and only once the learner has enough history to mine.
 */
@Singleton
class TopicSuggestionTask @Inject constructor(
    private val conversationDao: ConversationDao,
    private val topicDao: TopicDao,
    private val suggestedTopicDao: SuggestedTopicDao,
    private val providers: ProviderRegistry,
    private val lastRun: LastRunStore,
) : MaintenanceTask {

    override val id = "topic_suggestions"
    override val scope = TaskScope.ACTIVE_PROFILE
    override val needsLlm = true
    override val minIntervalMs: Long = TimeUnit.DAYS.toMillis(3)

    override suspend fun run(ctx: TaskContext) {
        val lang = ctx.profileTag
        if (conversationDao.countByMode(Mode.CONVERSATION, lang) <= MIN_CHATS) return

        // Nothing new to learn from since the last refresh means the same suggestions would come
        // back out; skip the call entirely.
        val since = lastRun.getLong(lastGeneratedKey(lang)) ?: 0L
        if (since > 0 && conversationDao.countByModeSince(Mode.CONVERSATION, lang, since) == 0) return

        val ranked = TopicSuggestionScorer.rank(collectSignals(lang), ctx.now, limit = SIGNAL_LIMIT)
        if (ranked.isEmpty()) return

        val suggestions = generate(ranked, ctx) ?: ranked.map {
            TopicSuggestionPrompt.Suggestion(it.phrase, it.description)
        }

        val rows = suggestions.take(SUGGESTION_COUNT).mapIndexed { index, suggestion ->
            SuggestedTopicEntity(
                phrase = suggestion.phrase,
                rationale = suggestion.rationale,
                targetLanguage = lang,
                // Preserve the model's ordering as a descending score.
                score = (suggestions.size - index).toDouble(),
                generatedAt = ctx.now,
            )
        }
        if (rows.isEmpty()) return

        // Replaced wholesale so stale suggestions never accumulate.
        suggestedTopicDao.clear(lang)
        suggestedTopicDao.insertAll(rows)
        lastRun.putLong(lastGeneratedKey(lang), ctx.now)
    }

    /** The learner's recorded interests plus the subjects of chats they started. */
    private suspend fun collectSignals(lang: String): List<TopicSuggestionScorer.Signal> {
        val topics = topicDao.suggestions(lang, SIGNAL_LIMIT).map { topic ->
            TopicSuggestionScorer.Signal(
                phrase = topic.phrase,
                description = topic.description,
                at = topic.lastUsedAt ?: topic.createdAt,
                weight = topic.useCount,
            )
        }
        val subjects = conversationDao.recentByMode(Mode.CONVERSATION, lang, SIGNAL_LIMIT)
            .mapNotNull { conversation ->
                val subject = conversation.subject?.trim()?.ifBlank { null } ?: return@mapNotNull null
                TopicSuggestionScorer.Signal(phrase = subject, at = conversation.createdAt)
            }
        return topics + subjects
    }

    /** Null when the call fails or the response yields nothing usable; the caller falls back. */
    private suspend fun generate(
        ranked: List<TopicSuggestionScorer.Scored>,
        ctx: TaskContext,
    ): List<TopicSuggestionPrompt.Suggestion>? {
        val prompt = TopicSuggestionPrompt.build(ranked, ctx.profileTag, SUGGESTION_COUNT)
        val parsed = try {
            val response = providers.forId(ctx.settings.provider).chat(
                LlmRequest(
                    systemPrompt = TopicSuggestionPrompt.SYSTEM_PROMPT,
                    messages = listOf(LlmMessage(Role.USER, prompt)),
                    model = ctx.settings.activeModel,
                    maxTokens = MAX_TOKENS,
                ),
            )
            TopicSuggestionPrompt.parse(response.text, SUGGESTION_COUNT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return parsed.ifEmpty { null }
    }

    private companion object {
        /** Below this much history the suggestions would just echo the learner's only few chats. */
        const val MIN_CHATS = 10
        const val SUGGESTION_COUNT = 5
        const val SIGNAL_LIMIT = 20
        const val MAX_TOKENS = 512

        fun lastGeneratedKey(tag: String) = "topics.$tag.generated_at"
    }
}
