package com.copiosa.data.routine

import com.copiosa.data.local.TopicDao
import com.copiosa.data.local.TopicEntity
import com.copiosa.domain.routine.CalmHeuristic
import com.copiosa.domain.routine.TopicSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The learner's interest table: topics worth seeding routine content with. Populated opportunistically
 * from existing flows (word lookups, started chats, the model's suggested next-topics) via [record],
 * and read back by the routine setup screen via [suggestions]. Deduplicated by (phrase, language): a
 * repeat sighting bumps [TopicEntity.useCount] and fills in a missing description rather than inserting
 * a duplicate.
 */
@Singleton
class TopicRepository @Inject constructor(
    private val dao: TopicDao,
) {
    suspend fun record(
        phrase: String,
        source: TopicSource,
        targetLanguage: String,
        description: String? = null,
        sourceConversationId: Long? = null,
        sourceMessageId: Long? = null,
        // Null lets the calm heuristic score the phrase; pass a value to override.
        calmScore: Double? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        val clean = phrase.trim()
        if (clean.isEmpty()) return
        val desc = description?.trim()?.ifBlank { null }
        val existing = dao.find(clean, targetLanguage)
        if (existing == null) {
            dao.insert(
                TopicEntity(
                    phrase = clean,
                    description = desc,
                    source = source,
                    sourceConversationId = sourceConversationId,
                    sourceMessageId = sourceMessageId,
                    calmScore = calmScore ?: CalmHeuristic.score(clean),
                    targetLanguage = targetLanguage,
                    useCount = 1,
                    createdAt = now,
                    lastUsedAt = now,
                ),
            )
        } else {
            dao.update(
                existing.copy(
                    // Keep the first non-null description we managed to capture (chat glosses are best).
                    description = existing.description ?: desc,
                    sourceConversationId = existing.sourceConversationId ?: sourceConversationId,
                    sourceMessageId = existing.sourceMessageId ?: sourceMessageId,
                    useCount = existing.useCount + 1,
                    lastUsedAt = now,
                ),
            )
        }
    }

    /** Best bedtime seeds first (calmest, then most recently/often touched). */
    suspend fun suggestions(targetLanguage: String, limit: Int = 8): List<TopicEntity> =
        dao.suggestions(targetLanguage, limit)

    fun observe(targetLanguage: String): Flow<List<TopicEntity>> = dao.observeForLanguage(targetLanguage)

    /** Marks a topic as just used to seed a routine, so it sinks in the suggestion ordering. */
    suspend fun markUsed(phrase: String, targetLanguage: String, now: Long = System.currentTimeMillis()) {
        val existing = dao.find(phrase.trim(), targetLanguage) ?: return
        dao.update(existing.copy(useCount = existing.useCount + 1, lastUsedAt = now))
    }
}
