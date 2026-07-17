package com.copiosa.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.copiosa.domain.model.AudioStatus
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.model.Role
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.RoutineRunStatus
import com.copiosa.domain.routine.RoutineType
import com.copiosa.domain.routine.TopicSource

/**
 * A saved session. Every conversation persists — the conversation list *is* the archive.
 */
@Entity(tableName = "conversations", indices = [Index("targetLanguage")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: Mode,
    val title: String,
    val subject: String? = null,
    val sourceUrl: String? = null,
    // Imported texts (URL_TRANSLATION): the extracted/pasted source shown in the "Original" tab and
    // fed to the chunked translator. The translated body is stored as the first assistant message.
    val originalTitle: String? = null,
    val originalText: String? = null,
    // Chunked-translation progress, persisted so a failed translation resumes where it stopped and
    // the reader can tell a finished text (translatedChunks == totalChunks) from a stalled partial.
    val translatedChunks: Int = 0,
    val totalChunks: Int = 0,
    val level: Cefr,
    val country: String,
    val targetLanguage: String,
    val nativeLanguage: String,
    val provider: ProviderId,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    // Whether the user has opened this since its latest content arrived. Batch-created chats start
    // unread so the list can flag the ones still waiting to be read; opening one marks it read.
    val read: Boolean = true,
    // User-flagged favourite, toggled from the list context menu or the open chat/text overflow menu.
    val starred: Boolean = false,
    // Reading position for imported texts: the first-visible block index + pixel offset, persisted so
    // the reader resumes where the user left off. finishedReading is set once they reach the bottom
    // and drives the Texts list's "read" coloring (more precise than the binary [read] used by chats).
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val finishedReading: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: Role,
    val content: String,
    val createdAt: Long,
    // Per-message snapshot of the settings used to produce a response (assistant turns).
    // Captured per-message because level/model can change mid-conversation.
    val model: String? = null,
    val level: Cefr? = null,
    val country: String? = null,
    // Structured metadata for assistant turns: a JSON-encoded ReplyMetadata (next topics, distinctive
    // words, vocab used/unused) parsed from the reply's trailing block. Null for user turns / no block.
    val metadata: String? = null,
)

@Entity(tableName = "flashcards", indices = [Index("targetLanguage")])
data class FlashcardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val front: String,
    val back: String,
    val contextSentence: String? = null,
    val sourceConversationId: Long? = null,
    // LLM-generated enrichment, populated on demand (single "explain" or batched "fill").
    val explanation: String? = null,
    val partOfSpeech: String? = null,
    val cefrLevel: String? = null,
    // Structured content fields, set on import (from a cleaned Anki deck's `la:*` tags) or on save.
    // [cardKind] gates LLM-vocab use + highlighting (only VOCAB/PHRASE). [gender] is M/F/MF/MPL/FPL
    // for display ("el"/"la"), null when not a gendered noun. [region] is a dialect slug (e.g. "mx").
    // [altForms] holds "; "-joined synonyms/gendered variants (primary stays in [front]), shown as
    // accepted answers on a back-to-front review. [frontIsTarget] is whether [front] is the target
    // language (false for a reverse card whose front is native) — drives the review prompt's flag.
    val cardKind: CardKind = CardKind.VOCAB,
    val gender: String? = null,
    val region: String? = null,
    val altForms: String? = null,
    // Precomputed surface forms for highlighting: a ";"-joined set of normalized inflections
    // (plurals/conjugations) of [front], so the renderer can light up "gatos" for a saved "gato"
    // without a per-open dictionary lookup. Filled on save/edit and by a one-time background backfill
    // (see FlashcardRepository); null means "not computed yet" (renderer falls back to the headword).
    val inflectedForms: String? = null,
    val frontIsTarget: Boolean = true,
    // Which side a review starts from. BOTH alternates randomly each review (app-created cards default
    // to this); TARGET_FIRST/NATIVE_FIRST pin the prompt side. Imported cards keep their Anki direction
    // (TARGET_FIRST for forward, NATIVE_FIRST for reverse) so they behave as before.
    val reviewDirection: ReviewDirection = ReviewDirection.TARGET_FIRST,
    val targetLanguage: String,
    val nativeLanguage: String,
    val createdAt: Long,
    // Spaced-repetition state. [dueAt]/[state]/[lastReviewedAt] plus the FSRS memory state
    // ([stability]/[difficulty]/[fsrsStep]) are what the FSRS engine reads and writes. The SM-2-shaped
    // fields ([intervalDays]/[easeFactor]/[repetitions]/[lapses]) are kept populated so an Anki .apkg
    // export has classic-scheduler columns to fill and so a card degrades gracefully if Anki ever
    // drops it back to SM-2. stability == null means the card is still new to FSRS.
    val dueAt: Long,
    val intervalDays: Double = 0.0,
    val easeFactor: Double = 2.5,
    val repetitions: Int = 0,
    val lapses: Int = 0,
    val lastReviewedAt: Long? = null,
    val state: FlashcardState = FlashcardState.NEW,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val fsrsStep: Int? = null,
    // "Frozen" — the user has set this word aside. Suspended cards are excluded from review *and*
    // from the random vocab woven into conversations, but keep their schedule so unfreezing restores
    // them where they left off. Mirrors Anki's per-card suspended flag.
    val suspended: Boolean = false,
    // User-flagged "marked" card (Anki's own term), toggled from review or the editor and used to
    // filter the vocabulary list. Round-trips through .apkg as Anki's native `marked` tag.
    val marked: Boolean = false,
    // Anki provenance + passthrough. Set on cards imported from a .apkg so a re-export reconstructs
    // the original note losslessly, and so a re-import updates the same card in place instead of
    // duplicating it. [ankiNoteGuid] + [ankiOrd] together identify one specific Anki card (a note's
    // forward/reverse templates are separate cards). [ankiFieldsJson] keeps every original field (the
    // front/back text above is only a display projection of two of them); [imageFile]/[audioFile] name
    // the card's media in the app media store. All null for cards created in Copiosa.
    val ankiNoteGuid: String? = null,
    val ankiOrd: Int? = null,
    val ankiNoteTypeId: Long? = null,
    val ankiFieldsJson: String? = null,
    val ankiTags: String? = null,
    val ankiDeckName: String? = null,
    val imageFile: String? = null,
    val audioFile: String? = null,
)

/**
 * A generated piece of listening audio in the playlist. The source [text] is rendered on-device by
 * the TTS engine to a WAV at [filePath]; [langCode] is the ISO target-language tag (e.g. "es") used
 * to drive Supertonic. Ordering is by ([played] ASC, [sortOrder] ASC) so played items sink to the
 * bottom of the list while keeping a user-arrangeable order within each group; [sortOrder] is a
 * fractional key so a drag-reorder only rewrites the moved rows. [sourceConversationId]/
 * [sourceMessageId] record where the audio came from (an imported Text or a chat reply), all null for
 * audio created some other way.
 */
@Entity(tableName = "audio_items", indices = [Index("langCode")])
data class AudioItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val text: String,
    val langCode: String,
    val sid: Int = 0,
    val status: AudioStatus = AudioStatus.PENDING,
    val filePath: String? = null,
    val durationMs: Long? = null,
    val played: Boolean = false,
    val sortOrder: Double,
    val createdAt: Long,
    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    // Short failure reason for a FAILED item, surfaced in the row (e.g. "Synthesis failed").
    val error: String? = null,
)

/**
 * One graded review of a flashcard — the full history that only [FlashcardEntity.lastReviewedAt]
 * used to summarise. Append-only; the source for Anki's `revlog` on export and the sink for it on
 * import, and the basis for any future FSRS parameter re-optimisation.
 */
@Entity(
    tableName = "reviews",
    foreignKeys = [
        ForeignKey(
            entity = FlashcardEntity::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("cardId")],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    // Epoch millis; doubles as the Anki revlog row id.
    val reviewedAt: Long,
    val rating: ReviewRating,
    // Interval (days) before and after this review — Anki's lastIvl / ivl.
    val lastIntervalDays: Double,
    val intervalDays: Double,
    val stabilityAfter: Double? = null,
    val difficultyAfter: Double? = null,
    // SM-2 ease snapshot (Anki factor) at review time.
    val easeFactor: Double,
    // Lifecycle state resulting from this review (drives Anki revlog `type`).
    val state: FlashcardState,
    val reviewDurationMs: Int? = null,
)

/**
 * One synthesized run of a [RoutineType] (e.g. the bedtime wind-down). The ordered, already-generated
 * activity sequence lives in [planJson] (a serialized RoutinePlan: activity instances + their content)
 * so a run survives process death and the bedtime story can continue night-to-night; [currentIndex] is
 * the resume point. [topicContext] preserves the context behind a (often cryptic) chosen topic so the
 * generator can make sense of it. [level]/[targetLanguage] snapshot the settings the run was built for.
 */
@Entity(tableName = "routine_runs")
data class RoutineRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineType: RoutineType,
    val durationMinutes: Int,
    val topic: String? = null,
    val topicContext: String? = null,
    val targetLanguage: String,
    val level: Cefr,
    val status: RoutineRunStatus = RoutineRunStatus.SYNTHESIZING,
    val planJson: String? = null,
    val currentIndex: Int = 0,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    // Short failure reason when status == FAILED.
    val error: String? = null,
)

/**
 * One attempt at an activity, logged silently (no score is ever shown to the user) so the adaptation
 * engine can estimate per-category capability. [correct] is null for no-right-answer activities
 * (affective PI), which are recorded only as completions. [category] sub-classifies an activity — e.g.
 * the contrast category "ser_estar" for a [ActivityType.CONTRAST_CLOZE] — and is null otherwise.
 * [difficulty] is the level the item was generated at, for "got it right *and* fast at this level".
 */
@Entity(
    tableName = "activity_attempts",
    indices = [Index("routineRunId")],
)
data class ActivityAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineRunId: Long,
    val activityType: ActivityType,
    val category: String? = null,
    val targetLanguage: String,
    val correct: Boolean? = null,
    val latencyMs: Long? = null,
    val difficulty: Int = 0,
    val createdAt: Long,
)

/**
 * The adaptation engine's running estimate of the learner's capability at one activity type or
 * contrast [bucketKey] in a [targetLanguage]. Updated after each scored attempt: [mastery] (0..1) rises
 * with fast, correct answers; [difficultyLevel] (fed to the generator) and [selectionWeight] (fed to
 * the planner) are derived from it so mastered material gets harder and appears less often.
 */
@Entity(
    tableName = "activity_proficiency",
    indices = [Index(value = ["bucketKey", "targetLanguage"], unique = true)],
)
data class ActivityProficiencyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // An ActivityType.name, or a finer contrast-category slug for CONTRAST_CLOZE (e.g. "ser_estar").
    val bucketKey: String,
    val targetLanguage: String,
    val mastery: Double = 0.3,
    val difficultyLevel: Int = 0,
    val selectionWeight: Double = 1.0,
    val attempts: Int = 0,
    val lastSeenAt: Long? = null,
)

/**
 * A topic the learner has shown interest in, used to seed routine content. Sourced from words they
 * looked up/translated, the subjects of chats they started, and the model's suggested next-topics
 * (cryptic on their own, so [description] keeps the context needed to make sense of them). Deduplicated
 * by ([phrase], [targetLanguage]); [calmScore] (0..1) biases bedtime seeds toward calming subjects.
 */
@Entity(
    tableName = "topics",
    indices = [Index(value = ["phrase", "targetLanguage"], unique = true)],
)
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phrase: String,
    val description: String? = null,
    val source: TopicSource,
    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    val calmScore: Double = 0.5,
    val targetLanguage: String,
    val useCount: Int = 0,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
)

/**
 * One rapid-fire listening pool per (language, cue type): the user's [configJson] (a serialized
 * QuizPoolConfig) plus the cue rows below. Numbers and Times are separate top-level activities, each
 * with its own independent pool, so a rebuild of one type never touches the other. Rebuilding a pool
 * replaces its row wholesale (cues cascade), so "the pool for a language + type" is always singular.
 */
@Entity(tableName = "quiz_pools", indices = [Index(value = ["langCode", "cueType"], unique = true)])
data class QuizPoolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val langCode: String,
    val cueType: CueType,
    val configJson: String,
    val createdAt: Long,
)

/**
 * One audio cue in a rapid-fire pool. [spokenText] is the words the TTS reads (seeded locally or by
 * the LLM, never digits); [displayText] is the digit form shown as an answer option; [valueKey] is the
 * canonical value ("73" / "06:15") distractors are re-derived from at session time. [sid] is the
 * random voice snapshotted at pool build. Rendering reuses the playlist's [AudioStatus] lifecycle so
 * the same queue-drain pattern (and its relaunch-resume semantics) applies.
 */
@Entity(
    tableName = "quiz_cues",
    foreignKeys = [
        ForeignKey(
            entity = QuizPoolEntity::class,
            parentColumns = ["id"],
            childColumns = ["poolId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("poolId"), Index(value = ["poolId", "status"])],
)
data class QuizCueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val poolId: Long,
    // The pool's language, denormalized so the render queue never joins back to quiz_pools.
    val langCode: String,
    val cueType: CueType,
    val valueKey: String,
    val displayText: String,
    val spokenText: String,
    val sid: Int = 0,
    val status: AudioStatus = AudioStatus.PENDING,
    val filePath: String? = null,
    val durationMs: Long? = null,
    // Short failure reason for a FAILED cue.
    val error: String? = null,
)

/**
 * The running state of the nightly serialized story (one thread per [targetLanguage]). [summary] is the
 * compact recap the next [SERIAL_STORY] chapter continues from; [chapter] is the count generated so far.
 */
@Entity(tableName = "story_threads", indices = [Index(value = ["targetLanguage"], unique = true)])
data class StoryThreadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetLanguage: String,
    val chapter: Int = 0,
    val summary: String = "",
    val updatedAt: Long,
)
