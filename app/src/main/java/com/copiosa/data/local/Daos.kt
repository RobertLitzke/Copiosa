package com.copiosa.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.copiosa.domain.model.AudioStatus
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.reading.ReadMode
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.ReviewDirection
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

    @Query("UPDATE conversations SET highlightWords = :on WHERE id = :id")
    suspend fun setHighlightWords(id: Long, on: Boolean?)

    @Query("UPDATE conversations SET read = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("UPDATE conversations SET scrollIndex = :index, scrollOffset = :offset WHERE id = :id")
    suspend fun setScroll(id: Long, index: Int, offset: Int)

    @Query("UPDATE conversations SET readMode = :mode WHERE id = :id")
    suspend fun setReadMode(id: Long, mode: ReadMode)

    @Query("UPDATE conversations SET finishedReading = :finished WHERE id = :id")
    suspend fun setFinishedReading(id: Long, finished: Boolean)

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

/** Due words in two piles: those with a due card studied before, and those due only as new cards. */
data class DueSplit(val reviewNotes: Int, val newNotes: Int)

private const val DUE_REVIEW_NOTES_SQL =
    "SELECT noteId FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 AND dueAt <= :now AND state != 'NEW'"

private const val FIRST_REVIEWED_SINCE_SQL =
    "SELECT COUNT(DISTINCT r.cardId) FROM reviews r JOIN flashcards f ON f.id = r.cardId " +
        "WHERE r.reviewedAt >= :since AND f.targetLanguage = :lang " +
        "AND NOT EXISTS (SELECT 1 FROM reviews p WHERE p.cardId = r.cardId AND p.reviewedAt < :since)"

private const val DUE_SPLIT_SQL =
    "SELECT (SELECT COUNT(DISTINCT noteId) FROM ($DUE_REVIEW_NOTES_SQL)) AS reviewNotes, " +
        "(SELECT COUNT(DISTINCT noteId) FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 " +
        "AND dueAt <= :now AND state = 'NEW' AND noteId NOT IN ($DUE_REVIEW_NOTES_SQL)) AS newNotes"

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

/**
 * Reads and writes the vocabulary: `notes` (content) and `cards` (one direction of a note, with its
 * schedule), read together through the `flashcards` view as [FlashcardEntity].
 *
 * Every `SELECT … FROM flashcards` returns **one row per card**, so a word studied both ways appears
 * twice. Queries that mean "per word" say so with `GROUP BY noteId`. Writes are split by which table
 * owns the column: text, grammar, media and provenance are the note's and reach every card of it;
 * due date, FSRS state and the frozen flag are one card's alone.
 */
@Dao
interface FlashcardDao {
    @Insert
    suspend fun insertNote(note: NoteEntity): Long

    @Insert
    suspend fun insertCard(card: CardEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Update
    suspend fun updateCard(card: CardEntity)

    /**
     * Stores [card] as a new note with its card — or with one card each way when its direction is
     * `BOTH`, both on the schedule the row carries. Returns the id of the card that shows the term
     * first, or of the only card.
     */
    @Transaction
    suspend fun insert(card: FlashcardEntity): Long = insertCardsFor(insertNote(card.toNote(id = 0)), card)

    /** Adds [card]'s card (or, for `BOTH`, both) to an existing note; see [insert]. */
    @Transaction
    suspend fun insertCardsFor(noteId: Long, card: FlashcardEntity): Long {
        if (card.reviewDirection != ReviewDirection.BOTH) return insertCard(card.toCard(noteId = noteId, id = 0))
        val first = insertCard(card.toCard(noteId = noteId, direction = ReviewDirection.TARGET_FIRST, id = 0))
        insertCard(card.toCard(noteId = noteId, direction = ReviewDirection.NATIVE_FIRST, id = 0))
        return first
    }

    /**
     * Writes [card]'s content back to its note. Every card of the note sees the change; no schedule
     * moves. Callers pass a row read from the view (so [FlashcardEntity.noteId] is set) with the
     * content fields edited.
     */
    suspend fun updateContent(card: FlashcardEntity) = updateNote(card.toNote())

    /**
     * Writes [card]'s schedule and frozen state back to its own card row, and nothing else. This is
     * what a review uses: a session can hold a card for minutes, and writing its stale copy of the
     * note back would undo an edit or an enrichment that landed in the meantime.
     */
    suspend fun updateSchedule(card: FlashcardEntity) = updateCard(card.toCard())

    /** Both halves of [card] — for an import that is authoritative for content and schedule alike. */
    @Transaction
    suspend fun update(card: FlashcardEntity) {
        updateContent(card)
        updateSchedule(card)
    }

    /**
     * One card per row, newest note first; a note's cards sit together, term-first card leading.
     *
     * In a transaction, like every query here that returns a whole deck's worth of rows: the result
     * is far too big for one cursor window, and each further window re-runs the query. Outside a
     * transaction a write landing between two windows changes what the second one sees, and when
     * it removes a row (a bulk import freezing a card that was due) the read runs off the end of
     * the result and throws.
     */
    @Transaction
    @Query("SELECT * FROM flashcards WHERE targetLanguage = :lang ORDER BY createdAt DESC, noteId DESC, reviewDirection DESC")
    fun observeAll(lang: String): Flow<List<FlashcardEntity>>

    /** Every card of one note, the term-first card leading. */
    @Query("SELECT * FROM flashcards WHERE noteId = :noteId ORDER BY reviewDirection DESC")
    suspend fun cardsOfNote(noteId: Long): List<FlashcardEntity>

    /** Live twin of [cardsOfNote], for a screen showing a word and both of its schedules. */
    @Query("SELECT * FROM flashcards WHERE noteId = :noteId ORDER BY reviewDirection DESC")
    fun observeCardsOfNote(noteId: Long): Flow<List<FlashcardEntity>>

    /**
     * One card per word saved from a conversation, a book or a routine run — pass the one that
     * applies and null for the others (an `= NULL` comparison matches nothing). Newest first. A word
     * learned both ways would otherwise appear twice, so the earliest card stands for the note.
     */
    @Query(
        "SELECT * FROM flashcards WHERE id IN (SELECT MIN(id) FROM flashcards WHERE " +
            "sourceConversationId = :conversationId OR sourceBookId = :bookId OR " +
            "sourceRoutineRunId = :routineRunId GROUP BY noteId) ORDER BY createdAt DESC, id DESC",
    )
    fun observeSavedFrom(conversationId: Long?, bookId: Long?, routineRunId: Long?): Flow<List<FlashcardEntity>>

    /** The note a previously imported Anki note was stored as, so a re-import adds cards to it. */
    @Query("SELECT id FROM notes WHERE ankiNoteGuid = :guid AND targetLanguage = :lang LIMIT 1")
    suspend fun noteIdForAnkiGuid(guid: String, lang: String): Long?

    /** Every card in [lang] (including frozen ones), the full set an Anki `.apkg` export is built from. */
    @Transaction
    @Query("SELECT * FROM flashcards WHERE targetLanguage = :lang ORDER BY createdAt ASC")
    suspend fun getAll(lang: String): List<FlashcardEntity>

    @Query("SELECT * FROM flashcards WHERE id = :id")
    fun observe(id: Long): Flow<FlashcardEntity?>

    @Query("SELECT * FROM flashcards WHERE id = :id")
    suspend fun getById(id: Long): FlashcardEntity?

    /**
     * How many *notes* in [lang] have a non-frozen card due at [now], split by whether the word has
     * been studied before — the two halves the daily new-card limit treats differently. Notes rather
     * than cards, because that is how many reviews are actually on offer: when both directions of a
     * word are due, answering one sends the other to tomorrow. A note with a due card that has been
     * studied counts as a review whatever its other direction is doing.
     */
    @Query(DUE_SPLIT_SQL)
    suspend fun dueSplit(lang: String, now: Long): DueSplit

    /** Flow twin of [dueSplit]. */
    @Query(DUE_SPLIT_SQL)
    fun observeDueSplit(lang: String, now: Long): Flow<DueSplit>

    /** Every non-frozen card in [lang], the pool a sized review session is composed from. */
    @Query("SELECT * FROM flashcards WHERE targetLanguage = :lang AND suspended = 0")
    suspend fun allActive(lang: String): List<FlashcardEntity>

    /**
     * Active cards carrying an example sentence the bank has no row for yet — the cloze miner's
     * pool.
     *
     * **The bank is the cursor.** Mining used to read [allActive] and stop after its budget of
     * candidates, which meant every run re-offered the same head of the deck: already-banked
     * sentences were absorbed by the insert's IGNORE and refused ones were refused again, so the
     * bank stopped growing while thousands of eligible sentences sat further down the list
     * (measured 2026-09-02: 4,226 sentences in the deck, bank stuck at 55). Excluding cards that
     * already produced a sentence makes every run examine material it has never seen, and keeps
     * `ClozeMineTask`'s "nothing needs to remember how far it got" true — no stored offset to
     * migrate, resume or get out of step with the bank.
     *
     * The exclusion is per *card*, not per source: a card whose sentence was banked from a book or
     * the dictionary pack is skipped here too. That is deliberate — the per-lemma cap would usually
     * refuse the second one anyway, and spending the budget on a word with no sentence at all beats
     * a near-duplicate for one that has one.
     *
     * **The order is random, and that is the other half of the fix.** Excluding banked cards only
     * advances past sentences that *succeeded*; a card the gate refuses is refused again every run
     * and never leaves the pool. Under a fixed `ORDER BY dueAt` those refusals silently become the
     * new permanent head — measured on device: the bank stopped at 1,478 with every run reporting
     * "Added 0, 120 didn't qualify", the same 120 each time. Sampling instead means a card that
     * becomes minable later (the learner's known words grow, so `TOO_HARD` is not forever) is found
     * within a few runs, and no card can block the ones behind it. Losing "soonest due first" costs
     * little: the bank is a pool to dip into, and the gate — not the order — decides quality.
     */
    @Query(
        "SELECT * FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 " +
            "AND contextSentence IS NOT NULL AND trim(contextSentence) <> '' " +
            // Per note: the sentence is the note's, so its second card must neither be offered
            // beside the first nor re-offer a sentence the first already banked.
            "AND noteId NOT IN (SELECT noteId FROM cards WHERE id IN " +
            "(SELECT cardId FROM cloze_sentences WHERE cardId IS NOT NULL)) " +
            "GROUP BY noteId ORDER BY RANDOM() LIMIT :limit",
    )
    suspend fun clozeMineCandidates(lang: String, limit: Int): List<FlashcardEntity>

    /**
     * The soonest-upcoming active saved words in [lang] (due on or before [maxDue]), the pool the
     * conversation vocab hints are sampled from. Restricted to lexical vocab (VOCAB/PHRASE — never
     * conjugation/grammar/cloze/sentence drills) and to the term-first card, so a note's two cards
     * don't both appear and it is the recognition schedule that decides what counts as upcoming.
     * The [maxDue] bound keeps "locked-in" words (due far out) out of the pool entirely.
     */
    @Query(
        "SELECT * FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 " +
            "AND reviewDirection = 'TARGET_FIRST' AND cardKind IN ('VOCAB', 'PHRASE') AND dueAt <= :maxDue " +
            "ORDER BY dueAt ASC LIMIT :poolSize",
    )
    suspend fun upcomingByLanguage(lang: String, maxDue: Long, poolSize: Int): List<FlashcardEntity>

    /** How many vocab-hint-eligible cards in [lang] are due on or before [maxDue] (same filter as
     *  [upcomingByLanguage]); used to decide whether the hint pool can use a tight due window. */
    @Query(
        "SELECT COUNT(*) FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 " +
            "AND reviewDirection = 'TARGET_FIRST' AND cardKind IN ('VOCAB', 'PHRASE') AND dueAt <= :maxDue",
    )
    suspend fun countUpcomingByLanguage(lang: String, maxDue: Long): Int

    /**
     * Hand-made cloze cards eligible for conversion into the cloze bank.
     *
     * Three filters, each load-bearing:
     *
     * - `autoSource IS NULL` keeps generated cards out. `GraduationCardMinter` mints course
     *   retention cards whose front *is* `{{c1::…}}` markup, stamped `course:<id>`; converting those
     *   would destroy the `removeAutoAdded` undo handle **and** flip `countFromSource` back to zero,
     *   so replaying a course's final lesson would re-mint the whole set.
     * - Media-bearing cards are excluded because the bank has no image or audio column, and silently
     *   dropping a media reference is worse than leaving the card alone.
     * - `{{c` rather than `{{` so a card that merely contains braces isn't swept up.
     */
    @Query(
        "SELECT * FROM flashcards WHERE targetLanguage = :lang AND autoSource IS NULL " +
            "AND imageFile IS NULL AND audioFile IS NULL " +
            "AND (front LIKE '%{{c%' OR back LIKE '%{{c%')",
    )
    suspend fun legacyClozeCards(lang: String): List<FlashcardEntity>

    /** How many such cards carry media, so the conversion dialog can say what it is leaving behind. */
    @Query(
        "SELECT COUNT(*) FROM flashcards WHERE targetLanguage = :lang AND autoSource IS NULL " +
            "AND (imageFile IS NOT NULL OR audioFile IS NOT NULL) " +
            "AND (front LIKE '%{{c%' OR back LIKE '%{{c%')",
    )
    suspend fun countLegacyClozeWithMedia(lang: String): Int

    /** Active saved target nouns that carry a clear gender — the pool a swipe-to-sort (gender) set draws. */
    @Query(
        "SELECT * FROM flashcards WHERE targetLanguage = :lang AND suspended = 0 " +
            "AND gender IN ('M', 'F') GROUP BY noteId ORDER BY RANDOM() LIMIT :limit",
    )
    suspend fun nounsWithGender(lang: String, limit: Int): List<FlashcardEntity>

    // Every `id` below is a *card* id — the handle every caller holds. A write to something the
    // note owns goes through the card to its note, so it is seen from both directions.

    /** Freezes or unfreezes one card (Anki "suspend") — one direction of a word, not the word. */
    @Query("UPDATE cards SET suspended = :suspended WHERE id = :id")
    suspend fun setSuspended(id: Long, suspended: Boolean)

    /** Freezes or unfreezes every card of the note that card [id] belongs to. */
    @Query("UPDATE cards SET suspended = :suspended WHERE noteId = (SELECT noteId FROM cards WHERE id = :id)")
    suspend fun setNoteSuspended(id: Long, suspended: Boolean)

    /** Toggles the "marked" flag (Anki "marked") on the note card [id] belongs to. */
    @Query("UPDATE notes SET marked = :marked WHERE id = (SELECT noteId FROM cards WHERE id = :id)")
    suspend fun setMarked(id: Long, marked: Boolean)

    /** Edits a note's text (typo fixes); leaves scheduling and enrichment untouched. */
    @Query("UPDATE notes SET term = :term, translation = :translation WHERE id = (SELECT noteId FROM cards WHERE id = :id)")
    suspend fun setText(id: Long, term: String, translation: String)

    /** Edits a note's text, alternate forms, part of speech, card type, gender, country, example and usage note from the card editor. */
    @Query(
        "UPDATE notes SET term = :term, translation = :translation, altForms = :altForms, partOfSpeech = :partOfSpeech, " +
            "cardKind = :cardKind, contextSentence = :contextSentence, gender = :gender, country = :country, " +
            "usageNote = :usageNote WHERE id = (SELECT noteId FROM cards WHERE id = :id)",
    )
    suspend fun setDetails(
        id: Long,
        term: String,
        translation: String,
        altForms: String?,
        partOfSpeech: String?,
        cardKind: String,
        contextSentence: String?,
        gender: String?,
        country: String?,
        usageNote: String?,
    )

    /** Caches a note's precomputed highlight surface forms (";"-joined); see [NoteEntity.inflectedForms]. */
    @Query("UPDATE notes SET inflectedForms = :forms WHERE id = (SELECT noteId FROM cards WHERE id = :id)")
    suspend fun setInflectedForms(id: Long, forms: String?)

    /** Splits a crammed translation: sets the clean translation and the peeled-out [usageNote]. */
    @Query("UPDATE notes SET translation = :translation, usageNote = :usageNote WHERE id = (SELECT noteId FROM cards WHERE id = :id)")
    suspend fun setBackAndNote(id: Long, translation: String, usageNote: String?)

    /**
     * Cards whose translation carries Markdown/multi-line structure — the backlog for the note-split
     * backfill (and repair of earlier mis-splits). Gated on bold markup or a newline in `back` so
     * clean single-line glosses are never scanned; a plain ML Kit translation has neither. (Note: in
     * SQL LIKE, `_` is a single-char wildcard, so an underscore clause would match nearly everything —
     * bold `**` plus the newline check already cover the generated blobs.)
     */
    @Query(
        "SELECT * FROM flashcards WHERE back LIKE '%**%' OR back LIKE '%' || char(10) || '%' GROUP BY noteId",
    )
    suspend fun cardsWithMarkdownBack(): List<FlashcardEntity>

    /** Notes whose highlight forms haven't been computed yet (one card each) — the backfill backlog. */
    @Query("SELECT * FROM flashcards WHERE inflectedForms IS NULL GROUP BY noteId")
    suspend fun cardsMissingInflectedForms(): List<FlashcardEntity>

    /** Sets (or clears, with null) the image filename on the note card [id] belongs to. */
    @Query("UPDATE notes SET imageFile = :file WHERE id = (SELECT noteId FROM cards WHERE id = :id)")
    suspend fun setImageFile(id: Long, file: String?)

    /**
     * Active lexical notes (VOCAB/PHRASE) in [lang] with no image yet, newest first, one card each —
     * the "add images" backlog.
     */
    @Query(
        "SELECT * FROM flashcards WHERE targetLanguage = :lang AND imageFile IS NULL AND suspended = 0 " +
            "AND cardKind IN ('VOCAB', 'PHRASE') GROUP BY noteId ORDER BY createdAt DESC",
    )
    suspend fun imagelessCards(lang: String): List<FlashcardEntity>

    /** Pushes one card's due date (passive review when the word is seen in reading material). */
    @Query("UPDATE cards SET dueAt = :dueAt WHERE id = :id")
    suspend fun setDueAt(id: Long, dueAt: Long)

    /**
     * The other cards of card [id]'s note that would come up before [before] — the ones a review of
     * [id] has to move, read first so the move can be undone.
     */
    @Query(
        "SELECT * FROM cards WHERE noteId = (SELECT noteId FROM cards WHERE id = :id) " +
            "AND id <> :id AND dueAt < :before",
    )
    suspend fun siblingsDueBefore(id: Long, before: Long): List<CardEntity>

    /**
     * Deletes the note card [id] belongs to — every card of it, and through them their review
     * history. Deleting a word means the word; one direction is set aside with [setSuspended].
     */
    @Query("DELETE FROM notes WHERE id = (SELECT noteId FROM cards WHERE id = :id)")
    suspend fun delete(id: Long)

    // --- auto-generated card provenance (see FlashcardEntity.autoSource) ---

    /**
     * Removes every card a given generator produced for this profile — the cleanup path for a batch
     * added by a since-fixed bug. Untouched cards the user has since studied are deleted too, which
     * is why nothing calls this without asking first.
     */
    @Query("DELETE FROM notes WHERE targetLanguage = :lang AND autoSource = :source")
    suspend fun deleteByAutoSource(lang: String, source: String): Int

    /** How many notes a generator has already produced — the "did this batch run?" check. */
    @Query("SELECT COUNT(*) FROM notes WHERE targetLanguage = :lang AND autoSource = :source")
    suspend fun countByAutoSource(lang: String, source: String): Int

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

    /**
     * Items still to be rendered (after a relaunch the queue is rebuilt from these).
     *
     * Untagged items come first, however long the tagged backlog is. A book is tens of chapters and
     * hours of on-device synthesis; without this, one "generate audio" on a novel would leave every
     * chat reply and highlighted selection stuck behind it, which is the opposite of what the
     * playlist is for.
     */
    @Query(
        "SELECT * FROM audio_items WHERE status IN ('PENDING', 'GENERATING') " +
            "ORDER BY (tag IS NOT NULL) ASC, sortOrder ASC",
    )
    suspend fun unrendered(): List<AudioItemEntity>

    /** One tag's items in track order — the folder screen, and the queue a "play all" builds. */
    @Query("SELECT * FROM audio_items WHERE tag = :tag AND langCode = :lang ORDER BY sortOrder ASC")
    fun observeTag(tag: String, lang: String): Flow<List<AudioItemEntity>>

    /** Chapters of [bookId] that already have a track, so a later pass only adds what's missing. */
    @Query(
        "SELECT DISTINCT sourceChapterIndex FROM audio_items " +
            "WHERE sourceBookId = :bookId AND sourceChapterIndex IS NOT NULL",
    )
    suspend fun chaptersWithAudio(bookId: Long): List<Int>

    /**
     * The tag [bookId]'s existing tracks were filed under. Reused rather than re-deriving from the
     * book's current title: a forward book renames itself once the LLM translates its title, and a
     * second generation pass would otherwise open a second folder for the same book.
     */
    @Query("SELECT tag FROM audio_items WHERE sourceBookId = :bookId AND tag IS NOT NULL LIMIT 1")
    suspend fun tagForBook(bookId: Long): String?

    @Query("SELECT * FROM audio_items WHERE sourceBookId = :bookId")
    suspend fun forBook(bookId: Long): List<AudioItemEntity>

    /** One book's tracks, live — what the generation notification ticks its progress off. */
    @Query("SELECT * FROM audio_items WHERE sourceBookId = :bookId ORDER BY sortOrder ASC")
    fun observeForBook(bookId: Long): Flow<List<AudioItemEntity>>

    @Query("SELECT * FROM audio_items WHERE tag = :tag AND langCode = :lang")
    suspend fun taggedItems(tag: String, lang: String): List<AudioItemEntity>

    @Query("DELETE FROM audio_items WHERE tag = :tag AND langCode = :lang")
    suspend fun deleteTag(tag: String, lang: String)

    /**
     * The "delete played" sweep's backlog — loose items only.
     *
     * A folder is a collection the learner deliberately created and can delete as a unit; dissolving
     * a finished audiobook one heard chapter at a time, from a button that reads as tidying up the
     * queue, is not what that button offers to do. Same reasoning as [playedBefore].
     */
    @Query("SELECT * FROM audio_items WHERE langCode = :lang AND played = 1 AND tag IS NULL")
    suspend fun playedItems(lang: String): List<AudioItemEntity>

    @Query("SELECT MAX(sortOrder) FROM audio_items")
    suspend fun maxSortOrder(): Double?

    @Query("UPDATE audio_items SET played = :played, sortOrder = :sortOrder, playedAt = :playedAt WHERE id = :id")
    suspend fun setPlayedAndOrder(id: Long, played: Boolean, sortOrder: Double, playedAt: Long?)

    /**
     * Marks played without touching [AudioItemEntity.sortOrder] — the tagged-item form, where the
     * track's position is its place in the book and must not move when it is heard.
     */
    @Query("UPDATE audio_items SET played = :played, playedAt = :playedAt, positionMs = 0 WHERE id = :id")
    suspend fun setPlayedKeepingOrder(id: Long, played: Boolean, playedAt: Long?)

    /** Also clears [AudioItemEntity.playedAt]: un-marking an item restarts its retention clock. */
    @Query("UPDATE audio_items SET played = :played, playedAt = NULL WHERE id = :id")
    suspend fun setPlayed(id: Long, played: Boolean)

    @Query("UPDATE audio_items SET positionMs = :ms WHERE id = :id")
    suspend fun setPosition(id: Long, ms: Long)

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

    @Query("DELETE FROM audio_items WHERE langCode = :lang AND played = 1 AND tag IS NULL")
    suspend fun deletePlayed(lang: String)

    /** Rows still pointing at legacy WAV renders, for the one-time AAC re-encode. */
    @Query("SELECT * FROM audio_items WHERE filePath LIKE '%.wav'")
    suspend fun legacyWavItems(): List<AudioItemEntity>

    /**
     * Items in [lang] whose playback finished before [cutoff] — the retention backlog. Grouped items
     * are exempt: retention exists to stop a queue of one-off listens accumulating, and a book the
     * learner asked to have read aloud is the opposite of that.
     */
    @Query(
        "SELECT * FROM audio_items WHERE played = 1 AND langCode = :lang AND tag IS NULL " +
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

    /**
     * How many cards in [lang] were reviewed for the first time at or after [since] — with [since]
     * at midnight, how much of the day's new-card allowance has been used. Read off the log rather
     * than kept as a counter, so undoing a review hands its place back without any bookkeeping.
     *
     * "First time" means no earlier log entry, so a card imported with a schedule but no history
     * counts once, on the day it is first answered here.
     */
    @Query(FIRST_REVIEWED_SINCE_SQL)
    suspend fun countFirstReviewedSince(lang: String, since: Long): Int

    /** Flow twin of [countFirstReviewedSince]. */
    @Query(FIRST_REVIEWED_SINCE_SQL)
    fun observeCountFirstReviewedSince(lang: String, since: Long): Flow<Int>

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

    /**
     * The newest run in [lang] that was begun and not finished — still being prepared, ready and
     * never opened, or left part-way — created at or after [since]. Failed runs are left out: one
     * has nothing to return to but a retry button.
     */
    @Query(
        "SELECT * FROM routine_runs WHERE targetLanguage = :lang " +
            "AND status IN ('SYNTHESIZING', 'READY', 'IN_PROGRESS') AND createdAt >= :since " +
            "ORDER BY createdAt DESC LIMIT 1",
    )
    fun observeLatestInProgress(lang: String, since: Long): Flow<RoutineRunEntity?>

    /** Every run in [lang] that never reached the end, failed ones included. */
    @Query("SELECT id FROM routine_runs WHERE targetLanguage = :lang AND status != 'COMPLETED'")
    suspend fun unfinishedIds(lang: String): List<Long>

    @Query("UPDATE routine_runs SET currentIndex = :index WHERE id = :id")
    suspend fun setCurrentIndex(id: Long, index: Int)

    @Query("DELETE FROM routine_runs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ActivityAttemptDao {
    @Insert
    suspend fun insert(attempt: ActivityAttemptEntity): Long
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

    /**
     * Seeds for a routine with no calm preference: recency/frequency only. Deliberately neutral rather
     * than the inverse of [suggestions] — a daytime session has no reason to prefer agitating themes.
     */
    @Query(
        "SELECT * FROM topics WHERE targetLanguage = :lang " +
            "ORDER BY lastUsedAt DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun recentSuggestions(lang: String, limit: Int): List<TopicEntity>

    @Query("SELECT * FROM topics WHERE targetLanguage = :lang ORDER BY createdAt DESC")
    fun observeForLanguage(lang: String): Flow<List<TopicEntity>>

    @Query("DELETE FROM topics WHERE id = :id")
    suspend fun delete(id: Long)
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

/** How many sentences the bank already holds for one lemma — the per-lemma cap, in one query. */
data class LemmaCount(val lemma: String, val total: Int)

@Dao
interface ClozeDao {

    // --- writing what the miners find ---

    /**
     * Banks new sentences, silently skipping any the bank already holds.
     *
     * [OnConflictStrategy.IGNORE] against the unique (language, lemma, textKey) index is the whole
     * of the miners' idempotency: they can rescan the same book on every background pass and this
     * inserts nothing. It is also what makes a suspended row a tombstone — a sentence the learner
     * threw out cannot be silently re-added, because the conflicting row is still there.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<ClozeSentenceEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ClozeSentenceEntity): Long

    @Update
    suspend fun update(row: ClozeSentenceEntity)

    @Query("SELECT * FROM cloze_sentences WHERE id = :id")
    suspend fun byId(id: Long): ClozeSentenceEntity?

    // --- composing a session ---

    /**
     * The pool a sitting is drawn from: everything not set aside, soonest-due first, so overdue
     * sentences come before ones never seen. The caller sizes the session and picks the order.
     */
    @Query(
        "SELECT * FROM cloze_sentences WHERE targetLanguage = :lang AND suspended = 0 " +
            "ORDER BY dueAt ASC LIMIT :limit",
    )
    suspend fun studyPool(lang: String, limit: Int): List<ClozeSentenceEntity>

    /**
     * Sentences for listen-and-rebuild: ones already met in a cloze sitting first, so the ear is
     * working on familiar words, then the rest — each group in random order. Not due-ordered: this
     * activity keeps no schedule of its own and must not drain the cloze bank's.
     */
    @Query(
        "SELECT * FROM cloze_sentences WHERE targetLanguage = :lang AND suspended = 0 " +
            "ORDER BY (repetitions > 0) DESC, RANDOM() LIMIT :limit",
    )
    suspend fun rebuildPool(lang: String, limit: Int): List<ClozeSentenceEntity>

    @Query("SELECT COUNT(*) FROM cloze_sentences WHERE targetLanguage = :lang AND suspended = 0 AND dueAt <= :now")
    suspend fun countDue(lang: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM cloze_sentences WHERE targetLanguage = :lang AND suspended = 0 AND dueAt <= :now")
    fun observeCountDue(lang: String, now: Long): Flow<Int>

    /** Bank size for this language, against which the mining cap is applied. */
    @Query("SELECT COUNT(*) FROM cloze_sentences WHERE targetLanguage = :lang")
    suspend fun countAll(lang: String): Int

    @Query("SELECT COUNT(*) FROM cloze_sentences WHERE targetLanguage = :lang AND suspended = 0")
    fun observeCountActive(lang: String): Flow<Int>

    /**
     * Sentences already banked per lemma — including suspended ones, so a lemma whose sentences the
     * learner rejected doesn't immediately refill from the same source.
     */
    @Query("SELECT lemma, COUNT(*) AS total FROM cloze_sentences WHERE targetLanguage = :lang GROUP BY lemma")
    suspend fun countsByLemma(lang: String): List<LemmaCount>

    // --- the learner throwing an item out ---

    @Query("UPDATE cloze_sentences SET suspended = :suspended, flaggedReason = :reason WHERE id = :id")
    suspend fun setFlag(id: Long, suspended: Boolean, reason: String?)

    @Query("UPDATE cloze_sentences SET distractors = :distractors WHERE id = :id")
    suspend fun setDistractors(id: Long, distractors: String?)

    // --- back-filling translations ---

    /**
     * Banked sentences with no translation, soonest-due first.
     *
     * Most of the bank arrives this way: the local miners have no bilingual text to draw on and bank
     * `translation = null` rather than guess, so this is the normal state of a sentence rather than a
     * fault. Due order means the ones about to be seen are translated first.
     */
    @Query(
        "SELECT * FROM cloze_sentences WHERE targetLanguage = :lang AND suspended = 0 " +
            "AND (translation IS NULL OR TRIM(translation) = '') ORDER BY dueAt ASC LIMIT :limit",
    )
    suspend fun untranslated(lang: String, limit: Int): List<ClozeSentenceEntity>

    @Query("UPDATE cloze_sentences SET translation = :translation WHERE id = :id")
    suspend fun setTranslation(id: Long, translation: String)

    @Query("SELECT * FROM cloze_sentences WHERE targetLanguage = :lang AND (suspended = 1 OR flaggedReason IS NOT NULL) ORDER BY id DESC")
    fun observeFlagged(lang: String): Flow<List<ClozeSentenceEntity>>

    /**
     * Items the learner reported as having more than one answer, still in circulation — the queue
     * the LLM adjudication pass works off. Flagged rows go before never-checked ones: an item that
     * has actually annoyed someone is worth more than a speculative check.
     */
    @Query(
        "SELECT * FROM cloze_sentences WHERE targetLanguage = :lang AND suspended = 0 " +
            "AND flaggedReason = :reason ORDER BY id ASC LIMIT :limit",
    )
    suspend fun flaggedFor(lang: String, reason: String, limit: Int): List<ClozeSentenceEntity>

    @Query("DELETE FROM cloze_sentences WHERE id = :id")
    suspend fun delete(id: Long)

    // --- Anki provenance ---

    /**
     * Whether an imported Anki cloze card has already been converted into the bank. Without this a
     * re-import of the same .apkg would recreate the flashcard the conversion deleted, silently
     * undoing it.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM cloze_sentences WHERE ankiNoteGuid = :guid AND ankiOrd = :ord)")
    suspend fun existsForAnkiKey(guid: String, ord: Int): Boolean

    // --- the review log ---

    @Insert
    suspend fun insertReview(review: ClozeReviewEntity): Long

    @Insert
    suspend fun insertReviews(reviews: List<ClozeReviewEntity>)

    // --- stats. Scoped by joining the sentence, since the log itself carries no language. ---

    @Query(
        "SELECT COUNT(*) FROM cloze_reviews r JOIN cloze_sentences s ON s.id = r.clozeId " +
            "WHERE (:lang IS NULL OR s.targetLanguage = :lang)",
    )
    fun observeAnswered(lang: String?): Flow<Int>

    /** AGAIN is the only rating a wrong answer can produce, so anything else was correct. */
    @Query(
        "SELECT COUNT(*) FROM cloze_reviews r JOIN cloze_sentences s ON s.id = r.clozeId " +
            "WHERE r.rating != 'AGAIN' AND (:lang IS NULL OR s.targetLanguage = :lang)",
    )
    fun observeCorrect(lang: String?): Flow<Int>

    @Query(
        "SELECT AVG(r.latencyMs) FROM cloze_reviews r JOIN cloze_sentences s ON s.id = r.clozeId " +
            "WHERE r.rating != 'AGAIN' AND r.latencyMs IS NOT NULL " +
            "AND (:lang IS NULL OR s.targetLanguage = :lang)",
    )
    fun observeAvgLatencyMs(lang: String?): Flow<Double?>

    @Query("SELECT COUNT(*) FROM cloze_sentences WHERE (:lang IS NULL OR targetLanguage = :lang)")
    fun observeBankSize(lang: String?): Flow<Int>

    /** Sentences whose interval has reached the deck's own maturity line. */
    @Query(
        "SELECT COUNT(*) FROM cloze_sentences WHERE intervalDays >= :threshold " +
            "AND (:lang IS NULL OR targetLanguage = :lang)",
    )
    fun observeMatureCount(lang: String?, threshold: Double): Flow<Int>
}
