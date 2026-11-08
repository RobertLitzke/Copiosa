package com.copiosa.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.copiosa.domain.listening.RadioScript
import com.copiosa.domain.model.AudioStatus
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.model.Role
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.reading.ReadMode
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.stats.FocusActivityType
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
    // Per-item override of AppSettings.highlightSavedWords: whether due vocabulary is tinted in this
    // chat/text. Null (the default) follows the language's setting, so flipping that moves every
    // item that has never been touched individually.
    val highlightWords: Boolean? = null,
    // Reading position for imported texts: the first-visible block index + pixel offset, persisted so
    // the reader resumes where the user left off. finishedReading is set once they reach the bottom
    // and drives the Texts list's "read" coloring (more precise than the binary [read] used by chats).
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val finishedReading: Boolean = false,
    // Which language version the reader was last showing, kept alongside the position for the same
    // reason: reopening a text should put it back exactly as it was left, not reset it to Target.
    val readMode: ReadMode = ReadMode.TARGET,
    // Background-retry bookkeeping only. Failure itself is still *derived* (a chat whose newest
    // message is a USER turn is awaiting a reply) — these two just bound how often the maintenance
    // task retries one, so a permanently-broken chat eventually stops costing calls. Reset once a
    // reply lands.
    val autoRetryAttempts: Int = 0,
    val lastAutoRetryAt: Long? = null,
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
    // The model's unparsed output for an assistant turn (body + trailing metadata block), kept only as
    // a debug aid and only for the most recent chats — see ConversationRepository.pruneRawResponses.
    // Null for user turns, older/pruned replies, and replies made before this was tracked.
    val rawResponse: String? = null,
)

// The vocabulary tables — NoteEntity, CardEntity and the FlashcardEntity view over them — live in
// Flashcards.kt.

/**
 * A generated piece of listening audio in the playlist. The source [text] is rendered on-device by
 * the TTS engine to a WAV at [filePath]; [langCode] is the ISO target-language tag (e.g. "es") used
 * to drive Supertonic. Ordering is by ([played] ASC, [sortOrder] ASC) so played items sink to the
 * bottom of the list while keeping a user-arrangeable order within each group; [sortOrder] is a
 * fractional key so a drag-reorder only rewrites the moved rows. [sourceConversationId]/
 * [sourceMessageId] record where the audio came from (an imported Text or a chat reply), all null for
 * audio created some other way.
 *
 * A [tag] groups items into what the Audio pane renders as a folder — a book's chapters all carry
 * the book's title. Tagged items are exempt from the played-items-sink rule: a book must stay in
 * chapter order forever, so [markPlayed][com.copiosa.data.repository.AudioRepository.markPlayed]
 * leaves their [sortOrder] alone.
 */
@Entity(tableName = "audio_items", indices = [Index("langCode"), Index("tag")])
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
    // Epoch-ms when playback finished, and the clock the retention sweep runs off. Null for
    // never-played items; cleared again when an item is marked unlistened.
    val playedAt: Long? = null,
    val sortOrder: Double,
    val createdAt: Long,
    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    // Short failure reason for a FAILED item, surfaced in the row (e.g. "Synthesis failed").
    val error: String? = null,
    // Free-text grouping label, null for a loose playlist item. Deliberately the display name rather
    // than a key, so the concept generalises past books without a second table.
    val tag: String? = null,
    // Provenance for book audio, the same idiom as sourceConversationId. Kept alongside [tag] because
    // the tag is a name the user sees (and a re-translated book may rename itself), whereas picking up
    // where generation left off needs a stable identity.
    val sourceBookId: Long? = null,
    val sourceChapterIndex: Int? = null,
    // Resume point in ms, for tracks too long to restart from the top. Zeroed when marked played.
    val positionMs: Long = 0,
    // What [text] is, and so how it is spoken: "ARTICLE" is prose read through; "RADIO" is a script
    // of sentences with their translations (see RadioScript). A plain slug, as on book_paragraphs,
    // so a new kind needs no migration and an unknown one is simply read as prose.
    val kind: String = RadioScript.KIND_ARTICLE,
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
            entity = CardEntity::class,
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
 * One cloze sentence in the practice bank: a sentence the learner has already met, with one word
 * blanked, carrying its own FSRS schedule.
 *
 * [text] holds Anki-style `{{c1::answer}}` markup so [com.copiosa.domain.model.Cloze] renders it
 * with no new parser and no third blank convention. [answer] and [lemma] are denormalised out of it
 * because every query that matters — dedup, the per-lemma cap, distractor choice — needs them
 * without parsing.
 *
 * [cardId] deliberately carries **no** foreign key. A cascade would delete a perfectly good sentence
 * when its vocab card is deleted, and the sentence is independently valuable: it just stops nudging.
 * A dangling id resolves to null and the row keeps working.
 *
 * [textKey] is the dedup key — the sentence lowercased, stripped of punctuation and collapsed
 * whitespace, with the answer left in. Unique per (language, lemma), which is what makes every miner
 * idempotent: they can re-scan the same book every six hours and insert nothing.
 *
 * [ankiNoteGuid]/[ankiOrd] are set only on rows converted from an imported Anki cloze card, so a
 * later re-import of the same .apkg recognises the card as already converted instead of recreating
 * the flashcard the conversion deleted.
 */
@Entity(
    tableName = "cloze_sentences",
    indices = [
        Index("targetLanguage", "dueAt"),
        Index("cardId"),
        Index(value = ["targetLanguage", "lemma", "textKey"], unique = true),
    ],
)
data class ClozeSentenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetLanguage: String,
    val text: String,
    val answer: String,
    val lemma: String,
    val textKey: String,
    val cardId: Long? = null,
    val translation: String? = null,
    // "; "-joined wrong options, following FlashcardEntity.altForms. Null means "pick fresh ones on
    // next sight" — which is also how a learner's "could be more than one answer" flag clears them.
    val distractors: String? = null,
    // Provenance and the undo handle: "context", "dict", "book:<id>", "llm", "legacy:<cardId>".
    val source: String,
    // Set aside by the learner (a bad sentence) — and the tombstone that stops a miner re-adding it.
    val suspended: Boolean = false,
    // Why it was flagged: "ambiguous" (options regenerate) or "wrong" (suspended for good).
    val flaggedReason: String? = null,
    // FSRS state, mirroring FlashcardEntity. No easeFactor: that column exists only to give an .apkg
    // export a classic-scheduler value, and cloze rows are not exported.
    val dueAt: Long,
    val intervalDays: Double = 0.0,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val fsrsStep: Int? = null,
    val repetitions: Int = 0,
    val lapses: Int = 0,
    val lastReviewedAt: Long? = null,
    val state: FlashcardState = FlashcardState.NEW,
    val ankiNoteGuid: String? = null,
    val ankiOrd: Int? = null,
    val createdAt: Long,
    // Kept by the learner: favoured when a sitting is composed and never rotated out of the bank.
    val pinned: Boolean = false,
    // The translation came from the on-device translator rather than a source or a model. Good enough
    // to read, and the marker that lets the background model pass replace it with a better one.
    val machineTranslated: Boolean = false,
)

/**
 * One graded answer on a cloze sentence — the same append-only shape as [ReviewLogEntity].
 *
 * It exists for three reasons: converting a hand-made cloze flashcard copies its `reviews` rows here
 * so deleting the card loses no history; the stats screen reads accuracy and speed from it; and a
 * schedule with no log could never contribute to a future FSRS parameter fit, which is the stated
 * purpose of the flashcard review log it mirrors.
 */
@Entity(
    tableName = "cloze_reviews",
    foreignKeys = [
        ForeignKey(
            entity = ClozeSentenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["clozeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clozeId")],
)
data class ClozeReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clozeId: Long,
    val reviewedAt: Long,
    val rating: ReviewRating,
    val lastIntervalDays: Double,
    val intervalDays: Double,
    val stabilityAfter: Double? = null,
    val difficultyAfter: Double? = null,
    val state: FlashcardState,
    // How long the learner took to answer, which is what chose the rating. Null on rows carried over
    // from a converted flashcard, where the original review recorded no duration.
    val latencyMs: Long? = null,
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
    // Serialized PhraseOptions for a PHRASE cue (correct + four stored distractors); null for
    // algorithmic cues, which re-derive their distractors at session time.
    val optionsJson: String? = null,
)

/**
 * An imported EPUB book, read as a level-adapted translation. Unlike imported texts (which keep the
 * whole source in [ConversationEntity.originalText]), a book's content lives in per-paragraph
 * [BookParagraphEntity] rows so a whole novel is never loaded (or re-written) at once.
 *
 * Translation happens in "sections" (~2 pages of source text, assigned at import); the frontier is
 * the single counter [translatedSections] — sections `0..translatedSections-1` are fully stored —
 * mirroring the conversations' translatedChunks/totalChunks resume pattern. Content is always
 * persisted before the counter advances, so a crash never over-claims.
 *
 * [direction] decides what that translation is *for*. A [BookDirection.REVERSE] book is already in
 * the target language and is readable in full the moment it is imported; its translation pass is an
 * optional native-language gloss, which is why such a book is imported at
 * [BookDownloadMode.OFF] and can cost nothing.
 */
@Entity(tableName = "books", indices = [Index("targetLanguage")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Target-language title once section 0's title call lands; the original title until then.
    val title: String,
    val originalTitle: String,
    val author: String? = null,
    // Filename inside files/book_covers (never a path), so backups restore cleanly. Null = no cover.
    val coverFile: String? = null,
    val sourceFileName: String? = null,
    // Settings snapshot at import time, as conversations do.
    // [level] is the grading target for FORWARD books. REVERSE books still carry it (it is the
    // profile default at import) but nothing reads it — a native-language gloss is never levelled.
    val level: Cefr,
    val direction: BookDirection = BookDirection.FORWARD,
    val country: String,
    val targetLanguage: String,
    val nativeLanguage: String,
    val provider: ProviderId,
    val model: String,
    val totalParagraphs: Int,
    // Source-word count, for the library row's reading-time estimate.
    val wordCount: Int,
    val totalSections: Int,
    val translatedSections: Int = 0,
    // How eagerly sections are translated: read-ahead only, the background trickle, a full-speed
    // download, or nothing at all. The single knob for what this book is allowed to spend.
    val downloadMode: BookDownloadMode = BookDownloadMode.WHILE_READING,
    // Reading position: ord of the first visible paragraph + pixel offset within it (the
    // scrollIndex/scrollOffset pattern from conversations).
    val posParagraph: Int = 0,
    val posOffset: Int = 0,
    // The language version last shown, persisted with the position (the conversations idiom again):
    // a novel being read in Both mode reopens in Both mode.
    val readMode: ReadMode = ReadMode.TARGET,
    val finishedReading: Boolean = false,
    val starred: Boolean = false,
    // Per-item override of AppSettings.highlightSavedWords; null follows the language's setting.
    val highlightWords: Boolean? = null,
    val createdAt: Long,
    // Moves when a *section is stored*, not when the book is read: it is the translation pipeline's
    // progress clock (see BookDao.pendingSlowTranslate). "Which book was I reading" is [lastReadAt].
    val updatedAt: Long,
    // When the reading position last moved. Kept apart from [updatedAt] because a book downloading
    // in the background is touched constantly without being read at all, and so used to pass for
    // the one in progress. Null until the book is first scrolled after this column existed.
    val lastReadAt: Long? = null,
    // Background-retry bookkeeping for the slow-translate task, reset on every successful section
    // (the conversations idiom: paces how often maintenance retries a failing book).
    val autoRetryAttempts: Int = 0,
    val lastAutoRetryAt: Long? = null,
    // Why the last attempt failed, as com.copiosa.data.remote.code; null once a section stores
    // successfully. Persisted rather than kept in the manager's in-memory error map because a
    // background stall is only ever *read* long after the process that produced it has gone: the
    // count that stopped moving is otherwise the whole diagnosis the reader gets.
    val lastError: String? = null,
)

/**
 * One paragraph of an imported book. [ord] is dense (`0..totalParagraphs-1`), assigned once at
 * import and never edited, so the reader's list index maps 1:1 onto rows. The translation is stored
 * **on the same row** — that 1:1 identity is what makes the original/translated toggle exact
 * (same list items, no index remapping).
 *
 * [kind] is a plain TEXT slug rather than an enum through [Converters], so an unknown future value
 * can never crash a read. That tolerance is what let the slug set grow past "PARA"/"HEADING" to
 * cover lists, tables, footnotes and images without a migration: rows written by an older build
 * carry only the two original slugs and stay valid, and any reader that meets a slug it does not
 * know must fall back to rendering the row as a paragraph rather than matching exhaustively.
 * [chapterIndex] is the 0-based content-chapter the paragraph came from; grouping by it is what the
 * reader's table of contents is built from (see `com.copiosa.domain.book.buildToc`).
 *
 * Which of [originalText]/[translatedText] the learner is *studying* depends on the book's
 * [BookEntity.direction] — see `com.copiosa.domain.book.targetText`.
 */
@Entity(
    tableName = "book_paragraphs",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId", "ord"], unique = true),
        Index(value = ["bookId", "sectionIndex"]),
    ],
)
data class BookParagraphEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val ord: Int,
    val chapterIndex: Int,
    // The translation unit (section) this paragraph belongs to, assigned at import.
    val sectionIndex: Int,
    val kind: String,
    // Markdown: "## Title" headings, *italic*, **bold*, "- "/"1. " list items with two spaces of
    // indent per nesting level, GFM pipe tables, "[^3]: " note bodies, "![](2.jpg)" images. Read
    // structure out of it only via com.copiosa.data.epub.BlockMarkup, and only from *this* column —
    // translatedText is trimmed and reworded in transit.
    val originalText: String,
    // Null until the paragraph's section is translated; a null inside a translated section is an
    // alignment miss and the reader falls back to showing the original in a muted style.
    val translatedText: String? = null,
) {
    companion object {
        // Frozen identifiers — persisted as TEXT. Mirrors com.copiosa.data.epub.BlockKind; add
        // here and there together, and never rename one that has been written to a database.
        const val KIND_PARA = "PARA"
        const val KIND_HEADING = "HEADING"
        const val KIND_LIST = "LIST"
        const val KIND_TABLE = "TABLE"
        const val KIND_NOTE = "NOTE"
        const val KIND_IMAGE = "IMAGE"
    }
}

/**
 * Which model produced one section's translation. A section is exactly one LLM call, so this is the
 * provenance record for that call — a book can be translated by more than one model over its life
 * (the download-mode dialog can change [BookEntity.model] mid-book), and this is what lets the
 * dialog say "12 sections were done with a different model" and offer to redo them.
 *
 * One row per *translated* section, written in the same transaction that stores the section's text,
 * so it can never claim a translation that isn't there. Keyed by section rather than carried on
 * every [BookParagraphEntity]: ~60–150 rows a book instead of several thousand.
 */
@Entity(
    tableName = "book_sections",
    primaryKeys = ["bookId", "sectionIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookSectionEntity(
    val bookId: Long,
    val sectionIndex: Int,
    val provider: ProviderId,
    val model: String,
    val translatedAt: Long,
)

/**
 * One focused-study session: a contiguous stretch of one [FocusActivityType] in one profile.
 * Append-only, written by the FocusTimeTracker: inserted on the first heartbeat flush and its
 * [durationMs] grown on later flushes, so a crash loses at most one heartbeat interval.
 *
 * [epochDay] is `LocalDate.toEpochDay()` of [startedAt] in the device zone, denormalized so daily
 * aggregation never re-derives day boundaries; the tracker splits sessions at midnight so a row
 * never spans days. [rolledUp] flips once a [FocusDailyStatEntity] row has absorbed this session —
 * aggregate queries UNION rollups with `rolledUp = 0` raw rows so each session counts exactly once.
 * [sourceId] is a seam for future per-source stats (conversation / audio item / run id).
 */
@Entity(
    tableName = "focus_sessions",
    indices = [Index(value = ["langTag", "epochDay"]), Index("rolledUp")],
)
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val langTag: String,
    val activityType: FocusActivityType,
    val startedAt: Long,
    val durationMs: Long,
    val epochDay: Long,
    val sourceId: Long? = null,
    val rolledUp: Boolean = false,
)

/**
 * Daily rollup of [FocusSessionEntity] rows for one (profile, day, activity), maintained by the
 * focus-stats maintenance task for completed days only — today always comes from raw sessions.
 * Future per-day stats either become columns here or sibling tables sharing the (langTag, epochDay)
 * key.
 */
@Entity(tableName = "focus_daily_stats", primaryKeys = ["langTag", "epochDay", "activityType"])
data class FocusDailyStatEntity(
    val langTag: String,
    val epochDay: Long,
    val activityType: FocusActivityType,
    val totalDurationMs: Long,
    val sessionCount: Int,
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

/**
 * Per-item state for the conjugation drill: what the selector reads to decide what to ask next.
 *
 * [itemKey] is the drill's own composite key (`1|produce|hablar||pres.1s`) built by
 * [com.copiosa.domain.conjugation.DrillItem]; a generalize card's key names an irregularity class
 * rather than a verb, because the skill it measures is the pattern. [mastery] is an EMA over graded
 * answers, weighted by how fast they came — see
 * [com.copiosa.domain.conjugation.ConjugationMastery], whose fast threshold is deliberately much
 * tighter than the routine engine's.
 */
@Entity(
    tableName = "conjugation_item_stats",
    indices = [Index(value = ["itemKey", "targetLanguage"], unique = true)],
)
data class ConjugationItemStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemKey: String,
    val targetLanguage: String,
    val attempts: Int = 0,
    val correct: Int = 0,
    val mastery: Double = 0.3,
    val avgLatencyMs: Long = 0,
    val lastLatencyMs: Long? = null,
    val lastSeenAt: Long? = null,
)

/**
 * One graded conjugation answer, append-only.
 *
 * This exists because [ActivityAttemptEntity.routineRunId] is non-null, so it cannot hold attempts
 * from a standalone drill. [latencyMs] is the **raw** prompt-to-reveal time, stored unscaled so the
 * mastery thresholds can be retuned later and the history re-scored; only the copy handed to the
 * routine planner is rescaled.
 *
 * [infinitive] records the verb actually shown, which [itemKey] omits for generalize cards. The enum
 * columns hold slugs as plain text rather than going through [Converters] because the stats queries
 * group by them in SQL, the same trade-off `activity_proficiency.bucketKey` makes.
 */
@Entity(
    tableName = "conjugation_attempts",
    indices = [Index(value = ["targetLanguage", "epochDay"]), Index("itemKey")],
)
data class ConjugationAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemKey: String,
    val infinitive: String,
    val tense: String,
    val person: String,
    val direction: String,
    val family: String,
    val targetLanguage: String,
    val grade: String,
    val correct: Boolean,
    val latencyMs: Long,
    val createdAt: Long,
    val epochDay: Long,
)

/**
 * Where a learner stands with one authored course (see `domain/course/CourseCatalog`). One row per
 * (course, language); created on first open. [status] holds a `CourseStatus` name and the checkup
 * fields drive the post-graduation retention ladder: [checkupStage] indexes the expanding interval
 * ladder and [nextCheckupAt] is when the hub/Home nudge lights up — computed on read, no background
 * task. Status names and courseId slugs are frozen identifiers.
 */
@Entity(
    tableName = "course_progress",
    indices = [Index(value = ["courseId", "targetLanguage"], unique = true)],
)
data class CourseProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: String,
    val targetLanguage: String,
    val status: String = "ACTIVE",
    val startedAt: Long,
    val graduatedAt: Long? = null,
    val checkupStage: Int = 0,
    val nextCheckupAt: Long? = null,
    val lastCheckupAt: Long? = null,
)

/**
 * Per-lesson standing inside a course. [needsReview] is set by a failed checkup — the hub then
 * offers the lesson's micro-lesson — and cleared when it is retaken. [generatedItemsJson] keeps the
 * lesson's best validated LLM items (a serialized segment list) so checkups and micro-lessons have
 * contextual material offline.
 */
@Entity(
    tableName = "course_lesson_state",
    indices = [Index(value = ["courseId", "lessonId", "targetLanguage"], unique = true)],
)
data class CourseLessonStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: String,
    val lessonId: String,
    val targetLanguage: String,
    val completedAt: Long? = null,
    val passes: Int = 0,
    val lastAccuracy: Double? = null,
    val needsReview: Boolean = false,
    val generatedItemsJson: String? = null,
)

/**
 * One course sitting — a lesson, a checkup, or a micro-lesson — mirroring [RoutineRunEntity] where
 * it matters: [planJson] is a serialized `LessonPlan` so the sitting survives process death, and
 * [currentIndex] is the resume point. [kind] holds a `SessionKind` name and [status] a
 * `CourseSessionStatus` name; both frozen. [lessonId] is null for a checkup, which spans the course.
 */
@Entity(
    tableName = "course_sessions",
    indices = [Index(value = ["courseId", "targetLanguage"])],
)
data class CourseSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: String,
    val lessonId: String? = null,
    val kind: String,
    val targetLanguage: String,
    val status: String = "SYNTHESIZING",
    val planJson: String? = null,
    val currentIndex: Int = 0,
    val createdAt: Long,
    val completedAt: Long? = null,
    // Short failure reason when status == FAILED.
    val error: String? = null,
)

/**
 * The usage meter: what model calls consumed, summed per local day for each (provider, model,
 * feature) — a running total rather than a log, so a year of chatting is a few thousand rows.
 *
 * [feature] is an [com.copiosa.domain.usage.LlmFeature] name, stored as text so a name written by a
 * newer build is kept rather than coerced. [bookId] is the book a call was made for, 0 for none:
 * that is what lets a book say what it has cost. It is deliberately not a foreign key — deleting a
 * book must not delete the record of money already spent on it.
 *
 * [costReported] is part of the key because the two kinds of row are read differently. Where the
 * provider billed the call (OpenRouter), [costUsd] is that bill and is the truth. Where it reported
 * tokens only (Gemini), [costUsd] stays 0 and the cost is estimated from the tokens when the row is
 * read (see [com.copiosa.domain.usage.LlmPricing]) — so a corrected price reprices history.
 *
 * [promptTokens] includes [cachedTokens]; [outputTokens] includes thinking.
 */
@Entity(
    tableName = "llm_usage",
    primaryKeys = ["day", "provider", "model", "feature", "bookId", "costReported"],
)
data class LlmUsageEntity(
    /** Local-zone epoch day, the convention `focus_sessions.epochDay` uses. */
    val day: Long,
    val provider: ProviderId,
    val model: String,
    val feature: String,
    val bookId: Long = 0,
    val costReported: Boolean,
    val calls: Int,
    val promptTokens: Long,
    val cachedTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
)
