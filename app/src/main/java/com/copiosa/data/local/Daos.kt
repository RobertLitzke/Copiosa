package com.copiosa.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.copiosa.domain.model.AudioStatus
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.model.Role
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.RoutineType
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE mode = :mode AND targetLanguage = :lang ORDER BY updatedAt DESC")
    fun observeByMode(mode: Mode, lang: String): Flow<List<ConversationEntity>>

    @Query(
        "SELECT * FROM conversations " +
            "WHERE mode = :mode AND targetLanguage = :lang " +
            "AND (title LIKE '%' || :q || '%' OR subject LIKE '%' || :q || '%') " +
            "ORDER BY updatedAt DESC",
    )
    fun searchByMode(mode: Mode, q: String, lang: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    @Query("UPDATE conversations SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE conversations SET read = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("UPDATE conversations SET scrollIndex = :index, scrollOffset = :offset WHERE id = :id")
    suspend fun setScroll(id: Long, index: Int, offset: Int)

    @Query("UPDATE conversations SET finishedReading = :finished WHERE id = :id")
    suspend fun setFinishedReading(id: Long, finished: Boolean)

    @Query("SELECT * FROM conversations WHERE targetLanguage = :lang ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecent(lang: String): ConversationEntity?

    @Query("SELECT COUNT(*) FROM conversations WHERE mode = :mode AND targetLanguage = :lang")
    suspend fun countByMode(mode: Mode, lang: String): Int

    @Query("SELECT COUNT(*) FROM conversations WHERE mode = :mode AND targetLanguage = :lang AND createdAt > :since")
    suspend fun countByModeSince(mode: Mode, lang: String, since: Long): Int

    /** Recent chat subjects — the raw signal the topic suggester ranks. */
    @Query(
        "SELECT * FROM conversations WHERE mode = :mode AND targetLanguage = :lang " +
            "AND subject IS NOT NULL ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun recentByMode(mode: Mode, lang: String, limit: Int): List<ConversationEntity>

    /**
     * Chats whose newest turn is still the user's — a reply that failed or was interrupted.
     *
     * Failure itself stays derived rather than stored, so this stays correct across process death;
     * [ConversationEntity.autoRetryAttempts] only bounds how often background maintenance retries
     * one, so a permanently-broken chat eventually stops costing calls.
     */
    @Query(
        "SELECT c.* FROM conversations c " +
            "WHERE c.mode = :mode AND c.targetLanguage = :lang " +
            "AND c.autoRetryAttempts < :maxAttempts " +
            "AND (SELECT m.role FROM messages m WHERE m.conversationId = c.id " +
            "     ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) = :userRole " +
            "ORDER BY c.updatedAt DESC LIMIT :limit",
    )
    suspend fun awaitingReply(
        mode: Mode,
        lang: String,
        userRole: Role,
        maxAttempts: Int,
        limit: Int,
    ): List<ConversationEntity>

    @Query(
        "UPDATE conversations SET autoRetryAttempts = autoRetryAttempts + 1, lastAutoRetryAt = :now " +
            "WHERE id = :id",
    )
    suspend fun recordAutoRetry(id: Long, now: Long)

    /** Clears the retry budget once a reply lands, so a recovered chat starts fresh next time. */
    @Query("UPDATE conversations SET autoRetryAttempts = 0, lastAutoRetryAt = NULL WHERE id = :id")
    suspend fun clearAutoRetry(id: Long)

    /** Newest unread chat in [lang], the Home screen's "jump back in" candidate. */
    @Query(
        "SELECT * FROM conversations WHERE mode = :mode AND targetLanguage = :lang AND read = 0 " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    fun observeLatestUnread(mode: Mode, lang: String): Flow<ConversationEntity?>

    /** Newest fully-translated text not yet read to the end — the Home "continue reading" candidate. */
    @Query(
        "SELECT * FROM conversations WHERE mode = :mode AND targetLanguage = :lang " +
            "AND finishedReading = 0 AND totalChunks > 0 AND translatedChunks >= totalChunks " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    fun observeLatestUnfinishedText(mode: Mode, lang: String): Flow<ConversationEntity?>

    @Query("SELECT COUNT(*) FROM conversations WHERE mode = :mode AND targetLanguage = :lang AND read = 0")
    fun observeUnreadCount(mode: Mode, lang: String): Flow<Int>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    // --- stats screen (null lang = all profiles combined) ---

    @Query("SELECT COUNT(*) FROM conversations WHERE mode = :mode AND (:lang IS NULL OR targetLanguage = :lang)")
    fun observeCountByMode(mode: Mode, lang: String?): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM conversations WHERE mode = :mode AND finishedReading = 1 " +
            "AND (:lang IS NULL OR targetLanguage = :lang)",
    )
    fun observeFinishedTextCount(mode: Mode, lang: String?): Flow<Int>
}

/**
 * Latest message for a conversation, used as the chat-list snippet preview. [role] lets the list
 * tell a still-awaited chat (last turn is the user's) from a completed one.
 */
data class ConversationPreview(val conversationId: Long, val snippet: String, val role: Role)

/** How many cards sit in one lifecycle [state], for the stats screen's card breakdown. */
data class StateCount(val state: FlashcardState, val count: Int)

/** How many reviews were graded with one [rating], for the stats screen's answer distribution. */
data class RatingCount(val rating: ReviewRating, val count: Int)

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getForConversation(conversationId: Long): List<MessageEntity>

    /** Overwrites a message's body — used to grow a translation message as each chunk lands. */
    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    /** Sets the JSON metadata blob — used by the text translator to write accumulated metadata once. */
    @Query("UPDATE messages SET metadata = :metadata WHERE id = :id")
    suspend fun updateMetadata(id: Long, metadata: String?)

    /** The most recent message per conversation (highest id wins on a timestamp tie). */
    @Query(
        "SELECT m.conversationId AS conversationId, m.content AS snippet, m.role AS role FROM messages m " +
            "WHERE m.id = (" +
            "  SELECT id FROM messages WHERE conversationId = m.conversationId " +
            "  ORDER BY createdAt DESC, id DESC LIMIT 1" +
            ")",
    )
    fun observeLatestPreviews(): Flow<List<ConversationPreview>>

    /** The newest message with [role], without materializing the whole history. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND role = :role " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    suspend fun getLastByRole(conversationId: Long, role: Role): MessageEntity?

    /** Total messages (both roles) across a profile's conversations — the stats "messages exchanged". */
    @Query(
        "SELECT COUNT(*) FROM messages m JOIN conversations c ON m.conversationId = c.id " +
            "WHERE (:lang IS NULL OR c.targetLanguage = :lang)",
    )
    fun observeMessageCount(lang: String?): Flow<Int>

    /**
     * Clears stored raw model output outside the [keepChats] most recently active conversations (by
     * updatedAt, matching the library list order). Raw output is a debug aid, not durable data, so it's
     * bounded to recent chats; reopening an old chat re-earns raw only for its new replies.
     */
    @Query(
        "UPDATE messages SET rawResponse = NULL WHERE rawResponse IS NOT NULL AND conversationId NOT IN (" +
            "SELECT id FROM conversations ORDER BY updatedAt DESC LIMIT :keepChats)",
    )
    suspend fun pruneRawResponses(keepChats: Int)
}

@Dao
interface FlashcardDao {
    @Insert
    suspend fun insert(card: FlashcardEntity): Long

    @Update
    suspend fun update(card: FlashcardEntity)

    @Query("SELECT * FROM flashcards WHERE targetLanguage = :lang ORDER BY createdAt DESC")
    fun observeAll(lang: String): Flow<List<FlashcardEntity>>

    /** Every card in [lang] (including frozen ones), the full set an Anki `.apkg` export is built from. */
    @Query("SELECT * FROM flashcards WHERE targetLanguage = :lang ORDER BY createdAt ASC")
    suspend fun getAll(lang: String): List<FlashcardEntity>

    @Query("SELECT * FROM flashcards WHERE id = :id")
    fun observe(id: Long): Flow<FlashcardEntity?>

    @Query("SELECT * FROM flashcards WHERE id = :id")
    suspend fun getById(id: Long): FlashcardEntity?

    /** Cards in [lang] due for review now. Frozen (suspended) cards are excluded. */
    @Query("SELECT * FROM flashcards WHERE targetLanguage = :lang AND dueAt <= :now AND suspended = 0 ORDER BY dueAt ASC")
    fun observeDue(now: Long, lang: String): Flow<List<FlashcardEntity>>

    /** How many non-frozen cards in [lang] are due at [now] — a count, so no rows are materialized. */
    @Query("SELECT COUNT(*) FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 AND dueAt <= :now")
    suspend fun countDue(lang: String, now: Long): Int

    /** Flow twin of [countDue], for the Home screen's review card. */
    @Query("SELECT COUNT(*) FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 AND dueAt <= :now")
    fun observeCountDue(lang: String, now: Long): Flow<Int>

    /** Every non-frozen card in [lang], the pool a sized review session is composed from. */
    @Query("SELECT * FROM flashcards WHERE targetLanguage = :lang AND suspended = 0")
    suspend fun allActive(lang: String): List<FlashcardEntity>

    /**
     * The soonest-upcoming active saved words in [lang] (due on or before [maxDue]), the pool the
     * conversation vocab hints are sampled from. Restricted to lexical vocab (VOCAB/PHRASE — never
     * conjugation/grammar/cloze/sentence drills) and to the target-front orientation, so
     * [FlashcardEntity.front] is the target term and a note's forward+reverse cards don't both appear.
     * The [maxDue] bound keeps "locked-in" words (due far out) out of the pool entirely.
     */
    @Query(
        "SELECT * FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 " +
            "AND frontIsTarget = 1 AND cardKind IN ('VOCAB', 'PHRASE') AND dueAt <= :maxDue " +
            "ORDER BY dueAt ASC LIMIT :poolSize",
    )
    suspend fun upcomingByLanguage(lang: String, maxDue: Long, poolSize: Int): List<FlashcardEntity>

    /** How many vocab-hint-eligible cards in [lang] are due on or before [maxDue] (same filter as
     *  [upcomingByLanguage]); used to decide whether the hint pool can use a tight due window. */
    @Query(
        "SELECT COUNT(*) FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 " +
            "AND frontIsTarget = 1 AND cardKind IN ('VOCAB', 'PHRASE') AND dueAt <= :maxDue",
    )
    suspend fun countUpcomingByLanguage(lang: String, maxDue: Long): Int

    /** Looks up a previously-imported Anki card by its stable identity, for in-place re-import updates. */
    @Query("SELECT * FROM flashcards WHERE ankiNoteGuid = :guid AND ankiOrd = :ord LIMIT 1")
    suspend fun findByAnkiKey(guid: String, ord: Int): FlashcardEntity?

    /** Active saved target nouns that carry a clear gender — the pool a swipe-to-sort (gender) set draws. */
    @Query(
        "SELECT * FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 " +
            "AND frontIsTarget = 1 AND gender IN ('M', 'F') ORDER BY RANDOM() LIMIT :limit",
    )
    suspend fun nounsWithGender(lang: String, limit: Int): List<FlashcardEntity>

    /** Toggles a card's frozen state (Anki "suspend"). */
    @Query("UPDATE flashcards SET suspended = :suspended WHERE id = :id")
    suspend fun setSuspended(id: Long, suspended: Boolean)

    /** Toggles a card's "marked" flag (Anki "marked"). */
    @Query("UPDATE flashcards SET marked = :marked WHERE id = :id")
    suspend fun setMarked(id: Long, marked: Boolean)

    /** Edits a card's text (typo fixes); leaves scheduling and enrichment untouched. */
    @Query("UPDATE flashcards SET front = :front, back = :back WHERE id = :id")
    suspend fun setText(id: Long, front: String, back: String)

    /** Edits a card's text, alternate forms, part of speech, card type, review direction, gender, country, and usage note from the card editor. */
    @Query(
        "UPDATE flashcards SET front = :front, back = :back, altForms = :altForms, partOfSpeech = :partOfSpeech, " +
            "cardKind = :cardKind, reviewDirection = :reviewDirection, " +
            "contextSentence = :contextSentence, gender = :gender, country = :country, usageNote = :usageNote WHERE id = :id",
    )
    suspend fun setDetails(
        id: Long,
        front: String,
        back: String,
        altForms: String?,
        partOfSpeech: String?,
        cardKind: String,
        reviewDirection: String,
        contextSentence: String?,
        gender: String?,
        country: String?,
        usageNote: String?,
    )

    /** Caches a card's precomputed highlight surface forms (";"-joined); see [FlashcardEntity.inflectedForms]. */
    @Query("UPDATE flashcards SET inflectedForms = :forms WHERE id = :id")
    suspend fun setInflectedForms(id: Long, forms: String?)

    /** Splits a crammed translation: sets the clean [back] and the peeled-out [usageNote]. */
    @Query("UPDATE flashcards SET back = :back, usageNote = :usageNote WHERE id = :id")
    suspend fun setBackAndNote(id: Long, back: String, usageNote: String?)

    /**
     * Cards whose translation carries Markdown/multi-line structure — the backlog for the note-split
     * backfill (and repair of earlier mis-splits). Gated on bold markup or a newline in `back` so
     * clean single-line glosses are never scanned; a plain ML Kit translation has neither. (Note: in
     * SQL LIKE, `_` is a single-char wildcard, so an underscore clause would match nearly everything —
     * bold `**` plus the newline check already cover the generated blobs.)
     */
    @Query(
        "SELECT * FROM flashcards WHERE back LIKE '%**%' OR back LIKE '%' || char(10) || '%'",
    )
    suspend fun cardsWithMarkdownBack(): List<FlashcardEntity>

    /** Cards whose highlight forms haven't been computed yet — the one-time backfill backlog. */
    @Query("SELECT * FROM flashcards WHERE inflectedForms IS NULL")
    suspend fun cardsMissingInflectedForms(): List<FlashcardEntity>

    /** Sets (or clears, with null) a card's image filename in the media store. */
    @Query("UPDATE flashcards SET imageFile = :file WHERE id = :id")
    suspend fun setImageFile(id: Long, file: String?)

    /** Active lexical cards (VOCAB/PHRASE) in [lang] with no image yet, newest first — the "add images" backlog. */
    @Query(
        "SELECT * FROM flashcards WHERE targetLanguage = :lang AND imageFile IS NULL AND suspended = 0 " +
            "AND cardKind IN ('VOCAB', 'PHRASE') ORDER BY createdAt DESC",
    )
    suspend fun imagelessCards(lang: String): List<FlashcardEntity>

    /** Pushes a card's due date (passive review when the word is seen in reading material). */
    @Query("UPDATE flashcards SET dueAt = :dueAt WHERE id = :id")
    suspend fun setDueAt(id: Long, dueAt: Long)

    @Query("DELETE FROM flashcards WHERE id = :id")
    suspend fun delete(id: Long)

    // --- stats screen (null lang = all profiles combined) ---

    @Query("SELECT COUNT(*) FROM flashcards WHERE (:lang IS NULL OR targetLanguage = :lang)")
    fun observeCardCount(lang: String?): Flow<Int>

    @Query("SELECT COUNT(*) FROM flashcards WHERE suspended = 1 AND (:lang IS NULL OR targetLanguage = :lang)")
    fun observeSuspendedCount(lang: String?): Flow<Int>

    @Query(
        "SELECT state, COUNT(*) AS count FROM flashcards " +
            "WHERE (:lang IS NULL OR targetLanguage = :lang) GROUP BY state",
    )
    fun observeStateCounts(lang: String?): Flow<List<StateCount>>

    @Query("SELECT COUNT(*) FROM flashcards WHERE createdAt >= :since AND (:lang IS NULL OR targetLanguage = :lang)")
    fun observeCardsAddedSince(lang: String?, since: Long): Flow<Int>
}

@Dao
interface AudioItemDao {
    @Insert
    suspend fun insert(item: AudioItemEntity): Long

    @Update
    suspend fun update(item: AudioItemEntity)

    /** The active language's playlist, played items sunk to the bottom; drives the Audio tab list. */
    @Query("SELECT * FROM audio_items WHERE langCode = :lang ORDER BY played ASC, sortOrder ASC")
    fun observeAll(lang: String): Flow<List<AudioItemEntity>>

    /** The playable queue in [lang], in playlist order, for building the ExoPlayer media list. */
    @Query("SELECT * FROM audio_items WHERE langCode = :lang AND status = 'READY' ORDER BY played ASC, sortOrder ASC")
    fun observeReady(lang: String): Flow<List<AudioItemEntity>>

    @Query("SELECT * FROM audio_items WHERE id = :id")
    suspend fun getById(id: Long): AudioItemEntity?

    /** How many rendered-and-unheard items await in [lang], for the Home screen's audio card. */
    @Query("SELECT COUNT(*) FROM audio_items WHERE langCode = :lang AND played = 0 AND status = 'READY'")
    fun observeUnplayedReadyCount(lang: String): Flow<Int>

    /** Items still to be rendered (after a relaunch the queue is rebuilt from these). */
    @Query("SELECT * FROM audio_items WHERE status IN ('PENDING', 'GENERATING') ORDER BY sortOrder ASC")
    suspend fun unrendered(): List<AudioItemEntity>

    @Query("SELECT * FROM audio_items WHERE langCode = :lang AND played = 1")
    suspend fun playedItems(lang: String): List<AudioItemEntity>

    @Query("SELECT MAX(sortOrder) FROM audio_items")
    suspend fun maxSortOrder(): Double?

    @Query("UPDATE audio_items SET played = :played, sortOrder = :sortOrder, playedAt = :playedAt WHERE id = :id")
    suspend fun setPlayedAndOrder(id: Long, played: Boolean, sortOrder: Double, playedAt: Long?)

    /** Also clears [AudioItemEntity.playedAt]: un-marking an item restarts its retention clock. */
    @Query("UPDATE audio_items SET played = :played, playedAt = NULL WHERE id = :id")
    suspend fun setPlayed(id: Long, played: Boolean)

    @Query("UPDATE audio_items SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Double)

    @Query("UPDATE audio_items SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: AudioStatus)

    @Query(
        "UPDATE audio_items SET status = :status, filePath = :filePath, durationMs = :durationMs, " +
            "error = :error WHERE id = :id",
    )
    suspend fun setResult(id: Long, status: AudioStatus, filePath: String?, durationMs: Long?, error: String?)

    @Query("DELETE FROM audio_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM audio_items WHERE langCode = :lang AND played = 1")
    suspend fun deletePlayed(lang: String)

    /** Rows still pointing at legacy WAV renders, for the one-time AAC re-encode. */
    @Query("SELECT * FROM audio_items WHERE filePath LIKE '%.wav'")
    suspend fun legacyWavItems(): List<AudioItemEntity>

    /** Items in [lang] whose playback finished before [cutoff] — the retention backlog. */
    @Query(
        "SELECT * FROM audio_items WHERE played = 1 AND langCode = :lang " +
            "AND playedAt IS NOT NULL AND playedAt < :cutoff",
    )
    suspend fun playedBefore(lang: String, cutoff: Long): List<AudioItemEntity>

    // --- stats screen (null lang = all profiles combined) ---

    @Query("SELECT COUNT(*) FROM audio_items WHERE played = 1 AND (:lang IS NULL OR langCode = :lang)")
    fun observePlayedCount(lang: String?): Flow<Int>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM audio_items WHERE played = 1 AND (:lang IS NULL OR langCode = :lang)")
    fun observePlayedDurationMs(lang: String?): Flow<Long>
}

@Dao
interface ReviewLogDao {
    @Insert
    suspend fun insert(log: ReviewLogEntity): Long

    @Query("SELECT * FROM reviews WHERE cardId = :cardId ORDER BY reviewedAt ASC")
    suspend fun forCard(cardId: Long): List<ReviewLogEntity>

    @Query("SELECT * FROM reviews ORDER BY reviewedAt ASC")
    suspend fun all(): List<ReviewLogEntity>

    /** Drops a card's history before re-applying an incoming Anki revlog on import. */
    @Query("DELETE FROM reviews WHERE cardId = :cardId")
    suspend fun deleteForCard(cardId: Long)

    /** Removes a single log entry — used when a review is undone. */
    @Query("DELETE FROM reviews WHERE id = :id")
    suspend fun delete(id: Long)

    /** Card ids reviewed at or after [since] (e.g. start-of-day), for "today's reviewed lemmas". */
    @Query("SELECT DISTINCT cardId FROM reviews WHERE reviewedAt >= :since")
    suspend fun cardIdsReviewedSince(since: Long): List<Long>

    // --- stats screen (null lang = all profiles combined; joins to scope by the card's language) ---

    @Query(
        "SELECT COUNT(*) FROM reviews r JOIN flashcards f ON r.cardId = f.id " +
            "WHERE (:lang IS NULL OR f.targetLanguage = :lang)",
    )
    fun observeReviewCount(lang: String?): Flow<Int>

    /** Reviews graded better than "Again" — the numerator for the accuracy stat. */
    @Query(
        "SELECT COUNT(*) FROM reviews r JOIN flashcards f ON r.cardId = f.id " +
            "WHERE r.rating != 'AGAIN' AND (:lang IS NULL OR f.targetLanguage = :lang)",
    )
    fun observeCorrectReviewCount(lang: String?): Flow<Int>

    /**
     * Raw review timestamps at or after [since], for the "reviews per day" sparkline. Bucketed into
     * days by the caller (in the device zone) so day boundaries line up with the rest of the stats.
     */
    @Query(
        "SELECT r.reviewedAt FROM reviews r JOIN flashcards f ON r.cardId = f.id " +
            "WHERE r.reviewedAt >= :since AND (:lang IS NULL OR f.targetLanguage = :lang)",
    )
    fun observeReviewTimestampsSince(lang: String?, since: Long): Flow<List<Long>>

    /** How often each rating was chosen — the "answer distribution". */
    @Query(
        "SELECT r.rating AS rating, COUNT(*) AS count FROM reviews r JOIN flashcards f ON r.cardId = f.id " +
            "WHERE (:lang IS NULL OR f.targetLanguage = :lang) GROUP BY r.rating",
    )
    fun observeRatingCounts(lang: String?): Flow<List<RatingCount>>
}

@Dao
interface RoutineRunDao {
    @Insert
    suspend fun insert(run: RoutineRunEntity): Long

    @Update
    suspend fun update(run: RoutineRunEntity)

    @Query("SELECT * FROM routine_runs WHERE id = :id")
    suspend fun getById(id: Long): RoutineRunEntity?

    @Query("SELECT * FROM routine_runs WHERE id = :id")
    fun observe(id: Long): Flow<RoutineRunEntity?>

    /** Most recent completed run of a type in [lang] — the basis for serializing the bedtime story night-to-night. */
    @Query(
        "SELECT * FROM routine_runs WHERE routineType = :type AND targetLanguage = :lang AND status = 'COMPLETED' " +
            "ORDER BY completedAt DESC LIMIT 1",
    )
    suspend fun lastCompleted(type: RoutineType, lang: String): RoutineRunEntity?

    @Query("UPDATE routine_runs SET currentIndex = :index WHERE id = :id")
    suspend fun setCurrentIndex(id: Long, index: Int)

    @Query("DELETE FROM routine_runs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ActivityAttemptDao {
    @Insert
    suspend fun insert(attempt: ActivityAttemptEntity): Long

    @Query("SELECT * FROM activity_attempts WHERE routineRunId = :runId ORDER BY createdAt ASC")
    suspend fun forRun(runId: Long): List<ActivityAttemptEntity>

    @Query(
        "SELECT * FROM activity_attempts WHERE activityType = :type AND targetLanguage = :lang " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun recentForType(type: ActivityType, limit: Int, lang: String): List<ActivityAttemptEntity>
}

@Dao
interface ActivityProficiencyDao {
    @Insert
    suspend fun insert(p: ActivityProficiencyEntity): Long

    @Update
    suspend fun update(p: ActivityProficiencyEntity)

    @Query("SELECT * FROM activity_proficiency WHERE bucketKey = :bucketKey AND targetLanguage = :lang LIMIT 1")
    suspend fun get(bucketKey: String, lang: String): ActivityProficiencyEntity?

    @Query("SELECT * FROM activity_proficiency WHERE targetLanguage = :lang")
    suspend fun forLanguage(lang: String): List<ActivityProficiencyEntity>

    @Query("SELECT * FROM activity_proficiency WHERE targetLanguage = :lang")
    fun observeForLanguage(lang: String): Flow<List<ActivityProficiencyEntity>>
}

@Dao
interface TopicDao {
    @Insert
    suspend fun insert(topic: TopicEntity): Long

    @Update
    suspend fun update(topic: TopicEntity)

    @Query("SELECT * FROM topics WHERE phrase = :phrase AND targetLanguage = :lang LIMIT 1")
    suspend fun find(phrase: String, lang: String): TopicEntity?

    /** Best bedtime seeds first: calmest, then most recently/often touched. */
    @Query(
        "SELECT * FROM topics WHERE targetLanguage = :lang " +
            "ORDER BY calmScore DESC, lastUsedAt DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun suggestions(lang: String, limit: Int): List<TopicEntity>

    @Query("SELECT * FROM topics WHERE targetLanguage = :lang ORDER BY createdAt DESC")
    fun observeForLanguage(lang: String): Flow<List<TopicEntity>>

    @Query("DELETE FROM topics WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SuggestedTopicDao {
    @Insert
    suspend fun insertAll(topics: List<SuggestedTopicEntity>)

    /** Live suggestions for [lang], best first, excluding ones the user has dismissed. */
    @Query(
        "SELECT * FROM suggested_topics WHERE targetLanguage = :lang AND dismissedAt IS NULL " +
            "ORDER BY score DESC, generatedAt DESC LIMIT :limit",
    )
    fun observeFor(lang: String, limit: Int): Flow<List<SuggestedTopicEntity>>

    /** Wipes a language's suggestions ahead of a refresh; the set is replaced wholesale. */
    @Query("DELETE FROM suggested_topics WHERE targetLanguage = :lang")
    suspend fun clear(lang: String)

    @Query("UPDATE suggested_topics SET dismissedAt = :now WHERE id = :id")
    suspend fun dismiss(id: Long, now: Long)
}

@Dao
interface QuizDao {
    @Insert
    suspend fun insertPool(pool: QuizPoolEntity): Long

    @Insert
    suspend fun insertCues(cues: List<QuizCueEntity>)

    @Query("SELECT * FROM quiz_pools WHERE langCode = :lang AND cueType = :type LIMIT 1")
    suspend fun pool(lang: String, type: CueType): QuizPoolEntity?

    @Query("SELECT * FROM quiz_pools WHERE langCode = :lang AND cueType = :type LIMIT 1")
    fun observePool(lang: String, type: CueType): Flow<QuizPoolEntity?>

    /** Cues cascade with the pool row. */
    @Query("DELETE FROM quiz_pools WHERE langCode = :lang AND cueType = :type")
    suspend fun deletePool(lang: String, type: CueType)

    /** Cues still to be rendered (after a relaunch the queue is rebuilt from these). */
    @Query("SELECT * FROM quiz_cues WHERE status IN ('PENDING', 'GENERATING') ORDER BY id ASC")
    suspend fun unrendered(): List<QuizCueEntity>

    @Query("SELECT * FROM quiz_cues WHERE id = :id")
    suspend fun getCue(id: Long): QuizCueEntity?

    @Query("UPDATE quiz_cues SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: AudioStatus)

    @Query(
        "UPDATE quiz_cues SET status = :status, filePath = :filePath, durationMs = :durationMs, " +
            "error = :error WHERE id = :id",
    )
    suspend fun setResult(id: Long, status: AudioStatus, filePath: String?, durationMs: Long?, error: String?)

    @Query("SELECT * FROM quiz_cues WHERE poolId = :poolId AND status = 'READY'")
    suspend fun readyCues(poolId: Long): List<QuizCueEntity>

    @Query("SELECT COUNT(*) FROM quiz_cues WHERE poolId = :poolId AND status = 'READY'")
    suspend fun readyCount(poolId: Long): Int

    @Query("SELECT COUNT(*) FROM quiz_cues WHERE poolId = :poolId AND status = 'READY'")
    fun observeReadyCount(poolId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM quiz_cues WHERE poolId = :poolId AND status = 'FAILED'")
    fun observeFailedCount(poolId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM quiz_cues WHERE poolId = :poolId")
    fun observeTotalCount(poolId: Long): Flow<Int>

    /** Every rendered file across all pools, for orphaned-WAV cleanup after a rebuild. */
    @Query("SELECT filePath FROM quiz_cues WHERE filePath IS NOT NULL")
    suspend fun allFilePaths(): List<String>

    @Query("UPDATE quiz_cues SET status = 'PENDING', error = NULL WHERE poolId = :poolId AND status = 'FAILED'")
    suspend fun retryFailed(poolId: Long)

    /** Rows still pointing at legacy WAV renders, for the one-time AAC re-encode. */
    @Query("SELECT * FROM quiz_cues WHERE filePath LIKE '%.wav'")
    suspend fun legacyWavCues(): List<QuizCueEntity>
}

@Dao
interface StoryThreadDao {
    @Query("SELECT * FROM story_threads WHERE targetLanguage = :lang LIMIT 1")
    suspend fun get(lang: String): StoryThreadEntity?

    @Insert
    suspend fun insert(thread: StoryThreadEntity): Long

    @Update
    suspend fun update(thread: StoryThreadEntity)
}

/** A GROUP BY projection for the drill's stats splits (per tense, per family, per direction). */
data class DrillBucketCount(val bucket: String, val total: Int, val correct: Int)

@Dao
interface ConjugationDrillDao {

    // --- selection ---

    @Query("SELECT * FROM conjugation_item_stats WHERE targetLanguage = :lang")
    suspend fun itemStats(lang: String): List<ConjugationItemStatEntity>

    @Query("SELECT * FROM conjugation_item_stats WHERE itemKey = :key AND targetLanguage = :lang LIMIT 1")
    suspend fun itemStat(key: String, lang: String): ConjugationItemStatEntity?

    /**
     * Verbs the learner has already met, in any direction. Generalize cards need a verb that is *not*
     * in this set, so the pattern is the only way to answer.
     */
    @Query("SELECT DISTINCT infinitive FROM conjugation_attempts WHERE targetLanguage = :lang")
    suspend fun seenInfinitives(lang: String): List<String>

    @Insert
    suspend fun insertStat(row: ConjugationItemStatEntity): Long

    @Update
    suspend fun updateStat(row: ConjugationItemStatEntity)

    @Insert
    suspend fun insertAttempt(row: ConjugationAttemptEntity): Long

    // --- stats screen (null lang = all profiles combined) ---

    @Query("SELECT COUNT(*) FROM conjugation_attempts WHERE (:lang IS NULL OR targetLanguage = :lang)")
    fun observeAttemptCount(lang: String?): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM conjugation_attempts " +
            "WHERE correct = 1 AND (:lang IS NULL OR targetLanguage = :lang)",
    )
    fun observeCorrectCount(lang: String?): Flow<Int>

    /** Mean time from prompt to reveal on answers the learner got right. */
    @Query(
        "SELECT AVG(latencyMs) FROM conjugation_attempts " +
            "WHERE correct = 1 AND (:lang IS NULL OR targetLanguage = :lang)",
    )
    fun observeAvgLatencyMs(lang: String?): Flow<Double?>

    @Query(
        "SELECT tense AS bucket, COUNT(*) AS total, SUM(correct) AS correct FROM conjugation_attempts " +
            "WHERE (:lang IS NULL OR targetLanguage = :lang) GROUP BY tense",
    )
    fun observeByTense(lang: String?): Flow<List<DrillBucketCount>>

    @Query(
        "SELECT family AS bucket, COUNT(*) AS total, SUM(correct) AS correct FROM conjugation_attempts " +
            "WHERE (:lang IS NULL OR targetLanguage = :lang) GROUP BY family",
    )
    fun observeByFamily(lang: String?): Flow<List<DrillBucketCount>>

    @Query(
        "SELECT direction AS bucket, COUNT(*) AS total, SUM(correct) AS correct FROM conjugation_attempts " +
            "WHERE (:lang IS NULL OR targetLanguage = :lang) GROUP BY direction",
    )
    fun observeByDirection(lang: String?): Flow<List<DrillBucketCount>>

    @Query("SELECT COUNT(*) FROM conjugation_item_stats WHERE (:lang IS NULL OR targetLanguage = :lang)")
    fun observeItemsSeen(lang: String?): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM conjugation_item_stats " +
            "WHERE mastery >= :threshold AND (:lang IS NULL OR targetLanguage = :lang)",
    )
    fun observeMasteredCount(lang: String?, threshold: Double): Flow<Int>

    // --- reset ---

    @Query("DELETE FROM conjugation_attempts WHERE (:lang IS NULL OR targetLanguage = :lang)")
    suspend fun clearAttempts(lang: String?)

    @Query("DELETE FROM conjugation_item_stats WHERE (:lang IS NULL OR targetLanguage = :lang)")
    suspend fun clearItemStats(lang: String?)
}
